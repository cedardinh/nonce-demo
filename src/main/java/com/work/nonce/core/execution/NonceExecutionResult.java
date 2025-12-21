package com.work.nonce.core.execution;

/**
 * handler 执行结果，用于指导模板如何更新 allocation 状态。
 */
public class NonceExecutionResult {

    public enum Outcome {
        /**
         * 业务和链上调用都成功。
         */
        SUCCESS,
        /**
         * 可重试错误，保持 HELD，交由上层重试。
         */
        RETRYABLE_FAILURE,
        /**
         * 不可重试错误，直接回收 nonce。
         */
        NON_RETRYABLE_FAILURE
    }

    private final Outcome outcome;
    private final String txHash;
    private final String reason;

    private NonceExecutionResult(Outcome outcome, String txHash, String reason) {
        this.outcome = outcome;
        this.txHash = txHash;
        this.reason = reason;
    }

    public static NonceExecutionResult success(String txHash) {
        return new NonceExecutionResult(Outcome.SUCCESS, txHash, null);
    }

    public static NonceExecutionResult retryableFailure(String reason) {
        return new NonceExecutionResult(Outcome.RETRYABLE_FAILURE, null, reason);
    }

    public static NonceExecutionResult nonRetryableFailure(String reason) {
        return new NonceExecutionResult(Outcome.NON_RETRYABLE_FAILURE, null, reason);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getReason() {
        return reason;
    }
}

