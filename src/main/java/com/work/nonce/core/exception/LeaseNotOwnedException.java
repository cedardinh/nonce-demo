package com.work.nonce.core.exception;

/**
 * 未能获取 signer 的写入租约（lease），调用方应当重试。
 */
public class LeaseNotOwnedException extends NonceException {

    public LeaseNotOwnedException(String message) {
        super(message);
    }

    public LeaseNotOwnedException(String message, Throwable cause) {
        super(message, cause);
    }
}


