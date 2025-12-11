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

    // 缓存相关
    private final boolean cacheEnabled;
    private final int cacheSize;
    private final Duration cacheTimeout;

    // 链上查询相关
    private final boolean chainQueryEnabled;
    private final int chainQueryMaxRetries;

    public NonceConfig(boolean redisEnabled,
                       Duration lockTtl,
                       Duration reservedTimeout,
                       boolean degradeOnRedisFailure,
                       boolean cacheEnabled,
                       int cacheSize,
                       Duration cacheTimeout,
                       boolean chainQueryEnabled,
                       int chainQueryMaxRetries) {
        this.redisEnabled = redisEnabled;
        this.lockTtl = lockTtl;
        this.reservedTimeout = reservedTimeout;
        this.degradeOnRedisFailure = degradeOnRedisFailure;
        this.cacheEnabled = cacheEnabled;
        this.cacheSize = cacheSize;
        this.cacheTimeout = cacheTimeout;
        this.chainQueryEnabled = chainQueryEnabled;
        this.chainQueryMaxRetries = chainQueryMaxRetries;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                true,
                true,
                1000,
                Duration.ofHours(1),
                true,
                3
        );
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

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public Duration getCacheTimeout() {
        return cacheTimeout;
    }

    public boolean isChainQueryEnabled() {
        return chainQueryEnabled;
    }

    public int getChainQueryMaxRetries() {
        return chainQueryMaxRetries;
    }
}

