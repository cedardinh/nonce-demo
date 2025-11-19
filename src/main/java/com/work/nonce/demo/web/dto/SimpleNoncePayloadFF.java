package com.work.nonce.demo.web.dto;

/**
 * demo 级别的默认 payload，实现 {@link NonceResponsePayload}，方便示例快速返回链上结果。
 */
public class SimpleNoncePayloadFF implements NonceResponsePayload {

    /** 模拟链上返回的 txHash。 */
    private final String txHash;
    /** 回声字段，方便前端确认 payload。 */
    private final String payloadEcho;

    public SimpleNoncePayloadFF(String txHash, String payloadEcho) {
        this.txHash = txHash;
        this.payloadEcho = payloadEcho;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getPayloadEcho() {
        return payloadEcho;
    }
}

