package com.work.nonce.core.model;

/**
 * nonce 分配记录（allocation）的生命周期状态。
 *
 * 注意：这是“本地资源状态机”，只回答“该 nonce 是否还能被复用”。
 * - HELD：被占用（绑定到一个活跃尝试/交易），不允许复用
 * - RELEASED：已释放回池，允许复用
 * - CONSUMED：已终局消费，永不复用
 *
 * 兼容性：
 * - 数据库若残留旧值 RESERVED/USED/RECYCLABLE，需在读取时映射到新枚举（由 Repository/Mapper 负责）。
 */
public enum NonceAllocationStatus {
    /**
     * 被占用：nonce 已绑定到一个活跃尝试/交易。
     */
    HELD,
    /**
     * 已终局消费：该 nonce 永不复用。
     */
    CONSUMED,
    /**
     * 已释放回池：允许复用（失败、放弃、超时回收等）。
     */
    RELEASED
}

