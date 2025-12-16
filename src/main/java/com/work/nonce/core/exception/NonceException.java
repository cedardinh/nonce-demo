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

    /**
     * 标识该异常是否可通过重试解决（用于乐观锁冲突/唯一约束冲突等场景）。
     * 默认不可重试。
     */
    public boolean isRetryable() {
        return false;
    }
}

