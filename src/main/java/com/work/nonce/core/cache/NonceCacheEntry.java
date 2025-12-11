package com.work.nonce.core.cache;

import java.time.Duration;
import java.time.Instant;

/**
 * 本地缓存条目，记录某个 submitter 的下一个待分配 nonce。
 */
public class NonceCacheEntry {

    private final Instant cachedTime;
    private long nextNonce;

    public NonceCacheEntry(Instant cachedTime, long nextNonce) {
        this.cachedTime = cachedTime;
        this.nextNonce = nextNonce;
    }

    public Instant getCachedTime() {
        return cachedTime;
    }

    public long getNextNonce() {
        return nextNonce;
    }

    public boolean isExpired(Duration timeout) {
        return Duration.between(cachedTime, Instant.now()).compareTo(timeout) > 0;
    }

    /**
     * 返回当前 nonce 并递增到下一个候选值。
     */
    public long getAndIncrementNonce() {
        return nextNonce++;
    }
}
