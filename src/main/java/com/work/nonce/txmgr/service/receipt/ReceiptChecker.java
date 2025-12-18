package com.work.nonce.txmgr.service.receipt;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.chain.TxReceipt;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.service.finality.TxFinalityService;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 111最终方案.md：ReceiptChecker（NotFound 不阻塞；错误退避；避免队头阻塞）。
 *
 * 里程碑2：实现最小闭环：
 * - 定时扫描 TRACKING 且 receipt 为空的 tx（可替换为更精细调度）
 * - worker 取任务查 receipt
 * - 找到 receipt -> TxFinalityService 仅持久化 receipt（终局由 FinalityManager 推进）
 */
@Service
public class ReceiptChecker {

    private static final Logger log = LoggerFactory.getLogger(ReceiptChecker.class);

    private final TxMgrProperties props;
    private final ManagedTxMapper txMapper;
    private final ChainConnector chain;
    private final TxFinalityService finality;
    private final LeaseManager leaseManager;
    private final TxMgrMetrics metrics;

    private ExecutorService workers;
    private final DelayQueue<ReceiptTask> queue = new DelayQueue<>();
    private final Map<String, Integer> errorAttempts = new ConcurrentHashMap<>();

    public ReceiptChecker(TxMgrProperties props, ManagedTxMapper txMapper, ChainConnector chain, TxFinalityService finality, LeaseManager leaseManager, TxMgrMetrics metrics) {
        this.props = props;
        this.txMapper = txMapper;
        this.chain = chain;
        this.finality = finality;
        this.leaseManager = leaseManager;
        this.metrics = metrics;
    }

    @PostConstruct
    public void start() {
        int n = Math.max(1, props.getReceiptWorkers());
        this.workers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "receipt-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < n; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("ReceiptChecker started workers={}", n);
    }

    /**
     * 简单扫描：每 5 秒拉一批待查 receipt 的交易（后续可改为更精细队列/索引）。
     */
    @Scheduled(fixedDelayString = "${txmgr.receipt-scan-interval.toMillis()}")
    public void schedule() {
        List<ManagedTxEntity> list = txMapper.listTrackingWithoutReceipt(200);
        for (ManagedTxEntity tx : list) {
            if (tx.getTxHash() == null || tx.getTxHash().trim().isEmpty()) {
                continue;
            }
            queue.offer(new ReceiptTask(tx.getTxId(), tx.getSubmitter(), tx.getTxHash(), System.currentTimeMillis()));
        }
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ReceiptTask task = queue.take();
                handle(task);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("receipt worker loop error", e);
            }
        }
    }

    private void handle(ReceiptTask task) {
        String key = task.txHash;
        try {
            TxReceipt r = chain.getTransactionReceipt(task.txHash);
            if (r == null) {
                // NotFound：不算错，按 staleReceiptTimeout 延迟重试（不阻塞其他任务）
                // 记录本次检查时间（便于调度与排障）：leader-only + fenced
                Instant now = Instant.now();
                LeaseDecision lease = leaseManager.acquireOrRenew(task.submitter);
                if (lease.isLeader()) {
                    txMapper.updateLastReceiptCheckAtFenced(task.txId, task.submitter, now,
                            leaseManager.getNodeId(), lease.getFencingToken(), now);
                }
                metrics.receiptCheck("not_found");
                requeue(task, props.getStaleReceiptTimeout());
                return;
            }
            errorAttempts.remove(key);
            metrics.receiptCheck("found");
            finality.handleReceipt(task.txId, task.submitter, task.txHash, r);
        } catch (Exception e) {
            // 非 NotFound 错误：退避并移到队尾
            int attempt = errorAttempts.merge(key, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue())).intValue();
            Duration backoff = backoff(attempt);
            log.warn("getReceipt error txId={} attempt={} backoff={} err={}", task.txId, attempt, backoff, e.toString());
            metrics.receiptCheck("error");
            requeue(task, backoff);
        }
    }

    // package-private for unit tests (avoid spinning worker threads)
    void handleTaskForTest(ReceiptTask task) {
        handle(task);
    }

    private void requeue(ReceiptTask task, Duration delay) {
        long d = Math.max(1000L, delay == null ? 1000L : delay.toMillis());
        queue.offer(new ReceiptTask(task.txId, task.submitter, task.txHash, System.currentTimeMillis() + d));
    }

    private Duration backoff(int attempt) {
        long base = 1000L;
        long max = 30_000L;
        long pow = 1L << Math.min(10, Math.max(0, attempt - 1));
        long ms = Math.min(max, base * pow);
        return Duration.ofMillis(ms);
    }

    static class ReceiptTask implements Delayed {
        final java.util.UUID txId;
        final String submitter;
        final String txHash;
        final long executeAtMs;

        ReceiptTask(java.util.UUID txId, String submitter, String txHash, long executeAtMs) {
            this.txId = txId;
            this.submitter = submitter;
            this.txHash = txHash;
            this.executeAtMs = executeAtMs;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long diff = executeAtMs - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            if (o == this) return 0;
            long d = getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS);
            return d == 0 ? 0 : (d < 0 ? -1 : 1);
        }
    }
}


