package com.work.nonce.demo.web.dto;

import com.work.nonce.core.execution.NonceExecutionResult;

/**
 * 用于 REST 示例的统一响应体，携带本次业务场景及组件执行结果。
 */
public class NonceScenarioResponse {

    /** 场景名称，便于前端或测试脚本区分调用来源。 */
    private final String scenario;
    /** submitter 唯一标识。 */
    private final String submitter;
    /** 实际使用的 nonce，方便观察复用/自增。 */
    private final long nonce;
    /** handler 返回的状态：SUCCESS / FAIL。 */
    private final NonceExecutionResult.Status status;
    /** 链上 txHash，仅 SUCCESS 场景存在。 */
    private final String txHash;
    /** 回显的业务 payload，辅助定位。 */
    private final String payloadEcho;
    /** 额外提示信息，解释当前状态。 */
    private final String message;

    private NonceScenarioResponse(String scenario,
                                  String submitter,
                                  long nonce,
                                  NonceExecutionResult.Status status,
                                  String txHash,
                                  String payloadEcho,
                                  String message) {
        this.scenario = scenario;
        this.submitter = submitter;
        this.nonce = nonce;
        this.status = status;
        this.txHash = txHash;
        this.payloadEcho = payloadEcho;
        this.message = message;
    }

    public static NonceScenarioResponse success(String scenario,
                                                String submitter,
                                                long nonce,
                                                String txHash,
                                                String payloadEcho,
                                                String message) {
        return new NonceScenarioResponse(scenario, submitter, nonce,
                NonceExecutionResult.Status.SUCCESS, txHash, payloadEcho, message);
    }

    public static NonceScenarioResponse fail(String scenario,
                                             String submitter,
                                             long nonce,
                                             String reason,
                                             String message) {
        String finalMsg = message == null ? reason : message + "；原因：" + reason;
        return new NonceScenarioResponse(scenario, submitter, nonce,
                NonceExecutionResult.Status.FAIL, null, null, finalMsg);
    }

    public String getScenario() {
        return scenario;
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getNonce() {
        return nonce;
    }

    public NonceExecutionResult.Status getStatus() {
        return status;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getPayloadEcho() {
        return payloadEcho;
    }

    public String getMessage() {
        return message;
    }
}

