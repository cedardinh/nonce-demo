package com.work.nonce.core.engine.performance;

import java.time.Instant;
import java.util.UUID;

/**
 * Redis → Postgres 的刷盘事件，持久化在队列中。
 */
public class PerformanceFlushEvent {

    public enum Type {
        /** 记录 RESERVE 行为，需在 DB 中创建/更新 reserved 记录。 */
        RESERVE,
        /** 标记 USED 行为，携带 txHash。 */
        MARK_USED,
        /** 标记 RECYCLABLE 行为，携带回收原因。 */
        MARK_RECYCLABLE
    }

    /** 事件唯一 ID，便于排查。 */
    private final String id;
    /** submitter 标识，用于定位状态行。 */
    private final String submitter;
    /** 目标 nonce 数值。 */
    private final long nonce;
    /** 事件类型。 */
    private final Type type;
    /** 链上交易哈希，仅在 MARK_USED 有效。 */
    private final String txHash;
    /** 回收原因，仅在 MARK_RECYCLABLE 有效。 */
    private final String reason;
    /** 是否需要推进 nextLocalNonce（新号才为 true）。 */
    private final boolean incrementsState;
    /** 如果需要推进，本次应写入的 nextLocalNonce。 */
    private final long nextLocalNonce;
    /** 事件创建时间戳（毫秒）。 */
    private final long createdAt;

    /**
     * 全量构造方法，供序列化/反序列化使用。
     */
    public PerformanceFlushEvent(String id,
                                 String submitter,
                                 long nonce,
                                 Type type,
                                 String txHash,
                                 String reason,
                                 boolean incrementsState,
                                 long nextLocalNonce,
                                 long createdAt) {
        this.id = id;
        this.submitter = submitter;
        this.nonce = nonce;
        this.type = type;
        this.txHash = txHash;
        this.reason = reason;
        this.incrementsState = incrementsState;
        this.nextLocalNonce = nextLocalNonce;
        this.createdAt = createdAt;
    }

    /**
     * 构造 RESERVE 事件，携带是否需要推进 nextLocalNonce。
     */
    public static PerformanceFlushEvent reserve(PerformanceNonceEngine.AllocationSnapshot snapshot) {
        long nextLocalNonce = snapshot.isIncrementsState() ? snapshot.getNonce() + 1 : 0;
        return new PerformanceFlushEvent(UUID.randomUUID().toString(),
                snapshot.getSubmitter(),
                snapshot.getNonce(),
                Type.RESERVE,
                null,
                null,
                snapshot.isIncrementsState(),
                nextLocalNonce,
                Instant.now().toEpochMilli());
    }

    /** 构造 MARK_USED 事件，带上 txHash。 */
    public static PerformanceFlushEvent markUsed(PerformanceNonceEngine.AllocationSnapshot snapshot) {
        return new PerformanceFlushEvent(UUID.randomUUID().toString(),
                snapshot.getSubmitter(),
                snapshot.getNonce(),
                Type.MARK_USED,
                snapshot.getTxHash(),
                null,
                false,
                0,
                Instant.now().toEpochMilli());
    }

    /** 构造 MARK_RECYCLABLE 事件，带上 reason。 */
    public static PerformanceFlushEvent markRecyclable(PerformanceNonceEngine.AllocationSnapshot snapshot) {
        return new PerformanceFlushEvent(UUID.randomUUID().toString(),
                snapshot.getSubmitter(),
                snapshot.getNonce(),
                Type.MARK_RECYCLABLE,
                null,
                snapshot.getReason(),
                false,
                0,
                Instant.now().toEpochMilli());
    }

    /** @return 事件唯一 ID */
    public String getId() {
        return id;
    }

    /** @return submitter 标识 */
    public String getSubmitter() {
        return submitter;
    }

    /** @return nonce 数值 */
    public long getNonce() {
        return nonce;
    }

    /** @return 事件类型 */
    public Type getType() {
        return type;
    }

    /** @return 链上交易哈希 */
    public String getTxHash() {
        return txHash;
    }

    /** @return 回收原因 */
    public String getReason() {
        return reason;
    }

    /** @return 是否需要推进 nextLocalNonce */
    public boolean isIncrementsState() {
        return incrementsState;
    }

    /** @return 若需要推进，新的 nextLocalNonce 值 */
    public long getNextLocalNonce() {
        return nextLocalNonce;
    }

    /** @return 事件创建时间（毫秒） */
    public long getCreatedAt() {
        return createdAt;
    }
}

