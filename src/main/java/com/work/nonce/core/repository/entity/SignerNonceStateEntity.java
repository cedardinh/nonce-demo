package com.work.nonce.core.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * signer nonce 状态表实体类。
 */
@TableName("signer_nonce_state")
public class SignerNonceStateEntity {

    @TableId(value = "signer", type = IdType.INPUT)
    private String signer;

    private Long nextLocalNonce;

    private Long fencingToken;

    private Instant updatedAt;

    private Instant createdAt;

    public SignerNonceStateEntity() {
    }

    public SignerNonceStateEntity(String signer, Long nextLocalNonce, Long fencingToken, Instant updatedAt, Instant createdAt) {
        this.signer = signer;
        this.nextLocalNonce = nextLocalNonce;
        this.fencingToken = fencingToken;
        this.updatedAt = updatedAt;
        this.createdAt = createdAt;
    }

    public String getSigner() {
        return signer;
    }

    public void setSigner(String signer) {
        this.signer = signer;
    }

    public Long getNextLocalNonce() {
        return nextLocalNonce;
    }

    public void setNextLocalNonce(Long nextLocalNonce) {
        this.nextLocalNonce = nextLocalNonce;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
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


