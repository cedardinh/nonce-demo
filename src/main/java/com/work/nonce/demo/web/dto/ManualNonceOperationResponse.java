package com.work.nonce.demo.web.dto;

/**
 * 低阶接口示例：手动标记 USED / RECYCLABLE 时返回的简要结果。
 */
public class ManualNonceOperationResponse {

    private final String operation;
    private final String submitter;
    private final long nonce;
    private final String detail;

    private ManualNonceOperationResponse(String operation,
                                         String submitter,
                                         long nonce,
                                         String detail) {
        this.operation = operation;
        this.submitter = submitter;
        this.nonce = nonce;
        this.detail = detail;
    }

    public static ManualNonceOperationResponse success(String operation,
                                                       String submitter,
                                                       long nonce,
                                                       String detail) {
        return new ManualNonceOperationResponse(operation, submitter, nonce, detail);
    }

    public String getOperation() {
        return operation;
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getNonce() {
        return nonce;
    }

    public String getDetail() {
        return detail;
    }
}

