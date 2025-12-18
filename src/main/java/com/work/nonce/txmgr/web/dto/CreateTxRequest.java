package com.work.nonce.txmgr.web.dto;

import javax.validation.constraints.NotBlank;

/**
 * 111最终方案.md：创建交易请求（requestId 可选）。
 */
public class CreateTxRequest {

    @NotBlank(message = "submitter 不能为空")
    private String submitter;

    /**
     * 幂等键（可选）。不传则不做请求级幂等。
     */
    private String requestId;

    /**
     * 业务 payload（以 JSON 字符串形式传入）。
     */
    @NotBlank(message = "payload 不能为空")
    private String payload;

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}


