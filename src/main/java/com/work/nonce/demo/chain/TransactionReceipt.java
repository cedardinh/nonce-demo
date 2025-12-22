package com.work.nonce.demo.chain;

/**
 * Demo 侧的 receipt 抽象。
 *
 * 说明：
 * - 在 EVM 语义中，只要 receipt 出现，就意味着该 nonce 已被链消耗（无论 success=true/false）。
 * - 该对象仅用于演示/最小闭环。生产环境应使用链 SDK 的 receipt 结构或自定义更完整的 DTO。
 */
public class TransactionReceipt {

    private final String txHash;
    private final long blockNumber;
    private final String blockHash;
    private final boolean success;

    public TransactionReceipt(String txHash, long blockNumber, String blockHash, boolean success) {
        this.txHash = txHash;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.success = success;
    }

    public String getTxHash() {
        return txHash;
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public boolean isSuccess() {
        return success;
    }
}


