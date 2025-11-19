package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    /**
     * 单次 Redis 锁持有的 TTL，避免死锁或节点宕机后锁无法释放。
     */
    private final Duration lockTtl;

    public NonceConfig(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(Duration.ofSeconds(10));
    }

    public Duration getLockTtl() {
        return lockTtl;
    }
}

