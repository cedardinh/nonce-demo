package com.work.nonce.txmgr.chain;

/**
 * 最小 receipt 表达（111最终方案.md：内部处理终局，不对外暴露 confirmations）。
 */
public class TxReceipt {
    private final long blockNumber;
    private final String blockHash;
    private final boolean success;

    public TxReceipt(long blockNumber, String blockHash, boolean success) {
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.success = success;
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


