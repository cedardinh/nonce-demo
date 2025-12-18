package com.work.nonce.txmgr.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("submitter_lease")
public class SubmitterLeaseEntity {

    @TableId(type = IdType.INPUT)
    private String submitter;

    private String ownerNode;

    private Long fencingToken;

    private Instant expiresAt;

    private Instant updatedAt;

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public String getOwnerNode() {
        return ownerNode;
    }

    public void setOwnerNode(String ownerNode) {
        this.ownerNode = ownerNode;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}


