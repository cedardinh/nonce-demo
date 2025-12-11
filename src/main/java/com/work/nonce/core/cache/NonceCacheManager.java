package com.work.nonce.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.work.nonce.core.config.NonceConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * 封装 Caffeine 缓存，提供超时判断与便捷访问。
 */
public class NonceCacheManager {

    private final Cache<String, NonceCacheEntry> cache;
    private final Duration cacheTimeout;
    private final boolean cacheEnabled;

    public NonceCacheManager(NonceConfig config) {
        this.cacheEnabled = config.isCacheEnabled();
        this.cacheTimeout = config.getCacheTimeout();
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.getCacheSize())
                .expireAfterWrite(config.getCacheTimeout())
                .build();
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public Optional<NonceCacheEntry> getIfPresent(String submitter) {
        if (!cacheEnabled) {
            return Optional.empty();
        }
        NonceCacheEntry entry = cache.getIfPresent(submitter);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(cacheTimeout)) {
            cache.invalidate(submitter);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void put(String submitter, NonceCacheEntry entry) {
        if (cacheEnabled) {
            cache.put(submitter, entry);
        }
    }

    public void invalidate(String submitter) {
        cache.invalidate(submitter);
    }
}
