package com.work.nonce.core.model;

/**
 * nonce 生命周期的三种状态，对应 README 中的 RESERVED / USED / RECYCLABLE。
 */
public enum NonceAllocationStatus {
    /**
     * 已分配给业务但尚未确认链上成功，调用方需要尽快执行业务操作。
     */
    RESERVED,
    /**
     * 链上视角认为该 nonce 已被接受/占用（不可再复用）。
     *
     * <p>注意：它不一定意味着已 FINALIZED/SAFE，只表达“该 nonce 不应再被回收复用”。</p>
     */
    ACCEPTED,
    /**
     * 链上视角“疑似已占用”（例如 pendingNonce 已推进，或业务异常导致不确定是否已提交）。
     * 该状态下禁止回收复用，由后续对账任务最终判定为 ACCEPTED 或 RECYCLABLE。
     */
    PENDING,
    /**
     * 可以重新分配的 gap nonce（失败、放弃、超时都会转成此状态）。
     */
    RECYCLABLE
}

