package com.work.nonce.core.engine.spi;

import com.work.nonce.core.model.NonceAllocation;

/**
 * 抽象“领取/标记 nonce” 的三大操作，用于屏蔽可靠模式与性能模式的差异。
 */
public interface NonceAllocationEngine {

    /**
     * 为指定 submitter 分配一个新的 RESERVED nonce。
     *
     * @param submitter 业务唯一标识
     * @return 已被 RESERVE 的 nonce 实例
     */
    NonceAllocation allocate(String submitter);

    /**
     * 将指定 nonce 标记为 USED。
     *
     * @param submitter 业务唯一标识
     * @param nonce     目标 nonce
     * @param txHash    链上交易哈希（用于追溯）
     */
    void markUsed(String submitter, long nonce, String txHash);

    /**
     * 将指定 nonce 标记为 RECYCLABLE，允许后续复用。
     *
     * @param submitter 业务唯一标识
     * @param nonce     目标 nonce
     * @param reason    回收原因，可用于排查
     */
    void markRecyclable(String submitter, long nonce, String reason);
}

