package com.work.nonce.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 仅存在于 demo/业务包，用于从 application.yml 读取配置。
 * 再由配置类转换为 core 包所需的 {@link com.work.nonce.core.config.NonceConfig}。
 */
@ConfigurationProperties(prefix = "nonce")
public class NonceProperties {

    private boolean redisEnabled = true;
    private Duration lockTtl = Duration.ofSeconds(10);
    private Duration reservedTimeout = Duration.ofSeconds(30);
    private boolean degradeOnRedisFailure = true;

    /**
     * receipt 轮询间隔（demo 用）。生产环境应结合节点能力与吞吐做调优。
     */
    private Duration receiptPollInterval = Duration.ofSeconds(2);

    /**
     * 每次轮询最多处理多少条已提交记录
     */
    private int receiptPollBatchSize = 200;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public void setReservedTimeout(Duration reservedTimeout) {
        this.reservedTimeout = reservedTimeout;
    }

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }

    public void setDegradeOnRedisFailure(boolean degradeOnRedisFailure) {
        this.degradeOnRedisFailure = degradeOnRedisFailure;
    }

    public Duration getReceiptPollInterval() {
        return receiptPollInterval;
    }

    public void setReceiptPollInterval(Duration receiptPollInterval) {
        this.receiptPollInterval = receiptPollInterval;
    }

    public int getReceiptPollBatchSize() {
        return receiptPollBatchSize;
    }

    public void setReceiptPollBatchSize(int receiptPollBatchSize) {
        this.receiptPollBatchSize = receiptPollBatchSize;
    }
}

