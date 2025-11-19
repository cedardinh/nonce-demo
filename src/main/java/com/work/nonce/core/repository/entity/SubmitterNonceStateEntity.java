package com.work.nonce.core.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Submitter n once 状态表实体类
 */
@TableName("submitter_nonce_state")
public class SubmitterNonceStateEntity {

    /** submitter 唯一标识，作为主键。 */
    @TableId(type = IdType.INPUT)
    private String submitter;
    
    /** 链上已确认的最新 nonce（用于灾备对账）。 */
    private Long lastChainNonce;
    
    /** 本地下一次可分配的 nonce。 */
    private Long nextLocalNonce;
    
    /** 最近一次状态变更时间。 */
    private Instant updatedAt;
    
    /** 创建时间。 */
    private Instant createdAt;

    public SubmitterNonceStateEntity() {
    }

    public SubmitterNonceStateEntity(String submitter, Long lastChainNonce, Long nextLocalNonce, Instant updatedAt, Instant createdAt) {
        this.submitter = submitter;
        this.lastChainNonce = lastChainNonce;
        this.nextLocalNonce = nextLocalNonce;
        this.updatedAt = updatedAt;
        this.createdAt = createdAt;
    }

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public Long getLastChainNonce() {
        return lastChainNonce;
    }

    public void setLastChainNonce(Long lastChainNonce) {
        this.lastChainNonce = lastChainNonce;
    }

    public Long getNextLocalNonce() {
        return nextLocalNonce;
    }

    public void setNextLocalNonce(Long nextLocalNonce) {
        this.nextLocalNonce = nextLocalNonce;
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

