package com.work.nonce.core.cache;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地缓存条目：保存一个已从 DB 预分配的 nonce 区间 [start, endExclusive)。
 *
 * 注意：该对象可能被并发访问，使用 AtomicLong 保证 getAndIncrement 的原子性。
 */
public class NonceCacheEntry {

    private final Instant cachedTime;
    private final AtomicLong nextNonce;
    private final long endExclusive;

    public NonceCacheEntry(Instant cachedTime, long startNonce, long endExclusive) {
        this.cachedTime = cachedTime;
        this.nextNonce = new AtomicLong(startNonce);
        this.endExclusive = endExclusive;
    }

    public Instant getCachedTime() {
        return cachedTime;
    }

    public long getNextNonce() {
        return nextNonce.get();
    }

    public long getEndExclusive() {
        return endExclusive;
    }

    /**
     * 返回当前 nonce 并递增到下一个候选值。
     */
    public long getAndIncrementNonce() {
        return nextNonce.getAndIncrement();
    }

    public boolean isExhausted() {
        return nextNonce.get() >= endExclusive;
    }
}
