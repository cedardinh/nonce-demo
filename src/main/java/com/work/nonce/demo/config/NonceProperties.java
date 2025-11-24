package com.work.nonce.demo.config;

import com.work.nonce.core.config.NonceConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import java.time.Duration;

/**
 * 读取 application.yml 中的 nonce.* 配置项，并构造核心模块理解的 {@link NonceConfig}。
 * <p>通过在 Spring Boot 中声明该 Bean，可实现对核心组件的集中化配置管理。</p>
 */
@Validated
@ConfigurationProperties(prefix = "nonce")
public class NonceProperties {

    /** 是否开启 Redis 性能模式。 */
    private boolean redisEnabled = true;
    /** Redis 分布式锁 TTL。 */
    private Duration lockTtl = Duration.ofSeconds(10);
    /** RESERVED 状态在性能模式下的过期时间。 */
    private Duration reservedTimeout = Duration.ofSeconds(30);
    /** flush worker 每批处理的事件数量（至少为 1）。 */
    @Min(1)
    private int flushBatchSize = 200;
    /** flush worker 调度周期。 */
    private Duration flushInterval = Duration.ofMillis(500);
    /** flush 失败后的重试间隔。 */
    private Duration flushRetryInterval = Duration.ofSeconds(5);
    /** 同一事件允许的最大重试次数（至少为 1）。 */
    @Min(1)
    private int flushMaxRetry = 3;
    /** DUAL_WRITE 阶段预热允许的最大耗时。 */
    private Duration dualWriteWarmupTimeout = Duration.ofSeconds(30);
    /** DRAIN_AND_SYNC 排水阶段最大等待时长。 */
    private Duration drainTimeout = Duration.ofSeconds(10);
    /** 可接受的刷盘延迟阈值。 */
    private Duration performanceLagTolerance = Duration.ofSeconds(5);
    /** Redis 异常是否自动触发降级。 */
    private boolean degradeOnRedisFailure = true;
    /** 性能模式一次预取的 nonce 数量。 */
    @Min(1)
    private int performancePrefetchBatchSize = 32;
    /** 消耗达到该比例后会自动触发下一波预取。 */
    @DecimalMin("0.01")
    @DecimalMax("0.99")
    private double performancePrefetchTriggerRatio = 0.9d;

    public NonceConfig toConfig() {
        return NonceConfig.builder()
                .redisEnabled(redisEnabled)
                .lockTtl(lockTtl)
                .reservedTimeout(reservedTimeout)
                .flushBatchSize(flushBatchSize)
                .flushInterval(flushInterval)
                .flushRetryInterval(flushRetryInterval)
                .flushMaxRetry(flushMaxRetry)
                .dualWriteWarmupTimeout(dualWriteWarmupTimeout)
                .drainTimeout(drainTimeout)
                .performanceLagTolerance(performanceLagTolerance)
                .degradeOnRedisFailure(degradeOnRedisFailure)
                .performancePrefetchBatchSize(performancePrefetchBatchSize)
                .performancePrefetchTriggerRatio(performancePrefetchTriggerRatio)
                .build();
    }

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

    public int getFlushBatchSize() {
        return flushBatchSize;
    }

    public void setFlushBatchSize(int flushBatchSize) {
        this.flushBatchSize = flushBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public Duration getFlushRetryInterval() {
        return flushRetryInterval;
    }

    public void setFlushRetryInterval(Duration flushRetryInterval) {
        this.flushRetryInterval = flushRetryInterval;
    }

    public int getFlushMaxRetry() {
        return flushMaxRetry;
    }

    public void setFlushMaxRetry(int flushMaxRetry) {
        this.flushMaxRetry = flushMaxRetry;
    }

    public Duration getDualWriteWarmupTimeout() {
        return dualWriteWarmupTimeout;
    }

    public void setDualWriteWarmupTimeout(Duration dualWriteWarmupTimeout) {
        this.dualWriteWarmupTimeout = dualWriteWarmupTimeout;
    }

    public Duration getDrainTimeout() {
        return drainTimeout;
    }

    public void setDrainTimeout(Duration drainTimeout) {
        this.drainTimeout = drainTimeout;
    }

    public Duration getPerformanceLagTolerance() {
        return performanceLagTolerance;
    }

    public void setPerformanceLagTolerance(Duration performanceLagTolerance) {
        this.performanceLagTolerance = performanceLagTolerance;
    }

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }

    public void setDegradeOnRedisFailure(boolean degradeOnRedisFailure) {
        this.degradeOnRedisFailure = degradeOnRedisFailure;
    }

    public int getPerformancePrefetchBatchSize() {
        return performancePrefetchBatchSize;
    }

    public void setPerformancePrefetchBatchSize(int performancePrefetchBatchSize) {
        this.performancePrefetchBatchSize = performancePrefetchBatchSize;
    }

    public double getPerformancePrefetchTriggerRatio() {
        return performancePrefetchTriggerRatio;
    }

    public void setPerformancePrefetchTriggerRatio(double performancePrefetchTriggerRatio) {
        this.performancePrefetchTriggerRatio = performancePrefetchTriggerRatio;
    }
}

