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
         * 可重试错误，保持 RESERVED，交由上层重试。
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
    /**
     * nonce 是否已被链上“消耗”（或视为已消耗）。
     * - true: 该 nonce 不能再被回收复用（应标记 USED/CONSUMED）
     * - false: 该 nonce 可根据失败类型被回收或保持 RESERVED
     */
    private final boolean nonceConsumed;

    private NonceExecutionResult(Outcome outcome, String txHash, String reason, boolean nonceConsumed) {
        this.outcome = outcome;
        this.txHash = txHash;
        this.reason = reason;
        this.nonceConsumed = nonceConsumed;
    }

    public static NonceExecutionResult success(String txHash) {
        return new NonceExecutionResult(Outcome.SUCCESS, txHash, null, true);
    }

    public static NonceExecutionResult retryableFailure(String reason) {
        return new NonceExecutionResult(Outcome.RETRYABLE_FAILURE, null, reason, false);
    }

    public static NonceExecutionResult nonRetryableFailure(String reason) {
        return new NonceExecutionResult(Outcome.NON_RETRYABLE_FAILURE, null, reason, false);
    }

    /**
     * 失败但 nonce 已消耗（例如交易已上链但执行失败、或链上提示 nonce 已被外部消耗）。
     * txHash 允许为空（比如 nonce too low 且未获取到 txHash）。
     */
    public static NonceExecutionResult consumedFailure(String txHash, String reason) {
        return new NonceExecutionResult(Outcome.NON_RETRYABLE_FAILURE, txHash, reason, true);
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

    public boolean isNonceConsumed() {
        return nonceConsumed;
    }
}

