package com.work.nonce.core.config;

/**
 * Nonce 分配策略（用于适配不同部署/流量模型）。
 *
 * <p>注意：该策略决定“是否值得做本地缓存/区间预分配”。安全性始终依赖 DB 约束与原子操作
 * （例如 UNIQUE(submitter, nonce) + reserveNonce 的原子语义），而不是依赖策略本身。</p>
 */
public enum AllocationStrategy {
    /**
     * 适用于：同一 submitter 的请求大概率会落在同一节点（例如一致性哈希、粘性路由、单节点）。
     *
     * <p>特性：启用本地缓存 + 区间预分配（preAllocateSize），以减少 state 行锁竞争。</p>
     */
    CACHE_RANGE,

    /**
     * 适用于：同一 submitter 的请求会被轮询打到不同节点（无粘性路由）。
     *
     * <p>特性：禁用本地缓存/区间缓存收益，强制 batchSize=1，仅依赖 DB 原子分配与唯一约束兜底，
     * 避免跨节点 thrash（多节点反复争抢缓存/区间）带来的净收益为负。</p>
     */
    DB_ONLY
}

