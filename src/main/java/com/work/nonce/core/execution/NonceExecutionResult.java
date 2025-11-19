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

    private final Status status;
    private final String txHash;
    private final String reason;
    private final T payload;

    private NonceExecutionResult(Status status, String txHash, String reason, T payload) {
        this.status = status;
        this.txHash = txHash;
        this.reason = reason;
        this.payload = payload;
    }

    public static <T> NonceExecutionResult<T> success(String txHash, T payload) {
        return new NonceExecutionResult<>(Status.SUCCESS, txHash, null, payload);
    }

    public static NonceExecutionResult<Void> success(String txHash) {
        return success(txHash, null);
    }

    public static <T> NonceExecutionResult<T> fail(String reason, T payload) {
        return new NonceExecutionResult<>(Status.FAIL, null, reason, payload);
    }

    public static NonceExecutionResult<Void> fail(String reason) {
        return fail(reason, null);
    }

    public Status getStatus() {
        return status;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getReason() {
        return reason;
    }

    public T getPayload() {
        return payload;
    }
}

