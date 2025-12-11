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

    // 缓存配置
    private boolean cacheEnabled = true;
    private int cacheSize = 1000;
    private Duration cacheTimeout = Duration.ofHours(1);

    // 链上查询配置
    private boolean chainQueryEnabled = true;
    private int chainQueryMaxRetries = 3;

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

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public Duration getCacheTimeout() {
        return cacheTimeout;
    }

    public void setCacheTimeout(Duration cacheTimeout) {
        this.cacheTimeout = cacheTimeout;
    }

    public boolean isChainQueryEnabled() {
        return chainQueryEnabled;
    }

    public void setChainQueryEnabled(boolean chainQueryEnabled) {
        this.chainQueryEnabled = chainQueryEnabled;
    }

    public int getChainQueryMaxRetries() {
        return chainQueryMaxRetries;
    }

    public void setChainQueryMaxRetries(int chainQueryMaxRetries) {
        this.chainQueryMaxRetries = chainQueryMaxRetries;
    }
}

