package com.work.nonce.core.engine.performance;

import com.work.nonce.core.engine.spi.EngineHealthSnapshot;
import java.time.Instant;

/**
 * 性能模式的健康状态快照，由 PerformanceNonceEngine 暴露，用于决策降级。
 */
public class PerformanceHealthSnapshot implements EngineHealthSnapshot {

    /** Redis 读写是否正常。 */
    private final boolean redisHealthy;
    /** 刷盘链路是否健康。 */
    private final boolean flushHealthy;
    /** 当前待刷盘事件数量（包括 main 与 pending）。 */
    private final long pendingEvents;
    /** 最近一次刷盘成功的时间，用于评估延迟。 */
    private final Instant lastFlushSuccessAt;
    /** 最近一次刷盘失败的时间，用于告警判断。 */
    private final Instant lastFlushFailureAt;

    /**
     * 构造函数，按快照数据初始化各字段。
     */
    public PerformanceHealthSnapshot(boolean redisHealthy,
                                     boolean flushHealthy,
                                     long pendingEvents,
                                     Instant lastFlushSuccessAt,
                                     Instant lastFlushFailureAt) {
        this.redisHealthy = redisHealthy;
        this.flushHealthy = flushHealthy;
        this.pendingEvents = pendingEvents;
        this.lastFlushSuccessAt = lastFlushSuccessAt;
        this.lastFlushFailureAt = lastFlushFailureAt;
    }

    /** @return Redis 是否处于健康状态 */
    public boolean isRedisHealthy() {
        return redisHealthy;
    }

    /** @return 刷盘链路是否健康 */
    public boolean isFlushHealthy() {
        return flushHealthy;
    }

    /** @return 当前队列中待刷盘事件数量 */
    public long getPendingEvents() {
        return pendingEvents;
    }

    /** @return 最近一次刷盘成功时间 */
    public Instant getLastFlushSuccessAt() {
        return lastFlushSuccessAt;
    }

    /** @return 最近一次刷盘失败时间 */
    public Instant getLastFlushFailureAt() {
        return lastFlushFailureAt;
    }
}

