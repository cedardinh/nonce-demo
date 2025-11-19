package com.work.nonce.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一条 submitter + nonce 的状态记录。
 * 
 * 注意：
 * 1. id、submitter、nonce是不可变字段，创建后不能修改
 * 2. status、lockOwner、lockedUntil、txHash、updatedAt是可变字段，会在状态转换时更新
 * 3. 此对象主要在事务中使用，线程安全性由事务保证
 */
public class NonceAllocation {

    /** 持久化主键。 */
    private final long id;
    /** submitter 唯一标识。 */
    private final String submitter;
    /** 分配的 nonce 值。 */
    private final long nonce;
    /** 当前状态（RESERVED/RECYCLABLE/USED）。 */
    private NonceAllocationStatus status;
    /** 持有该 nonce 的锁 owner，RESERVED 时存在。 */
    private String lockOwner;
    /** 成功上链后的 txHash。 */
    private String txHash;
    /** 最近一次更新时间。 */
    private Instant updatedAt;

    public NonceAllocation(long id,
                           String submitter,
                           long nonce,
                           NonceAllocationStatus status,
                           String lockOwner,
                           String txHash,
                           Instant updatedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("id 必须大于0");
        }
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
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
        this.submitter = submitter;
        this.nonce = nonce;
        this.status = status;
        this.lockOwner = lockOwner;
        this.txHash = txHash;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public String getSubmitter() {
        return submitter;
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

    public String getLockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
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
                ", submitter='" + submitter + '\'' +
                ", nonce=" + nonce +
                ", status=" + status +
                ", lockOwner='" + lockOwner + '\'' +
                ", txHash='" + txHash + '\'' +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

