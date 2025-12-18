package com.work.nonce.txmgr.service.resubmit;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.domain.TxState;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.service.stuck.StuckResolutionAction;
import com.work.nonce.txmgr.service.stuck.StuckResolutionDecision;
import com.work.nonce.txmgr.service.stuck.StuckResolutionService;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 里程碑3：ResubmitScheduler（重提调度）+ StuckResolutionHook 接入点。
 *
 * 策略（最小可运行）：
 * - 仅处理：state=TRACKING && receipt IS NULL && next_resubmit_at <= now()
 * - submitter 维度 acquire/renew lease，所有写入都 fenced
 * - 超过最大 attempts 的交易交给 hook 决策，默认 MARK_STUCK
 */
@Service
public class ResubmitScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResubmitScheduler.class);

    private final TxMgrProperties props;
    private final ManagedTxMapper txMapper;
    private final LeaseManager leaseManager;
    private final ChainConnector chain;
    private final StuckResolutionService stuckService;
    private final TxMgrMetrics metrics;

    public ResubmitScheduler(TxMgrProperties props,
                             ManagedTxMapper txMapper,
                             LeaseManager leaseManager,
                             ChainConnector chain,
                             StuckResolutionService stuckService,
                             TxMgrMetrics metrics) {
        this.props = props;
        this.txMapper = txMapper;
        this.leaseManager = leaseManager;
        this.chain = chain;
        this.stuckService = stuckService;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${txmgr.resubmit-scan-interval.toMillis()}")
    public void scanAndResubmit() {
        List<ManagedTxEntity> due = txMapper.listDueResubmits(200);
        if (due == null) {
            return;
        }

        // group by submitter to reduce lease operations
        Map<String, List<ManagedTxEntity>> bySubmitter = new LinkedHashMap<>();
        for (ManagedTxEntity tx : due) {
            if (tx.getSubmitter() == null) {
                continue;
            }
            bySubmitter.computeIfAbsent(tx.getSubmitter(), k -> new ArrayList<>()).add(tx);
        }

        // 若当前没有 due，也需要按 backlog 治理检查“最老 pending”
        if (bySubmitter.isEmpty()) {
            try {
                List<String> submitters = txMapper.listSubmittersWithTrackingPending(200);
                if (submitters != null) {
                    for (String s : submitters) {
                        if (s == null) continue;
                        bySubmitter.put(s, java.util.Collections.emptyList());
                    }
                }
            } catch (Exception ignore) {
            }
        }

        if (bySubmitter.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (Map.Entry<String, List<ManagedTxEntity>> entry : bySubmitter.entrySet()) {
            String submitter = entry.getKey();
            List<ManagedTxEntity> list = entry.getValue();

            LeaseDecision lease = leaseManager.acquireOrRenew(submitter);
            if (!lease.isLeader()) {
                continue;
            }

            String nodeId = leaseManager.getNodeId();
            long token = lease.getFencingToken();

            // pending 堆积治理：当 pending 数量或最老 age 超阈值时，优先处理最老的那条
            try {
                Long cnt = txMapper.countTrackingPending(submitter);
                long pending = cnt == null ? 0L : cnt.longValue();
                boolean overCount = pending >= Math.max(1, props.getPendingBacklogThreshold());
                ManagedTxEntity oldest = txMapper.selectOldestTrackingPending(submitter);
                boolean overAge = isOverOldestAge(now, oldest);
                if (oldest != null && (overCount || overAge)) {
                    handleOne(now, nodeId, token, oldest);
                }
            } catch (Exception e) {
                // best-effort
            }

            if (list != null) {
                for (ManagedTxEntity tx : list) {
                    handleOne(now, nodeId, token, tx);
                }
            }
        }
    }

    private boolean isOverOldestAge(Instant now, ManagedTxEntity oldest) {
        if (oldest == null || now == null || props.getPendingOldestAgeThreshold() == null) {
            return false;
        }
        Instant base = oldest.getLastSubmitAt();
        if (base == null) base = oldest.getCreatedAt();
        if (base == null) return false;
        long ageMs = now.toEpochMilli() - base.toEpochMilli();
        return ageMs >= Math.max(0L, props.getPendingOldestAgeThreshold().toMillis());
    }

    private void handleOne(Instant now, String nodeId, long token, ManagedTxEntity tx) {
        if (tx == null || tx.getTxId() == null || tx.getSubmitter() == null) {
            return;
        }
        if (tx.getNonce() == null || tx.getPayload() == null) {
            txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(),
                    now.plus(props.getResubmitInterval()),
                    "missing nonce/payload",
                    nodeId, token, now);
            return;
        }

        int attempts = tx.getSubmitAttempts() == null ? 0 : tx.getSubmitAttempts().intValue();
        int maxAttempts = Math.max(1, props.getResubmitMaxAttempts());
        if (attempts >= maxAttempts) {
            handleStuckCandidateOrOverrideResubmit(now, nodeId, token, tx, attempts, maxAttempts);
            return;
        }

        resubmitOnce(now, nodeId, token, tx, attempts);
    }

    private void handleStuckCandidateOrOverrideResubmit(Instant now, String nodeId, long token, ManagedTxEntity tx, int attempts, int maxAttempts) {
        // 统一交给 StuckResolutionService：对外是“开放方法”，对内避免逻辑散落在 scheduler
        StuckResolutionDecision decision = stuckService.handle(tx, maxAttempts);
        if (decision != null && decision.getAction() == StuckResolutionAction.RESUBMIT_NOW) {
            // service 做了 claim，这里立即尝试执行一次 resubmit（越过 maxAttempts）
            resubmitOnce(now, nodeId, token, tx, attempts);
        }
    }

    /**
     * 关键约束：先 fenced claim（推进 next_resubmit_at 到未来）后再发链上 RPC，避免多节点/多轮扫描造成重复副作用。
     */
    private void resubmitOnce(Instant now, String nodeId, long token, ManagedTxEntity tx, int attempts) {
        // 1) fenced claim：把 next_resubmit_at 推到未来，避免下一轮扫描再次捞到
        Instant next = now.plus(props.getResubmitInterval());
        int claimed = txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), next, null, nodeId, token, now);
        if (claimed != 1) {
            return;
        }

        // 2) 发 RPC（同 nonce、同 payload；不做替换/取消语义）
        try {
            String txHash = chain.sendTransaction(tx.getSubmitter(), tx.getNonce().longValue(), tx.getPayload());
            txMapper.updateTxHashFenced(tx.getTxId(), tx.getSubmitter(), txHash, TxState.TRACKING.name(),
                    next, nodeId, token, now);
            metrics.resubmit("success");
        } catch (Exception e) {
            Duration backoff = backoff(attempts + 1);
            Instant retryAt = now.plus(backoff);
            String err = "resubmit error: " + e;
            txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), retryAt, err, nodeId, token, now);
            log.warn("resubmit failed txId={} submitter={} attempts={} next={} err={}",
                    tx.getTxId(), tx.getSubmitter(), attempts, retryAt, e.toString());
            metrics.resubmit("error");
        }
    }

    private Duration backoff(int attempt) {
        long base = 2000L;
        long max = Math.max(base, props.getResubmitInterval().toMillis());
        long pow = 1L << Math.min(10, Math.max(0, attempt - 1));
        long ms = Math.min(max, base * pow);
        return Duration.ofMillis(ms);
    }
}


