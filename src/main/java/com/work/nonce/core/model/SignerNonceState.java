package com.work.nonce.core.model;

import java.time.Instant;

/**
 * 对应数据库中的 signer_nonce_state 表。
 *
 * 注意：
 * 1. signer 是不可变字段，创建后不能修改
 * 2. nextLocalNonce、updatedAt 是可变字段，会在状态更新时修改
 * 3. 此对象主要在事务中使用，线程安全性由事务保证
 */
public class SignerNonceState {

    private static final long INITIAL_NEXT_LOCAL_NONCE = 0L;

    private final String signer;
    private long nextLocalNonce;
    private Instant updatedAt;

    public SignerNonceState(String signer, long nextLocalNonce, Instant updatedAt) {
        if (signer == null || signer.trim().isEmpty()) {
            throw new IllegalArgumentException("signer 不能为空");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为null");
        }
        if (nextLocalNonce < 0) {
            throw new IllegalArgumentException("nextLocalNonce 不能为负数");
        }

        this.signer = signer;
        this.nextLocalNonce = nextLocalNonce;
        this.updatedAt = updatedAt;
    }

    /**
     * 创建初始状态的 SignerNonceState。
     */
    public static SignerNonceState init(String signer) {
        return new SignerNonceState(signer, INITIAL_NEXT_LOCAL_NONCE, Instant.now());
    }

    public String getSigner() {
        return signer;
    }

    public long getNextLocalNonce() {
        return nextLocalNonce;
    }

    public void setNextLocalNonce(long nextLocalNonce) {
        if (nextLocalNonce < 0) {
            throw new IllegalArgumentException("nextLocalNonce 不能为负数");
        }
        this.nextLocalNonce = nextLocalNonce;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为null");
        }
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "SignerNonceState{" +
                "signer='" + signer + '\'' +
                ", nextLocalNonce=" + nextLocalNonce +
                ", updatedAt=" + updatedAt +
                '}';
    }
}


