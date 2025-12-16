package com.work.nonce.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 仅存在于 demo/业务包，用于从 application.yml 读取配置。
 * 再由配置类转换为 core 包所需的 {@link com.work.nonce.core.config.NonceConfig}。
 */
@ConfigurationProperties(prefix = "nonce")
public class NonceProperties {

    private Duration reservedTimeout = Duration.ofSeconds(30);

    /**
     * allocate 的最大重试次数（用于乐观锁/唯一约束冲突时的退避重试）
     */
    private int allocateMaxAttempts = 15;

    /**
     * 退避重试的基础等待（会叠加指数退避与 jitter）
     */
    private Duration backoffBase = Duration.ofMillis(15);

    /**
     * 退避重试的最大等待上限
     */
    private Duration backoffMax = Duration.ofMillis(200);

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public void setReservedTimeout(Duration reservedTimeout) {
        this.reservedTimeout = reservedTimeout;
    }

    public int getAllocateMaxAttempts() {
        return allocateMaxAttempts;
    }

    public void setAllocateMaxAttempts(int allocateMaxAttempts) {
        this.allocateMaxAttempts = allocateMaxAttempts;
    }

    public Duration getBackoffBase() {
        return backoffBase;
    }

    public void setBackoffBase(Duration backoffBase) {
        this.backoffBase = backoffBase;
    }

    public Duration getBackoffMax() {
        return backoffMax;
    }

    public void setBackoffMax(Duration backoffMax) {
        this.backoffMax = backoffMax;
    }
}

