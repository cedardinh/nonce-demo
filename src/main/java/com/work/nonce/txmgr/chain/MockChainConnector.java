package com.work.nonce.txmgr.chain;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo 级链连接器：仅用于跑通最小链路。
 */
@Component
public class MockChainConnector implements ChainConnector {

    private final Map<String, Long> pendingNonce = new ConcurrentHashMap<>();
    private final Map<String, TxReceipt> receipts = new ConcurrentHashMap<>();
    private final AtomicLong blockNumber = new AtomicLong(1);

    @Override
    public long getPendingNonce(String submitter) {
        return pendingNonce.getOrDefault(submitter, 0L);
    }

    @Override
    public String sendTransaction(String submitter, long nonce, String payload) {
        // 模拟：链的 pending nonce 前进到 nonce+1
        pendingNonce.merge(submitter, Long.valueOf(nonce + 1), (a, b) -> Long.valueOf(Math.max(a.longValue(), b.longValue())));
        long bn = blockNumber.getAndIncrement();
        // 注意：同 nonce 的重提在真实链上会产生不同 txHash，这里用 blockNumber 作为简化的“唯一后缀”
        String txHash = "tx_" + submitter + "_" + nonce + "_" + bn;
        receipts.put(txHash, new TxReceipt(bn, "block_" + bn, true));
        return txHash;
    }

    @Override
    public TxReceipt getTransactionReceipt(String txHash) {
        return receipts.get(txHash);
    }

    @Override
    public long getLatestBlockNumber() {
        // blockNumber 是下一次将要分配的高度，所以最新高度=当前值-1
        return Math.max(0L, blockNumber.get() - 1L);
    }

    @Override
    public String getBlockHashByNumber(long blockNumber) {
        if (blockNumber <= 0) return null;
        return "block_" + blockNumber;
    }
}


