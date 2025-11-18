package com.work.nonce.demo.chain;

/**
 * Demo 层的链上客户端抽象，组件本身不依赖该接口，纯粹由业务侧决定如何发送交易。
 */
public interface ChainClient {

    /**
     * 发送交易到链上并返回 txHash。
     */
    String sendTransaction(String submitter, long nonce, String payload);

    /**
     * 查询链上最新 nonce（可选，用于监控或恢复示例）。
     */
    default long queryLatestNonce(String submitter) {
        return -1L;
    }
}

