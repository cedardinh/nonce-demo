package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    private final Duration lockTtl;
    private final Duration reservedTimeout;
    private final Duration leaseTtl;
    private final String ownerId;

    public NonceConfig(Duration lockTtl,
                       Duration reservedTimeout,
                       Duration leaseTtl,
                       String ownerId) {
        this.lockTtl = lockTtl;
        this.reservedTimeout = reservedTimeout;
        this.leaseTtl = leaseTtl;
        this.ownerId = ownerId;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(10), "local");
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public String getOwnerId() {
        return ownerId;
    }
}

