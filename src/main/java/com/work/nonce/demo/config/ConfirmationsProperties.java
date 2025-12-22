package com.work.nonce.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 参考 FireFly Transaction Manager 的 confirmations 配置，提供最小可用的参数集合。
 */
@ConfigurationProperties(prefix = "confirmations")
public class ConfirmationsProperties {

    /**
     * 达到多少确认后认为最终确定
     */
    private int required = 20;

    /**
     * 交易进入跟踪后，如果在该时间内没有 receipt 更新，则触发一次 receipt 查询
     * 在当前实现中用于过滤哪些已提交记录需要被查询
     */
    private Duration staleReceiptTimeout = Duration.ofMinutes(1);

    /**
     * receipt 查询 worker 数
     */
    private int receiptWorkers = 10;

    /**
     * 是否在进入跟踪时立即查询一次 receipt
     * 在当前实现中等价为忽略 staleReceiptTimeout 过滤
     */
    private boolean fetchReceiptUponEntry = false;

    /**
     * receipt 轮询触发间隔
     */
    private Duration receiptPollInterval = Duration.ofSeconds(2);

    /**
     * confirmations 轮询触发间隔
     */
    private Duration confirmationsPollInterval = Duration.ofSeconds(2);

    /**
     * 每次批量最多处理多少条记录
     */
    private int batchSize = 200;

    public int getRequired() {
        return required;
    }

    public void setRequired(int required) {
        this.required = required;
    }

    public Duration getStaleReceiptTimeout() {
        return staleReceiptTimeout;
    }

    public void setStaleReceiptTimeout(Duration staleReceiptTimeout) {
        this.staleReceiptTimeout = staleReceiptTimeout;
    }

    public int getReceiptWorkers() {
        return receiptWorkers;
    }

    public void setReceiptWorkers(int receiptWorkers) {
        this.receiptWorkers = receiptWorkers;
    }

    public boolean isFetchReceiptUponEntry() {
        return fetchReceiptUponEntry;
    }

    public void setFetchReceiptUponEntry(boolean fetchReceiptUponEntry) {
        this.fetchReceiptUponEntry = fetchReceiptUponEntry;
    }

    public Duration getReceiptPollInterval() {
        return receiptPollInterval;
    }

    public void setReceiptPollInterval(Duration receiptPollInterval) {
        this.receiptPollInterval = receiptPollInterval;
    }

    public Duration getConfirmationsPollInterval() {
        return confirmationsPollInterval;
    }

    public void setConfirmationsPollInterval(Duration confirmationsPollInterval) {
        this.confirmationsPollInterval = confirmationsPollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}


