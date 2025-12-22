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
     * nonce 已被链消耗（receipt 已出现），该 nonce 永久占用。
     *
     * 注意：USED 不等价于“业务成功”。在 EVM 中交易可能 revert（receipt.success=false），但 nonce 仍会被消耗。
     */
    USED,
    /**
     * 可以重新分配的 gap nonce（失败、放弃、超时都会转成此状态）。
     */
    RECYCLABLE
}

