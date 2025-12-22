package com.work.nonce.demo.chain;

import java.util.Optional;

/**
 * Demo 层的链上客户端抽象，组件本身不依赖该接口，纯粹由业务侧决定如何发送交易。
 */
public interface ChainClient {

    /**
     * 发送交易到链上并返回 txHash。
     */
    String sendTransaction(String submitter, long nonce, String payload);

    /**
     * 查询交易回执（receipt）。如果交易尚未被打包，应返回 empty（类似 FFTM 的 NotFound 语义）。
     */
    Optional<TransactionReceipt> getTransactionReceipt(String txHash);

    /**
     * 查询最新区块号，用于 confirmations 计算。
     * 返回 -1 表示该实现不支持。
     */
    default long queryLatestBlockNumber() {
        return -1L;
    }

    /**
     * 查询链上最新 nonce（可选，用于监控或恢复示例）。
     */
    default long queryLatestNonce(String submitter) {
        return -1L;
    }
}

