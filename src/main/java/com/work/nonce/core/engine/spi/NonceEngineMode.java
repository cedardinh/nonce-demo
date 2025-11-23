package com.work.nonce.core.engine.spi;

/**
 * 描述 Nonce 引擎的运行模式。
 */
public enum NonceEngineMode {
    /** 可靠模式：所有流量直接落 PostgreSQL，作为真相源。 */
    RELIABLE,
    /** DUAL_WRITE：可靠模式仍然生效，同时镜像写 Redis，为性能模式预热。 */
    DUAL_WRITE,
    /** 性能模式：以 Redis 为主流程，异步刷盘回 DB。 */
    PERFORMANCE,
    /** 降级模式：性能链路异常后自动退回可靠模式，但保留健康告警。 */
    DEGRADED,
    /** 排水模式：待刷盘队列清空前不再分配新请求，确保安全切换。 */
    DRAIN_AND_SYNC;

    /**
     * 判断当前模式是否仍依赖性能链路（DUAL_WRITE/ PERFORMANCE）。
     *
     * @return true 表示仍需维护 Redis 视图
     */
    public boolean isPerformanceLike() {
        return this == PERFORMANCE || this == DUAL_WRITE;
    }
}

