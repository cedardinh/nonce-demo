package com.work.nonce.txmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TxMgr 配置项（对应 111最终方案.md 的落地需要）。
 *
 * 注意：这里仅提供工程落地所需的最小配置载体，不改变 111最终方案.md 的语义边界。
 */
@ConfigurationProperties(prefix = "txmgr")
public class TxMgrProperties {

    /**
     * lease 时长（必须 > 0）。
     */
    private Duration leaseDuration = Duration.ofSeconds(10);

    /**
     * 续租间隔（必须 < leaseDuration）。
     */
    private Duration renewInterval = Duration.ofSeconds(3);

    /**
     * 时钟偏移容忍（用于保守判断过期）。
     */
    private Duration clockSkewAllowance = Duration.ofSeconds(1);

    /**
     * 节点内 writer worker 数量（固定分片）。
     */
    private int writerWorkers = 256;

    /**
     * writer 单批最大 op 数。
     */
    private int writerBatchSize = 50;

    /**
     * nonce cache 的“新鲜度”阈值（类似 FFTM nonceStateTimeout）。
     */
    private Duration nonceStateTimeout = Duration.ofHours(1);

    /**
     * receipt 查询 worker 数（为后续里程碑预留）。
     */
    private int receiptWorkers = 10;

    /**
     * receipt 兜底轮询周期（NotFound 后延迟重试的基础间隔）。
     */
    private Duration staleReceiptTimeout = Duration.ofSeconds(60);

    /**
     * receipt 扫描周期（从 DB 拉取待查 receipt 的交易）。
     */
    private Duration receiptScanInterval = Duration.ofSeconds(5);

    /**
     * resubmit 扫描周期（从 DB 拉取 next_resubmit_at 到期的交易）。
     */
    private Duration resubmitScanInterval = Duration.ofSeconds(5);

    /**
     * 每次 submit/resubmit 后，下一次允许 resubmit 的时间间隔。
     */
    private Duration resubmitInterval = Duration.ofSeconds(30);

    /**
     * 最多允许提交/重提次数，超过则进入 STUCK（可由 hook 覆盖处理）。
     */
    private int resubmitMaxAttempts = 5;

    /**
     * finality 判定所需 confirmations（demo 默认 1：receipt 即终局）。
     */
    private int finalityConfirmations = 1;

    /**
     * finality 扫描周期：从 DB 拉取 receipt 已就绪但未终局的交易并尝试推进。
     */
    private Duration finalityScanInterval = Duration.ofSeconds(5);

    /**
     * pending 堆积阈值：超过则触发头部卡住治理（最小实现）。
     */
    private int pendingBacklogThreshold = 50;

    /**
     * 头部/最老 pending 超过该时长视为风险（触发 resubmit/补救决策）。
     */
    private Duration pendingOldestAgeThreshold = Duration.ofMinutes(5);

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getRenewInterval() {
        return renewInterval;
    }

    public void setRenewInterval(Duration renewInterval) {
        this.renewInterval = renewInterval;
    }

    public Duration getClockSkewAllowance() {
        return clockSkewAllowance;
    }

    public void setClockSkewAllowance(Duration clockSkewAllowance) {
        this.clockSkewAllowance = clockSkewAllowance;
    }

    public int getWriterWorkers() {
        return writerWorkers;
    }

    public void setWriterWorkers(int writerWorkers) {
        this.writerWorkers = writerWorkers;
    }

    public int getWriterBatchSize() {
        return writerBatchSize;
    }

    public void setWriterBatchSize(int writerBatchSize) {
        this.writerBatchSize = writerBatchSize;
    }

    public Duration getNonceStateTimeout() {
        return nonceStateTimeout;
    }

    public void setNonceStateTimeout(Duration nonceStateTimeout) {
        this.nonceStateTimeout = nonceStateTimeout;
    }

    public int getReceiptWorkers() {
        return receiptWorkers;
    }

    public void setReceiptWorkers(int receiptWorkers) {
        this.receiptWorkers = receiptWorkers;
    }

    public Duration getStaleReceiptTimeout() {
        return staleReceiptTimeout;
    }

    public void setStaleReceiptTimeout(Duration staleReceiptTimeout) {
        this.staleReceiptTimeout = staleReceiptTimeout;
    }

    public Duration getReceiptScanInterval() {
        return receiptScanInterval;
    }

    public void setReceiptScanInterval(Duration receiptScanInterval) {
        this.receiptScanInterval = receiptScanInterval;
    }

    public Duration getResubmitScanInterval() {
        return resubmitScanInterval;
    }

    public void setResubmitScanInterval(Duration resubmitScanInterval) {
        this.resubmitScanInterval = resubmitScanInterval;
    }

    public Duration getResubmitInterval() {
        return resubmitInterval;
    }

    public void setResubmitInterval(Duration resubmitInterval) {
        this.resubmitInterval = resubmitInterval;
    }

    public int getResubmitMaxAttempts() {
        return resubmitMaxAttempts;
    }

    public void setResubmitMaxAttempts(int resubmitMaxAttempts) {
        this.resubmitMaxAttempts = resubmitMaxAttempts;
    }

    public int getFinalityConfirmations() {
        return finalityConfirmations;
    }

    public void setFinalityConfirmations(int finalityConfirmations) {
        this.finalityConfirmations = finalityConfirmations;
    }

    public Duration getFinalityScanInterval() {
        return finalityScanInterval;
    }

    public void setFinalityScanInterval(Duration finalityScanInterval) {
        this.finalityScanInterval = finalityScanInterval;
    }

    public int getPendingBacklogThreshold() {
        return pendingBacklogThreshold;
    }

    public void setPendingBacklogThreshold(int pendingBacklogThreshold) {
        this.pendingBacklogThreshold = pendingBacklogThreshold;
    }

    public Duration getPendingOldestAgeThreshold() {
        return pendingOldestAgeThreshold;
    }

    public void setPendingOldestAgeThreshold(Duration pendingOldestAgeThreshold) {
        this.pendingOldestAgeThreshold = pendingOldestAgeThreshold;
    }
}


