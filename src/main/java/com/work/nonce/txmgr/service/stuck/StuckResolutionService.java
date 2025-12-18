package com.work.nonce.txmgr.service.stuck;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.domain.TxState;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.TxCompletionMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 给业务“开放方法”的落地点：不通过 HTTP，而是提供可注入的 Java Service。
 *
 * 业务可以：
 * - 注入并实现 {@link StuckResolutionHook}，自定义决策
 * - 直接调用本 Service 的方法触发一次处理（例如在业务的告警/SOP/后台任务中调用）
 */
@Service
public class StuckResolutionService {

    private static final Logger log = LoggerFactory.getLogger(StuckResolutionService.class);

    private final TxMgrProperties props;
    private final LeaseManager leaseManager;
    private final ManagedTxMapper txMapper;
    private final TxCompletionMapper completionMapper;
    private final ChainConnector chain;
    private final StuckResolutionHook hook;
    private final TxMgrMetrics metrics;

    public StuckResolutionService(TxMgrProperties props,
                                  LeaseManager leaseManager,
                                  ManagedTxMapper txMapper,
                                  TxCompletionMapper completionMapper,
                                  ChainConnector chain,
                                  StuckResolutionHook hook,
                                  TxMgrMetrics metrics) {
        this.props = props;
        this.leaseManager = leaseManager;
        this.txMapper = txMapper;
        this.completionMapper = completionMapper;
        this.chain = chain;
        this.hook = hook;
        this.metrics = metrics;
    }

    /**
     * 业务可直接调用：按 txId 触发一次 hook 决策与处置。
     * 返回 null 表示 tx 不存在或无权处理（非 leader / fenced 未命中）。
     */
    public StuckResolutionDecision handleByTxId(UUID txId, int maxAttempts) {
        if (txId == null) return null;
        ManagedTxEntity tx = txMapper.selectByTxId(txId);
        if (tx == null) return null;
        return handle(tx, maxAttempts);
    }

    /**
     * 系统/业务通用：对某条 tx 执行一次 stuck 决策与处置。
     */
    public StuckResolutionDecision handle(ManagedTxEntity tx, int maxAttempts) {
        if (tx == null || tx.getTxId() == null || tx.getSubmitter() == null) {
            return null;
        }
        LeaseDecision lease = leaseManager.acquireOrRenew(tx.getSubmitter());
        if (!lease.isLeader()) {
            return null;
        }

        Instant now = Instant.now();
        int attempts = tx.getSubmitAttempts() == null ? 0 : tx.getSubmitAttempts();
        int maxA = Math.max(1, maxAttempts);

        long ageMillis = computeAgeMillis(now, tx);
        StuckResolutionContext ctx = new StuckResolutionContext(
                now,
                tx.getSubmitter(),
                tx.getTxId(),
                tx.getNonce(),
                tx.getTxHash(),
                tx.getState(),
                tx.getLastError(),
                attempts,
                maxA,
                ageMillis,
                -1
        );

        StuckResolutionDecision decision;
        try {
            decision = hook == null ? null : hook.onStuckCandidate(tx, ctx);
        } catch (Exception e) {
            log.warn("stuck hook threw txId={} submitter={} err={}", tx.getTxId(), tx.getSubmitter(), e.toString());
            decision = null;
        }
        if (decision == null || decision.getAction() == null) {
            decision = StuckResolutionHook.defaultHook().onStuckCandidate(tx, ctx);
        }
        metrics.stuckDecision(decision.getAction().name());

        applyDecision(lease, now, tx, decision);
        return decision;
    }

    private void applyDecision(LeaseDecision lease, Instant now, ManagedTxEntity tx, StuckResolutionDecision decision) {
        String nodeId = leaseManager.getNodeId();
        long token = lease.getFencingToken();

        switch (decision.getAction()) {
            case RESUBMIT_NOW: {
                // 只做“允许立刻重提”的 claim；真正的 send 由 ResubmitScheduler 负责
                txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), now, decision.getNote(), nodeId, token, now);
                return;
            }
            case DELAY: {
                Duration d = decision.getDelay();
                long base = props.getResubmitInterval() == null ? 30_000L : props.getResubmitInterval().toMillis();
                long ms = d == null ? base : Math.max(1000L, d.toMillis());
                txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), now.plusMillis(ms), decision.getNote(), nodeId, token, now);
                return;
            }
            case IGNORE: {
                long base = props.getResubmitInterval() == null ? 30_000L : props.getResubmitInterval().toMillis();
                txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), now.plusMillis(Math.max(1000L, base)), decision.getNote(), nodeId, token, now);
                return;
            }
            case CANCEL:
            case REPLACE:
            case PLACEHOLDER: {
                // “执行能力”开放给业务：业务通过 decision.remediationPayload 提供要发送的 payload
                String p = decision.getRemediationPayload();
                if (p == null || p.trim().isEmpty() || tx.getNonce() == null) {
                    int updated = txMapper.markStuckFenced(tx.getTxId(), tx.getSubmitter(), "INVALID_REMEDIATION_PAYLOAD", decision.getNote(), nodeId, token, now);
                    if (updated == 1) completionMapper.insertCompletion(tx.getTxId(), now, TxState.STUCK.name());
                    return;
                }

                // 先 claim 再发 RPC：避免多节点重复执行补救动作
                Instant next = now.plus(props.getResubmitInterval() == null ? Duration.ofSeconds(30) : props.getResubmitInterval());
                int claimed = txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), next, decision.getNote(), nodeId, token, now);
                if (claimed != 1) {
                    return;
                }

                try {
                    String txHash = chain.sendTransaction(tx.getSubmitter(), tx.getNonce(), p);
                    txMapper.updateTxHashFenced(tx.getTxId(), tx.getSubmitter(), txHash, TxState.TRACKING.name(),
                            next, nodeId, token, now);
                } catch (Exception e) {
                    String err = "remediation send error: " + e;
                    Instant retryAt = now.plus(props.getResubmitInterval() == null ? Duration.ofSeconds(60) : props.getResubmitInterval().multipliedBy(2));
                    txMapper.updateNextResubmitAtFenced(tx.getTxId(), tx.getSubmitter(), retryAt, err, nodeId, token, now);
                }
                return;
            }
            case MARK_STUCK:
            default: {
                int updated = txMapper.markStuckFenced(tx.getTxId(), tx.getSubmitter(), decision.getSubState(), decision.getNote(), nodeId, token, now);
                if (updated == 1) completionMapper.insertCompletion(tx.getTxId(), now, TxState.STUCK.name());
            }
        }
    }

    private long computeAgeMillis(Instant now, ManagedTxEntity tx) {
        Instant base = tx.getLastSubmitAt();
        if (base == null) base = tx.getCreatedAt();
        if (base == null) return -1;
        long ms = now.toEpochMilli() - base.toEpochMilli();
        return Math.max(0, ms);
    }
}


