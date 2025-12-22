package com.work.nonce.demo.chain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;

/**
 * 内存版链客户端，仅用于 demo，真实项目请替换为业务自己的实现。
 */
public class MockChainClient implements ChainClient {

    private final Map<String, Long> latestNonce = new ConcurrentHashMap<>();
    private final Map<String, PendingTx> pendingTxs = new ConcurrentHashMap<>();
    private final AtomicLong blockNumber = new AtomicLong(1);

    // demo：发送后延迟一小段时间才“出现回执”
    private final Duration receiptDelay = Duration.ofSeconds(2);

    private static class PendingTx {
        final Instant sentAt;

        PendingTx(Instant sentAt) {
            this.sentAt = sentAt;
        }
    }

    @Override
    public String sendTransaction(String submitter, long nonce, String payload) {
        // 避免 Map.merge 的装箱/拆箱告警，直接按 getOrDefault 更新
        long prev = latestNonce.getOrDefault(submitter, -1L);
        latestNonce.put(submitter, Math.max(prev, nonce));
        String txHash = "tx_" + submitter + "_" + nonce;
        pendingTxs.put(txHash, new PendingTx(Instant.now()));
        return txHash;
    }

    @Override
    public Optional<TransactionReceipt> getTransactionReceipt(String txHash) {
        PendingTx pending = pendingTxs.get(txHash);
        if (pending == null) {
            // 如果我们没有记录，按“尚未出回执”处理
            return Optional.empty();
        }
        if (Instant.now().isBefore(pending.sentAt.plus(receiptDelay))) {
            return Optional.empty();
        }
        // 生成一个稳定的 demo receipt，并移除 pending
        long bn = blockNumber.getAndIncrement();
        TransactionReceipt receipt = new TransactionReceipt(txHash, bn, "block_" + bn, true);
        pendingTxs.remove(txHash);
        return Optional.of(receipt);
    }

    @Override
    public long queryLatestNonce(String submitter) {
        return latestNonce.getOrDefault(submitter, -1L);
    }
}

