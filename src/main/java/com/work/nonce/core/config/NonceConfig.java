package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    private final Duration reservedTimeout;
    private final int allocateMaxAttempts;
    private final Duration backoffBase;
    private final Duration backoffMax;

    public NonceConfig(Duration reservedTimeout,
                       int allocateMaxAttempts,
                       Duration backoffBase,
                       Duration backoffMax) {
        this.reservedTimeout = reservedTimeout;
        this.allocateMaxAttempts = allocateMaxAttempts;
        this.backoffBase = backoffBase;
        this.backoffMax = backoffMax;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(
                Duration.ofSeconds(30),
                15,
                Duration.ofMillis(15),
                Duration.ofMillis(200)
        );
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public int getAllocateMaxAttempts() {
        return allocateMaxAttempts;
    }

    public Duration getBackoffBase() {
        return backoffBase;
    }

    public Duration getBackoffMax() {
        return backoffMax;
    }
}

