package com.work.nonce.core.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Nonce 分配记录表实体类
 */
@TableName("submitter_nonce_allocation")
public class NonceAllocationEntity {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /** submitter 唯一标识。 */
    private String submitter;
    
    /** 分配的 nonce 值。 */
    private Long nonce;
    
    /** 当前状态（RESERVED/RECYCLABLE/USED）。 */
    private String status;
    
    /** 持有该 nonce 的锁 owner（RESERVED 时存在）。 */
    private String lockOwner;
    
    /** 成功上链后的 txHash。 */
    private String txHash;
    
    /** 回收原因或备注。 */
    private String reason;
    
    /** 最近一次更新时间。 */
    private Instant updatedAt;
    
    /** 创建时间。 */
    private Instant createdAt;

    public NonceAllocationEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public Long getNonce() {
        return nonce;
    }

    public void setNonce(Long nonce) {
        this.nonce = nonce;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

