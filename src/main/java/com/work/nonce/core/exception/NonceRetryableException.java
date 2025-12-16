package com.work.nonce.core.exception;

/**
 * 可重试的 nonce 异常：用于乐观锁冲突、唯一约束冲突等场景。
 */
public class NonceRetryableException extends NonceException {

    public NonceRetryableException(String message) {
        super(message);
    }

    public NonceRetryableException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}


