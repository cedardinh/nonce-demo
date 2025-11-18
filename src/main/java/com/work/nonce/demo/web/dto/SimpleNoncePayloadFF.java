package com.work.nonce.demo.web.dto;

/**
 * demo 级别的默认 payload，实现 {@link NonceResponsePayload}，方便示例快速返回链上结果。
 */
public class SimpleNoncePayloadFF implements NonceResponsePayload {

    private final String txHash;
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

