package com.work.nonce.demo.web.dto;

/**
 * 泛型响应体，要求业务自定义 payload 时实现 {@link NonceResponsePayload}，
 * 以确保基础字段一致且类型安全。
 *
 * @param <T> 业务自定义的 payload 类型
 */
public class NonceResponse<T extends NonceResponsePayload> {

    private final String signer;
    private final long nonce;
    private final T payload;

    private NonceResponse(String signer, long nonce, T payload) {
        this.signer = signer;
        this.nonce = nonce;
        this.payload = payload;
    }

    public static <T extends NonceResponsePayload> NonceResponse<T> of(String signer, long nonce, T payload) {
        return new NonceResponse<>(signer, nonce, payload);
    }

    public String getSigner() {
        return signer;
    }

    public long getNonce() {
        return nonce;
    }

    public T getPayload() {
        return payload;
    }
}

