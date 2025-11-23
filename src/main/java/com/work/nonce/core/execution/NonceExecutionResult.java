package com.work.nonce.core.execution;

/**
 * handler 执行结果，用于指导模板如何更新 allocation 状态。
 */
public class NonceExecutionResult<T> {

    public enum Status {
        /**
         * 业务和链上调用都成功。
         */
        SUCCESS,
        /**
         * 业务失败，不再重试，nonce 将被回收。
         */
        FAIL
    }

    /** 当前执行结果状态。 */
    private final Status status;
    /** 成功场景下的链上 txHash。 */
    private final String txHash;
    /** 失败场景下的原因描述。 */
    private final String reason;
    /** 业务自定义的返回载荷。 */
    private final T payload;

    private NonceExecutionResult(Status status, String txHash, String reason, T payload) {
        this.status = status;
        this.txHash = txHash;
        this.reason = reason;
        this.payload = payload;
    }

    /**
     * 构造 SUCCESS 结果并携带业务自定义载荷。
     *
     * @param txHash  链上交易哈希
     * @param payload 业务返回值
     */
    public static <T> NonceExecutionResult<T> success(String txHash, T payload) {
        return new NonceExecutionResult<>(Status.SUCCESS, txHash, null, payload);
    }

    /** 构造不带 payload 的 SUCCESS 结果。 */
    public static NonceExecutionResult<Void> success(String txHash) {
        return success(txHash, null);
    }

    /**
     * 构造 FAIL 结果并携带 payload。
     *
     * @param reason 失败原因
     * @param payload 业务返回值
     */
    public static <T> NonceExecutionResult<T> fail(String reason, T payload) {
        return new NonceExecutionResult<>(Status.FAIL, null, reason, payload);
    }

    /** 构造不带 payload 的 FAIL 结果。 */
    public static NonceExecutionResult<Void> fail(String reason) {
        return fail(reason, null);
    }

    /** @return 当前状态 */
    public Status getStatus() {
        return status;
    }

    /** @return SUCCESS 时的链上哈希 */
    public String getTxHash() {
        return txHash;
    }

    /** @return FAIL 时的原因 */
    public String getReason() {
        return reason;
    }

    /** @return 业务自定义载荷 */
    public T getPayload() {
        return payload;
    }
}

