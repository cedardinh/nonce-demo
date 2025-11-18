package com.work.nonce.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一条 submitter + nonce 的状态记录。
 */
public class NonceAllocation {

    private final long id;
    private final String submitter;
    private final long nonce;
    private NonceAllocationStatus status;
    private String lockOwner;
    private Instant lockedUntil;
    private String txHash;
    private Instant updatedAt;

    public NonceAllocation(long id,
                           String submitter,
                           long nonce,
                           NonceAllocationStatus status,
                           String lockOwner,
                           Instant lockedUntil,
                           String txHash,
                           Instant updatedAt) {
        this.id = id;
        this.submitter = submitter;
        this.nonce = nonce;
        this.status = status;
        this.lockOwner = lockOwner;
        this.lockedUntil = lockedUntil;
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
        this.status = status;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
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
}

