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
    /** Redis key 的默认 TTL（用于计数器/回收池/快照），防止内存泄漏。0 表示永不过期（不推荐）。 */
    private final Duration redisKeyTtl;
    /** RESERVED 状态的过期时间，超过该时间未转为 USED/RECYCLABLE 将被自动回收。 */
    private final Duration staleReservedTimeout;
    /** 历史 USED 记录保留天数，超过该天数的记录将被归档/清理（0 表示不清理）。 */
    private final int usedRecordRetentionDays;
    /** 性能模式下一次批量预取的 nonce 数量。 */
    private final int performancePrefetchBatchSize;
    /** 当批量消费比例超过该阈值时触发下一波预取（0-1之间）。 */
    private final double performancePrefetchTriggerRatio;

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
        this.redisKeyTtl = builder.redisKeyTtl;
        this.staleReservedTimeout = builder.staleReservedTimeout;
        this.usedRecordRetentionDays = builder.usedRecordRetentionDays;
        this.performancePrefetchBatchSize = builder.performancePrefetchBatchSize;
        this.performancePrefetchTriggerRatio = builder.performancePrefetchTriggerRatio;
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
    
    public Duration getRedisKeyTtl() {
        return redisKeyTtl;
    }
    
    public Duration getStaleReservedTimeout() {
        return staleReservedTimeout;
    }
    
    public int getUsedRecordRetentionDays() {
        return usedRecordRetentionDays;
    }

    public int getPerformancePrefetchBatchSize() {
        return performancePrefetchBatchSize;
    }

    public double getPerformancePrefetchTriggerRatio() {
        return performancePrefetchTriggerRatio;
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
        private Duration redisKeyTtl = Duration.ofDays(30); // 默认 30 天过期
        private Duration staleReservedTimeout = Duration.ofHours(1); // 默认 1 小时未处理视为过期
        private int usedRecordRetentionDays = 90; // 默认保留 90 天
        private int performancePrefetchBatchSize = 32;
        private double performancePrefetchTriggerRatio = 0.9d;

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
        
        public Builder redisKeyTtl(Duration redisKeyTtl) {
            this.redisKeyTtl = redisKeyTtl;
            return this;
        }
        
        public Builder staleReservedTimeout(Duration staleReservedTimeout) {
            this.staleReservedTimeout = staleReservedTimeout;
            return this;
        }
        
        public Builder usedRecordRetentionDays(int usedRecordRetentionDays) {
            this.usedRecordRetentionDays = usedRecordRetentionDays;
            return this;
        }

        public Builder performancePrefetchBatchSize(int performancePrefetchBatchSize) {
            this.performancePrefetchBatchSize = performancePrefetchBatchSize;
            return this;
        }

        public Builder performancePrefetchTriggerRatio(double performancePrefetchTriggerRatio) {
            this.performancePrefetchTriggerRatio = performancePrefetchTriggerRatio;
            return this;
        }

        public NonceConfig build() {
            // 配置合理性校验
            validateConfig();
            return new NonceConfig(this);
        }
        
        /**
         * 校验配置合理性，防止不安全的参数组合。
         */
        private void validateConfig() {
            // 锁 TTL 必须大于事务超时（留出余量），否则锁可能提前释放
            // ReliableNonceEngine.TRANSACTION_TIMEOUT_SECONDS = 5s
            long minLockTtlSeconds = 5 + 2; // 事务超时 + 余量
            if (lockTtl.getSeconds() < minLockTtlSeconds) {
                throw new IllegalArgumentException(String.format(
                        "lockTtl (%ds) 必须 >= %ds (事务超时5s + 余量2s)，否则锁可能在事务完成前过期",
                        lockTtl.getSeconds(), minLockTtlSeconds));
            }
            
            // 批量大小必须合理
            if (flushBatchSize <= 0 || flushBatchSize > 10000) {
                throw new IllegalArgumentException(
                        "flushBatchSize 必须在 1-10000 之间，当前值: " + flushBatchSize);
            }
            
            // 各种超时必须为正数
            if (reservedTimeout.isNegative() || reservedTimeout.isZero()) {
                throw new IllegalArgumentException("reservedTimeout 必须大于 0");
            }
            if (flushInterval.isNegative() || flushInterval.isZero()) {
                throw new IllegalArgumentException("flushInterval 必须大于 0");
            }
            if (drainTimeout.isNegative() || drainTimeout.isZero()) {
                throw new IllegalArgumentException("drainTimeout 必须大于 0");
            }
            if (performanceLagTolerance.isNegative() || performanceLagTolerance.isZero()) {
                throw new IllegalArgumentException("performanceLagTolerance 必须大于 0");
            }
            
            // 重试次数必须合理
            if (flushMaxRetry < 0 || flushMaxRetry > 100) {
                throw new IllegalArgumentException(
                        "flushMaxRetry 必须在 0-100 之间，当前值: " + flushMaxRetry);
            }
            
            // Redis key TTL 必须合理（0 表示不过期）
            if (redisKeyTtl != null && redisKeyTtl.isNegative()) {
                throw new IllegalArgumentException("redisKeyTtl 不能为负数");
            }
            
            // staleReservedTimeout 必须合理
            if (staleReservedTimeout.isNegative() || staleReservedTimeout.isZero()) {
                throw new IllegalArgumentException("staleReservedTimeout 必须大于 0");
            }
            
            // 保留天数必须非负
            if (usedRecordRetentionDays < 0) {
                throw new IllegalArgumentException("usedRecordRetentionDays 不能为负数");
            }

            if (performancePrefetchBatchSize <= 0 || performancePrefetchBatchSize > 10000) {
                throw new IllegalArgumentException(
                        "performancePrefetchBatchSize 必须在 1-10000 之间，当前值: " + performancePrefetchBatchSize);
            }
            if (performancePrefetchTriggerRatio <= 0.0d || performancePrefetchTriggerRatio >= 1.0d) {
                throw new IllegalArgumentException(
                        "performancePrefetchTriggerRatio 必须在 0-1 之间（不含端点），当前值: " + performancePrefetchTriggerRatio);
            }
        }
    }
}

