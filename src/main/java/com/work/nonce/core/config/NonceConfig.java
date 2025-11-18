package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    private final boolean redisEnabled;
    private final Duration lockTtl;
    private final Duration reservedTimeout;
    private final boolean degradeOnRedisFailure;

    public NonceConfig(boolean redisEnabled,
                       Duration lockTtl,
                       Duration reservedTimeout,
                       boolean degradeOnRedisFailure) {
        this.redisEnabled = redisEnabled;
        this.lockTtl = lockTtl;
        this.reservedTimeout = reservedTimeout;
        this.degradeOnRedisFailure = degradeOnRedisFailure;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(true, Duration.ofSeconds(10), Duration.ofSeconds(30), true);
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }
}

