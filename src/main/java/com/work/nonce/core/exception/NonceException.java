package com.work.nonce.core.exception;

/**
 * 组件内部的统⼀异常类型，便于业务侧捕获或转换为 RPC 错误码。
 */
public class NonceException extends RuntimeException {

    public NonceException(String message) {
        super(message);
    }

    public NonceException(String message, Throwable cause) {
        super(message, cause);
    }
}

