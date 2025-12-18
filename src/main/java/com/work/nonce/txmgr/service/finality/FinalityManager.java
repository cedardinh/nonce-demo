package com.work.nonce.txmgr.service.finality;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.chain.TxReceipt;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 里程碑：FinalityManager（内部终局推进，不对外暴露 confirmations）。
 *
 * 最小实现：
 * - 扫描：state=TRACKING 且 receipt 已就绪、confirmed_at 为空的交易
 * - confirmations：当 chain 支持 latestBlockNumber 时，按 confirmations 判定；否则退化为 receipt 即终局
 * - reorg：当 chain 支持 getBlockHashByNumber 时，校验 receipt.blockHash 是否一致；不一致则标记 STUCK（最小可观测处理）
 */
@Service
public class FinalityManager {

    private static final Logger log = LoggerFactory.getLogger(FinalityManager.class);

    private final TxMgrProperties props;
    private final LeaseManager leaseManager;
    private final ManagedTxMapper txMapper;
    private final TxCompletionMapper completionMapper;
    private final ChainConnector chain;
    private final TxMgrMetrics metrics;

    public FinalityManager(TxMgrProperties props,
                           LeaseManager leaseManager,
                           ManagedTxMapper txMapper,
                           TxCompletionMapper completionMapper,
                           ChainConnector chain,
                           TxMgrMetrics metrics) {
        this.props = props;
        this.leaseManager = leaseManager;
        this.txMapper = txMapper;
        this.completionMapper = completionMapper;
        this.chain = chain;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${txmgr.finality-scan-interval.toMillis()}")
    public void scanAndFinalize() {
        List<ManagedTxEntity> list = txMapper.listTrackingWithReceipt(200);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ManagedTxEntity tx : list) {
            try {
                handleOne(tx);
            } catch (Exception e) {
                log.warn("finality handle error txId={} err={}", tx == null ? null : tx.getTxId(), e.toString());
            }
        }
    }

    private void handleOne(ManagedTxEntity tx) {
        if (tx == null || tx.getTxId() == null || tx.getSubmitter() == null || tx.getTxHash() == null) {
            return;
        }

        LeaseDecision lease = leaseManager.acquireOrRenew(tx.getSubmitter());
        if (!lease.isLeader()) {
            return;
        }

        // 以链为准重新取一次 receipt（避免仅依赖 DB 内的 receipt JSON 解析）
        TxReceipt r = chain.getTransactionReceipt(tx.getTxHash());
        if (r == null) {
            return;
        }

        Instant now = Instant.now();
        String nodeId = leaseManager.getNodeId();
        long token = lease.getFencingToken();

        // 1) 最小 reorg 检测（可选能力）
        String canonicalHash = chain.getBlockHashByNumber(r.getBlockNumber());
        if (canonicalHash != null && r.getBlockHash() != null && !canonicalHash.equals(r.getBlockHash())) {
            int updated = txMapper.markStuckFenced(tx.getTxId(), tx.getSubmitter(), "REORG_DETECTED",
                    "receipt blockHash mismatch canonical=" + canonicalHash + " receipt=" + r.getBlockHash(),
                    nodeId, token, now);
            if (updated == 1) {
                completionMapper.insertCompletion(tx.getTxId(), now, TxState.STUCK.name());
                metrics.finality("reorg_stuck");
            }
            return;
        }

        // 2) confirmations 判定（可选能力）
        int required = Math.max(1, props.getFinalityConfirmations());
        long latest = chain.getLatestBlockNumber();
        boolean enough;
        if (latest < 0) {
            enough = true; // 不支持时退化为“receipt 即终局”
        } else {
            long conf = (latest - r.getBlockNumber()) + 1;
            enough = conf >= required;
        }

        if (!enough) {
            metrics.finality("not_enough_confirmations");
            return;
        }

        TxState finalState = r.isSuccess() ? TxState.CONFIRMED : TxState.FAILED_FINAL;
        int updated = txMapper.updateFinalStateFenced(tx.getTxId(), tx.getSubmitter(), finalState.name(), now, nodeId, token, now);
        if (updated == 1) {
            completionMapper.insertCompletion(tx.getTxId(), now, finalState.name());
            metrics.finality(finalState.name().toLowerCase());
        } else {
            metrics.fencedWriteRejected("finality_update");
        }
    }
}


