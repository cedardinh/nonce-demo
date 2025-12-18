package com.work.nonce.txmgr.chain;

/**
 * 111最终方案.md：链交互最小端口。
 *
 * 注意：这里只定义方案内出现的最小能力，不引入额外业务语义。
 */
public interface ChainConnector {

    /**
     * 查询 pending nonce（EVM: eth_getTransactionCount(pending)）。
     */
    long getPendingNonce(String submitter);

    /**
     * 发送交易（demo 里用 submitter+nonce+payload 构造“链上提交”）。
     */
    String sendTransaction(String submitter, long nonce, String payload);

    /**
     * 查询交易 receipt。返回 null 表示 NotFound（尚未就绪）。
     */
    TxReceipt getTransactionReceipt(String txHash);

    /**
     * 可选：查询最新区块高度（用于 confirmations/finality 判定）。
     * 返回 < 0 表示链实现不支持该能力。
     */
    default long getLatestBlockNumber() {
        return -1L;
    }

    /**
     * 可选：按高度查询区块 hash（用于最小 reorg 检测）。
     * 返回 null 表示链实现不支持该能力。
     */
    default String getBlockHashByNumber(long blockNumber) {
        return null;
    }

    /**
     * 可选：灰区补齐能力（仅接口占位，默认返回 null）。
     * 111最终方案.md：用于“txHash 灰区”补齐。
     */
    default String deriveExpectedTxHash(String submitter, long nonce, String payload) {
        return null;
    }
}


