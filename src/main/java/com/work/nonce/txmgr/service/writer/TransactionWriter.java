package com.work.nonce.txmgr.service.writer;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.domain.TxState;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.SubmitterNonceCursorMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.service.NotLeaderException;
import com.work.nonce.txmgr.service.nonce.MaxNonceAllocator;
import com.work.nonce.txmgr.service.nonce.NonceCache;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;

/**
 * 111最终方案.md：writer worker 路由 + batch + 两段式幂等（二次校验）+ 冲突请求不消耗 nonce。
 *
 * 说明：此实现为“渐进式里程碑1”的可运行骨架：
 * - 已具备 worker 路由、batch、pre-insert 幂等校验、max(chain,cache,db) 分配、txHash fenced 写回。
 * - receipt/finality/resubmit 在后续里程碑完善。
 */
@Service
public class TransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriter.class);

    private final TxMgrProperties props;
    private final LeaseManager leaseManager;
    private final ManagedTxMapper txMapper;
    private final SubmitterNonceCursorMapper cursorMapper;
    private final ChainConnector chain;
    private final TxMgrMetrics metrics;

    private final NonceCache nonceCache;
    private final MaxNonceAllocator nonceAllocator;

    private final List<BlockingQueue<CreateTxOp>> queues = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();

    @Autowired
    @Lazy
    private TransactionWriter self;

    public TransactionWriter(TxMgrProperties props,
                             LeaseManager leaseManager,
                             ManagedTxMapper txMapper,
                             SubmitterNonceCursorMapper cursorMapper,
                             ChainConnector chain,
                             TxMgrMetrics metrics) {
        this.props = props;
        this.leaseManager = leaseManager;
        this.txMapper = txMapper;
        this.cursorMapper = cursorMapper;
        this.chain = chain;
        this.metrics = metrics;
        this.nonceCache = new NonceCache(10_000);
        this.nonceAllocator = new MaxNonceAllocator(chain, cursorMapper, props, nonceCache);
    }

    @PostConstruct
    public void start() {
        int n = Math.max(1, props.getWriterWorkers());
        for (int i = 0; i < n; i++) {
            final int workerIdx = i;
            BlockingQueue<CreateTxOp> q = new LinkedBlockingQueue<>();
            queues.add(q);
            Thread t = new Thread(() -> runWorkerLoop(workerIdx, q), "tx-writer-" + workerIdx);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        log.info("TransactionWriter started with workers={}", n);
    }

    public CompletableFuture<ManagedTxEntity> submitCreate(String submitter, String requestId, String payload) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(payload, "payload");
        CreateTxOp op = new CreateTxOp(submitter, normalizeOptional(requestId), payload);
        int idx = workerIndex(submitter);
        queues.get(idx).offer(op);
        // queue depth 作为可观测信号（实现侧可忽略）
        try {
            metrics.writerQueueDepth("writer", queues.get(idx).size());
        } catch (Exception ignore) {
        }
        return op.future;
    }

    private int workerIndex(String submitter) {
        int n = queues.size();
        return (submitter.hashCode() & 0x7fffffff) % n;
    }

    private void runWorkerLoop(int workerIndex, BlockingQueue<CreateTxOp> q) {
        int batchSize = Math.max(1, props.getWriterBatchSize());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CreateTxOp first = q.take();
                List<CreateTxOp> batch = new ArrayList<>(batchSize);
                batch.add(first);
                q.drainTo(batch, batchSize - 1);
                processBatch(batch);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("writer loop error", e);
            }
        }
    }

    private void processBatch(List<CreateTxOp> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            self.processBatchTx(batch);
        } catch (Exception e) {
            // batch 失败：必须清理该批涉及的 submitter 的 nonce cache（对齐 111最终方案.md）
            Set<String> submitters = new HashSet<>();
            for (CreateTxOp op : batch) {
                submitters.add(op.submitter);
            }
            for (String s : submitters) {
                nonceAllocator.clearCache(s);
            }
            for (CreateTxOp op : batch) {
                op.future.completeExceptionally(e);
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processBatchTx(List<CreateTxOp> batch) {
        // group by submitter to acquire lease once per submitter
        Map<String, List<CreateTxOp>> bySubmitter = new LinkedHashMap<>();
        for (CreateTxOp op : batch) {
            bySubmitter.computeIfAbsent(op.submitter, k -> new ArrayList<>()).add(op);
        }

        Instant now = Instant.now();

        for (Map.Entry<String, List<CreateTxOp>> entry : bySubmitter.entrySet()) {
            String submitter = entry.getKey();
            List<CreateTxOp> ops = entry.getValue();

            LeaseDecision lease = leaseManager.acquireOrRenew(submitter);
            if (!lease.isLeader()) {
                NotLeaderException ex = new NotLeaderException("not leader for submitter=" + submitter);
                for (CreateTxOp op : ops) {
                    op.future.completeExceptionally(ex);
                }
                continue;
            }
            long token = lease.getFencingToken();
            String nodeId = leaseManager.getNodeId();

            // 事务内二次幂等校验：冲突请求不得消耗 nonce
            for (CreateTxOp op : ops) {
                if (op.requestId != null) {
                    ManagedTxEntity existing = txMapper.selectBySubmitterAndRequestId(op.submitter, op.requestId);
                    if (existing != null) {
                        op.sentConflict = true;
                        op.existing = existing;
                    }
                }
            }

            // 初始化 cursor（leader-only 场景）
            cursorMapper.insertIfNotExists(submitter, 0L, token, now);

            // 为非冲突 op 分配 nonce 并插入 managed_tx
            for (CreateTxOp op : ops) {
                if (op.sentConflict) {
                    // 冲突：不分配 nonce，不写 cursor
                    op.future.complete(op.existing);
                    continue;
                }
                UUID txId = UUID.randomUUID();
                long nonce = nonceAllocator.nextNonce(submitter, now);
                txMapper.insertManagedTx(txId, submitter, op.requestId, nonce, op.payload, TxState.ALLOCATED.name(), token, now, now);
                ManagedTxEntity created = txMapper.selectByTxId(txId);
                op.created = created;
                op.future.complete(created);
            }

            // batch 内对该 submitter 的游标推进：用 cache 中的 nextNonce 作为“下一个可分配”
            NonceCache.Entry cacheEntry = nonceCache.get(submitter);
            if (cacheEntry != null) {
                int updated = cursorMapper.updateNextNonceFenced(submitter, cacheEntry.nextNonce, nodeId, token, now);
                if (updated != 1) {
                    throw new RuntimeException("fenced updating nonce cursor for submitter=" + submitter);
                }
            }
        }
    }

    /**
     * 事务提交后异步提交到链，并 fenced 写回 txHash/state=TRACKING。
     *
     * 当前作为“里程碑1”最小实现：仅示例性提供方法，不自动触发重试/receipt/finality。
     */
    public void submitToChainAsync(ManagedTxEntity tx) {
        if (tx == null || tx.getTxId() == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String txHash = chain.sendTransaction(tx.getSubmitter(), tx.getNonce(), tx.getPayload());
                // fenced 写回：依赖 lease 仍由当前节点持有（若已切换则应更新失败）
                LeaseDecision lease = leaseManager.acquireOrRenew(tx.getSubmitter());
                if (!lease.isLeader()) {
                    return;
                }
                txMapper.updateTxHashFenced(tx.getTxId(), tx.getSubmitter(), txHash, TxState.TRACKING.name(),
                        Instant.now().plus(props.getResubmitInterval()),
                        leaseManager.getNodeId(), lease.getFencingToken(), Instant.now());
            } catch (Exception e) {
                // 灰区补齐：当 sendTransaction 失败但交易可能已被节点接收时，尝试补齐预期 txHash
                try {
                    String expected = chain.deriveExpectedTxHash(tx.getSubmitter(), tx.getNonce(), tx.getPayload());
                    if (expected != null && !expected.trim().isEmpty()) {
                        LeaseDecision lease = leaseManager.acquireOrRenew(tx.getSubmitter());
                        if (lease.isLeader()) {
                            Instant now = Instant.now();
                            txMapper.updateTxHashFenced(tx.getTxId(), tx.getSubmitter(), expected, TxState.TRACKING.name(),
                                    now.plus(props.getResubmitInterval()),
                                    leaseManager.getNodeId(), lease.getFencingToken(), now);
                            return;
                        }
                    }
                } catch (Exception ignore) {
                    // best-effort
                }
                log.warn("submitToChainAsync failed txId={}", tx.getTxId(), e);
            }
        });
    }

    private String normalizeOptional(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static class CreateTxOp {
        final String submitter;
        final String requestId;
        final String payload;

        boolean sentConflict;
        ManagedTxEntity existing;
        ManagedTxEntity created;
        final CompletableFuture<ManagedTxEntity> future = new CompletableFuture<>();

        CreateTxOp(String submitter, String requestId, String payload) {
            this.submitter = submitter;
            this.requestId = requestId;
            this.payload = payload;
        }
    }
}


