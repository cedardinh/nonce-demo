package com.work.nonce.core.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 交易回执（receipt）持久化表实体（最小字段集）。
 *
 * 说明：
 * - receipt 出现意味着 nonce 已被链消耗（不等价于业务成功）。
 * - 该表用于审计/对账/后续 confirmations 扩展。
 */
@TableName("tx_receipts")
public class TransactionReceiptEntity {

    @TableId(type = IdType.INPUT)
    private String txHash;

    private String submitter;

    private Long nonce;

    private Long blockNumber;

    private String blockHash;

    private Boolean success;

    /**
     * 当前确认数（基于 latestBlockNumber - blockNumber + 1 计算）
     */
    private Integer confirmations;

    /**
     * 是否达到最终确定阈值
     */
    private Boolean confirmed;

    /**
     * 达到最终确定时刻
     */
    private Instant confirmedAt;

    private Instant updatedAt;

    private Instant createdAt;

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
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

    public Long getBlockNumber() {
        return blockNumber;
    }

    public void setBlockNumber(Long blockNumber) {
        this.blockNumber = blockNumber;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public void setBlockHash(String blockHash) {
        this.blockHash = blockHash;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Integer getConfirmations() {
        return confirmations;
    }

    public void setConfirmations(Integer confirmations) {
        this.confirmations = confirmations;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
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


