package com.work.nonce.core.chain;

/**
 * 抽象的链上 nonce 查询客户端，由宿主应用实现。
 */
public interface ChainNonceClient {

    /**
     * 查询链上 next nonce（下一个可用 nonce）。
     *
     * <p>返回值语义：**下一个可用 nonce**（通常等于最高已用 nonce + 1，或节点 pending nonce）。</p>
     *
     * <ul>
     *   <li>返回负数表示链上查询不可用（调用方应回退到 DB 逻辑）</li>
     *   <li>查询失败可抛出异常（调用方可按策略重试/降级）</li>
     * </ul>
     *
     * @param tag 链侧视图（例如 PENDING / LATEST / SAFE / FINALIZED）
     */
    long getNextNonce(String submitter, ChainBlockTag tag);

    /**
     * 兼容旧接口：默认按 PENDING 视图查询。
     */
    default long getLatestNonce(String submitter) {
        return getNextNonce(submitter, ChainBlockTag.PENDING);
    }
}
