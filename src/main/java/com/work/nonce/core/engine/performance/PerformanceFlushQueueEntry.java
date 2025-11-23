package com.work.nonce.core.engine.performance;

/**
 * 表示处于 pending 阶段的刷盘事件，包含解析后的对象与原始 JSON。
 */
public class PerformanceFlushQueueEntry {

    /** 结构化的刷盘事件。 */
    private final PerformanceFlushEvent event;
    /** Redis 列表中保存的原始字符串，用于 ACK/NACK。 */
    private final String rawPayload;

    /**
     * @param event      已反序列化的事件
     * @param rawPayload Redis 中的原始字符串
     */
    public PerformanceFlushQueueEntry(PerformanceFlushEvent event, String rawPayload) {
        this.event = event;
        this.rawPayload = rawPayload;
    }

    /** @return 结构化刷盘事件 */
    public PerformanceFlushEvent getEvent() {
        return event;
    }

    /** @return 原始 JSON 字符串 */
    public String getRawPayload() {
        return rawPayload;
    }
}

