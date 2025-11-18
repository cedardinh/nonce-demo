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
     * 业务流程及链上调用均成功，该 nonce 永久占⽤。
     */
    USED,
    /**
     * 可以重新分配的 gap nonce（失败、放弃、超时都会转成此状态）。
     */
    RECYCLABLE
}

