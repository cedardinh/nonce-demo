package com.work.nonce.demo.web.dto;

/**
 * 泛型响应体，要求业务自定义 payload 时实现 {@link NonceResponsePayload}，
 * 以确保基础字段一致且类型安全。
 *
 * @param <T> 业务自定义的 payload 类型
 */
public class NonceResponse<T extends NonceResponsePayload> {

    private final String submitter;
    private final long nonce;
    private final T payload;

    private NonceResponse(String submitter, long nonce, T payload) {
        this.submitter = submitter;
        this.nonce = nonce;
        this.payload = payload;
    }

    public static <T extends NonceResponsePayload> NonceResponse<T> of(String submitter, long nonce, T payload) {
        return new NonceResponse<>(submitter, nonce, payload);
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getNonce() {
        return nonce;
    }

    public T getPayload() {
        return payload;
    }
}

