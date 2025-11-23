package com.work.nonce.core.config;

import java.time.Duration;

/**
 * 纯组件侧的配置定义，不依赖任何上层框架。
 * <p>宿主系统只需在装配阶段注入 {@link NonceConfig} 即可，将配置与业务逻辑彻底解耦。</p>
 */
public class NonceConfig {

    /** 是否启用 Redis 性能模式。 */
    private final boolean redisEnabled;
    /** Redis 分布式锁的 TTL，防止锁意外遗留。 */
    private final Duration lockTtl;
    /** RESERVED 状态在性能模式中的超时时间，用于回收脏记录。 */
    private final Duration reservedTimeout;
    /** 每次刷盘批量处理的事件数量。 */
    private final int flushBatchSize;
    /** flush worker 任务调度周期。 */
    private final Duration flushInterval;
    /** flush 失败后的等待重试间隔。 */
    private final Duration flushRetryInterval;
    /** flush 失败允许的最大重试次数。 */
    private final int flushMaxRetry;
    /** 进入性能模式前，DUAL_WRITE 预热允许的最长耗时。 */
    private final Duration dualWriteWarmupTimeout;
    /** DRAIN_AND_SYNC 排水阶段允许的最大等待时间。 */
    private final Duration drainTimeout;
    /** 刷盘延迟超过该阈值时触发同步写兜底。 */
    private final Duration performanceLagTolerance;
    /** Redis 出现故障时是否自动降级回可靠模式。 */
    private final boolean degradeOnRedisFailure;

    private NonceConfig(Builder builder) {
        this.redisEnabled = builder.redisEnabled;
        this.lockTtl = builder.lockTtl;
        this.reservedTimeout = builder.reservedTimeout;
        this.flushBatchSize = builder.flushBatchSize;
        this.flushInterval = builder.flushInterval;
        this.flushRetryInterval = builder.flushRetryInterval;
        this.flushMaxRetry = builder.flushMaxRetry;
        this.dualWriteWarmupTimeout = builder.dualWriteWarmupTimeout;
        this.drainTimeout = builder.drainTimeout;
        this.performanceLagTolerance = builder.performanceLagTolerance;
        this.degradeOnRedisFailure = builder.degradeOnRedisFailure;
    }

    public static NonceConfig defaultConfig() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
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

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public Duration getFlushRetryInterval() {
        return flushRetryInterval;
    }

    public int getFlushMaxRetry() {
        return flushMaxRetry;
    }

    public Duration getDualWriteWarmupTimeout() {
        return dualWriteWarmupTimeout;
    }

    public Duration getDrainTimeout() {
        return drainTimeout;
    }

    public Duration getPerformanceLagTolerance() {
        return performanceLagTolerance;
    }

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }

    /**
     * 构造器模式，方便宿主按照需要覆盖默认值。
     */
    public static final class Builder {
        private boolean redisEnabled = true;
        private Duration lockTtl = Duration.ofSeconds(10);
        private Duration reservedTimeout = Duration.ofSeconds(30);
        private int flushBatchSize = 200;
        private Duration flushInterval = Duration.ofSeconds(1);
        private Duration flushRetryInterval = Duration.ofSeconds(5);
        private int flushMaxRetry = 3;
        private Duration dualWriteWarmupTimeout = Duration.ofSeconds(30);
        private Duration drainTimeout = Duration.ofSeconds(10);
        private Duration performanceLagTolerance = Duration.ofSeconds(5);
        private boolean degradeOnRedisFailure = true;

        public Builder redisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
            return this;
        }

        public Builder lockTtl(Duration lockTtl) {
            this.lockTtl = lockTtl;
            return this;
        }

        public Builder reservedTimeout(Duration reservedTimeout) {
            this.reservedTimeout = reservedTimeout;
            return this;
        }

        public Builder flushBatchSize(int flushBatchSize) {
            this.flushBatchSize = flushBatchSize;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        public Builder flushRetryInterval(Duration flushRetryInterval) {
            this.flushRetryInterval = flushRetryInterval;
            return this;
        }

        public Builder flushMaxRetry(int flushMaxRetry) {
            this.flushMaxRetry = flushMaxRetry;
            return this;
        }

        public Builder dualWriteWarmupTimeout(Duration dualWriteWarmupTimeout) {
            this.dualWriteWarmupTimeout = dualWriteWarmupTimeout;
            return this;
        }

        public Builder drainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
            return this;
        }

        public Builder performanceLagTolerance(Duration performanceLagTolerance) {
            this.performanceLagTolerance = performanceLagTolerance;
            return this;
        }

        public Builder degradeOnRedisFailure(boolean degradeOnRedisFailure) {
            this.degradeOnRedisFailure = degradeOnRedisFailure;
            return this;
        }

        public NonceConfig build() {
            return new NonceConfig(this);
        }
    }
}

