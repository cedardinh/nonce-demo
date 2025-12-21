package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    private final Duration lockTtl;
    private final Duration reservedTimeout;

    public NonceConfig(Duration lockTtl,
                       Duration reservedTimeout) {
        this.lockTtl = lockTtl;
        this.reservedTimeout = reservedTimeout;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }
}

