package com.work.nonce.core.chain;

/**
 * 抽象的链上 nonce 查询客户端，由宿主应用实现。
 */
public interface ChainNonceClient {

    /**
     * 查询链上 nonce。返回值语义：**下一个可用 nonce**（通常等于最高已用 nonce + 1，或节点 pending nonce）。
     *
     * - 返回负数表示链上查询不可用（调用方应回退到 DB 逻辑）
     * - 查询失败可抛出异常（调用方可按策略重试/降级）
     */
    long getLatestNonce(String submitter);
}
