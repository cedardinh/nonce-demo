package com.work.nonce.txmgr.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("submitter_nonce_cursor")
public class SubmitterNonceCursorEntity {

    @TableId(type = IdType.INPUT)
    private String submitter;

    private Long nextNonce;

    private Long fencingToken;

    private Instant updatedAt;

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public Long getNextNonce() {
        return nextNonce;
    }

    public void setNextNonce(Long nextNonce) {
        this.nextNonce = nextNonce;
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
}


