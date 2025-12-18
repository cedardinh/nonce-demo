package com.work.nonce.txmgr.service.finality;

import com.work.nonce.txmgr.chain.TxReceipt;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 里程碑2：内部终局推进（不对外暴露 confirmations）。
 *
 * 当前实现（最小可运行）：以 receipt 作为终局依据：
 * - receipt.success=true  -> CONFIRMED
 * - receipt.success=false -> FAILED_FINAL
 *
 * 后续可在不改变对外语义的前提下，引入更多 finality 判定（例如 required confirmations）。
 */
@Service
public class TxFinalityService {

    private final LeaseManager leaseManager;
    private final ManagedTxMapper txMapper;

    public TxFinalityService(LeaseManager leaseManager, ManagedTxMapper txMapper) {
        this.leaseManager = leaseManager;
        this.txMapper = txMapper;
    }

    public void handleReceipt(UUID txId, String submitter, String txHash, TxReceipt receipt) {
        if (txId == null || submitter == null || receipt == null) {
            return;
        }

        LeaseDecision lease = leaseManager.acquireOrRenew(submitter);
        if (!lease.isLeader()) {
            return;
        }

        long token = lease.getFencingToken();
        String nodeId = leaseManager.getNodeId();
        Instant now = Instant.now();

        // 1) fenced 写入 receipt（最小证据）
        String receiptJson = toJson(txHash, receipt);
        txMapper.updateReceiptFenced(txId, submitter, receiptJson, nodeId, token, now);

        // 终局推进交给 FinalityManager（confirmations/reorg 等内部逻辑）
    }

    private String toJson(String txHash, TxReceipt r) {
        // 简单 JSON 拼接（demo）；生产可替换为 Jackson/ObjectMapper
        String h = txHash == null ? "" : txHash;
        return "{\"txHash\":\"" + escape(h) + "\",\"blockNumber\":" + r.getBlockNumber() +
                ",\"blockHash\":\"" + escape(r.getBlockHash()) + "\",\"success\":" + r.isSuccess() + "}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}


