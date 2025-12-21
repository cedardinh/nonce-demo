package com.work.nonce.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一条 signer + nonce 的状态记录。
 * 
 * 注意：
 * 1. id、signer、nonce是不可变字段，创建后不能修改
 * 2. status、lockedUntil、txHash、updatedAt是可变字段，会在状态转换时更新
 * 3. 此对象主要在事务中使用，线程安全性由事务保证
 */
public class NonceAllocation {

    private final long id;
    private final String signer;
    private final long nonce;
    private NonceAllocationStatus status;
    private Instant lockedUntil;
    private String txHash;
    private Instant updatedAt;

    public NonceAllocation(long id,
                           String signer,
                           long nonce,
                           NonceAllocationStatus status,
                           Instant lockedUntil,
                           String txHash,
                           Instant updatedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("id 必须大于0");
        }
        if (signer == null || signer.trim().isEmpty()) {
            throw new IllegalArgumentException("signer 不能为空");
        }
        if (nonce < 0) {
            throw new IllegalArgumentException("nonce 不能为负数");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt 不能为null");
        }
        
        this.id = id;
        this.signer = signer;
        this.nonce = nonce;
        this.status = status;
        this.lockedUntil = lockedUntil;
        this.txHash = txHash;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public String getSigner() {
        return signer;
    }

    public long getNonce() {
        return nonce;
    }

    public NonceAllocationStatus getStatus() {
        return status;
    }

    public void setStatus(NonceAllocationStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为null");
        }
        this.status = status;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NonceAllocation that = (NonceAllocation) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "NonceAllocation{" +
                "id=" + id +
                ", signer='" + signer + '\'' +
                ", nonce=" + nonce +
                ", status=" + status +
                ", lockedUntil=" + lockedUntil +
                ", txHash='" + txHash + '\'' +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

