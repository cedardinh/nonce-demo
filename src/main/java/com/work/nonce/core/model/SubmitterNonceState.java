package com.work.nonce.core.model;

import java.time.Instant;

/**
 * 对应 README 中的 submitter_nonce_state 表。
 * 
 * 注意：
 * 1. submitter是不可变字段，创建后不能修改
 * 2. lastChainNonce、nextLocalNonce、updatedAt是可变字段，会在状态更新时修改
 * 3. 此对象主要在事务中使用，线程安全性由事务保证
 */
public class SubmitterNonceState {

    private static final long INITIAL_LAST_CHAIN_NONCE = -1L;
    private static final long INITIAL_NEXT_LOCAL_NONCE = 0L;

    /** submitter 唯一标识，不可变。 */
    private final String submitter;
    /** 链上已确认的最新 nonce。 */
    private long lastChainNonce;
    /** 下一次可分配的本地 nonce。 */
    private long nextLocalNonce;
    /** 最近一次更新时间。 */
    private Instant updatedAt;

    public SubmitterNonceState(String submitter, long lastChainNonce, long nextLocalNonce, Instant updatedAt) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为null");
        }
        if (nextLocalNonce < 0) {
            throw new IllegalArgumentException("nextLocalNonce 不能为负数");
        }
        
        this.submitter = submitter;
        this.lastChainNonce = lastChainNonce;
        this.nextLocalNonce = nextLocalNonce;
        this.updatedAt = updatedAt;
    }

    /**
     * 创建初始状态的SubmitterNonceState
     */
    public static SubmitterNonceState init(String submitter) {
        return new SubmitterNonceState(submitter, INITIAL_LAST_CHAIN_NONCE, 
                                      INITIAL_NEXT_LOCAL_NONCE, Instant.now());
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getLastChainNonce() {
        return lastChainNonce;
    }

    public void setLastChainNonce(long lastChainNonce) {
        this.lastChainNonce = lastChainNonce;
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
        return "SubmitterNonceState{" +
                "submitter='" + submitter + '\'' +
                ", lastChainNonce=" + lastChainNonce +
                ", nextLocalNonce=" + nextLocalNonce +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

