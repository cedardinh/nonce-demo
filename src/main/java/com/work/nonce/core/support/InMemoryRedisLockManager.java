package com.work.nonce.core.support;

import com.work.nonce.core.lock.RedisLockManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 使用 ConcurrentHashMap 模拟 Redis 锁的简单实现。
 */
public class InMemoryRedisLockManager implements RedisLockManager {

    private static class LockInfo {
        String owner;
        Instant expireAt;
    }

    private final Map<String, LockInfo> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String submitter, String lockOwner, Duration ttl) {
        LockInfo newLock = new LockInfo();
        newLock.owner = lockOwner;
        newLock.expireAt = Instant.now().plus(ttl);
        final boolean[] acquired = {false};
        locks.compute(submitter, (key, existing) -> {
            Instant now = Instant.now();
            if (existing == null || existing.expireAt.isBefore(now)) {
                acquired[0] = true;
                return newLock;
            }
            if (existing.owner.equals(lockOwner)) {
                existing.expireAt = now.plus(ttl);
                acquired[0] = true;
                return existing;
            }
            acquired[0] = false;
            return existing;
        });
        return acquired[0];
    }

    @Override
    public void unlock(String submitter, String lockOwner) {
        locks.computeIfPresent(submitter, (key, existing) -> {
            if (existing.owner.equals(lockOwner) || existing.expireAt.isBefore(Instant.now())) {
                return null;
            }
            return existing;
        });
    }
}

