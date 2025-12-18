package com.work.nonce.txmgr.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("managed_tx")
public class ManagedTxEntity {

    @TableId(type = IdType.INPUT)
    private UUID txId;

    private String submitter;

    private String requestId;

    private Long nonce;

    /**
     * 以 json 字符串形式传入，由 SQL 侧 CAST 为 jsonb（避免引入额外 type handler）。
     */
    private String payload;

    private String txHash;

    private String state;

    private String subState;

    private Instant lastSubmitAt;

    private Instant lastReceiptCheckAt;

    private Instant nextResubmitAt;

    private Integer submitAttempts;

    private String lastError;

    private String receipt;

    private Instant confirmedAt;

    private Long fencingToken;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getTxId() {
        return txId;
    }

    public void setTxId(UUID txId) {
        this.txId = txId;
    }

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getNonce() {
        return nonce;
    }

    public void setNonce(Long nonce) {
        this.nonce = nonce;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getSubState() {
        return subState;
    }

    public void setSubState(String subState) {
        this.subState = subState;
    }

    public Instant getLastSubmitAt() {
        return lastSubmitAt;
    }

    public void setLastSubmitAt(Instant lastSubmitAt) {
        this.lastSubmitAt = lastSubmitAt;
    }

    public Instant getLastReceiptCheckAt() {
        return lastReceiptCheckAt;
    }

    public void setLastReceiptCheckAt(Instant lastReceiptCheckAt) {
        this.lastReceiptCheckAt = lastReceiptCheckAt;
    }

    public Instant getNextResubmitAt() {
        return nextResubmitAt;
    }

    public void setNextResubmitAt(Instant nextResubmitAt) {
        this.nextResubmitAt = nextResubmitAt;
    }

    public Integer getSubmitAttempts() {
        return submitAttempts;
    }

    public void setSubmitAttempts(Integer submitAttempts) {
        this.submitAttempts = submitAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getReceipt() {
        return receipt;
    }

    public void setReceipt(String receipt) {
        this.receipt = receipt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}


