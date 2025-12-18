package com.work.nonce.txmgr.service.nonce;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简单 LRU nonce cache（不引入新依赖）。
 *
 * 语义对齐 111最终方案.md：
 * - cache 仅用于加速
 * - batch/事务失败必须按 submitter 清理
 */
public class NonceCache {

    public static class Entry {
        public long nextNonce;
        public Instant cachedAt;

        public Entry(long nextNonce, Instant cachedAt) {
            this.nextNonce = nextNonce;
            this.cachedAt = cachedAt;
        }
    }

    private final int maxSize;
    private final LinkedHashMap<String, Entry> lru;

    public NonceCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.lru = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > NonceCache.this.maxSize;
            }
        };
    }

    public synchronized Entry get(String submitter) {
        return lru.get(submitter);
    }

    public synchronized void put(String submitter, Entry entry) {
        lru.put(submitter, entry);
    }

    public synchronized void remove(String submitter) {
        lru.remove(submitter);
    }
}


