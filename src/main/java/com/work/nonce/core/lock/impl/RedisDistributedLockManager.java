package com.work.nonce.core.lock.impl;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.RedisLockManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * 基于 Redis 的生产级分布式锁实现
 * 使用 Lua 脚本保证原子性操作
 */
@Component
public class RedisDistributedLockManager implements RedisLockManager {

    private static final String LOCK_KEY_PREFIX = "nonce:lock:";
    
    private final StringRedisTemplate redisTemplate;
    
    // 释放锁的 Lua 脚本：只有锁的 owner 匹配时才删除
    private static final String UNLOCK_SCRIPT = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";
    
    private final DefaultRedisScript<Long> unlockScript;

    public RedisDistributedLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.unlockScript = new DefaultRedisScript<>();
        this.unlockScript.setScriptText(UNLOCK_SCRIPT);
        this.unlockScript.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(String submitter, String lockOwner, Duration ttl) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (lockOwner == null || lockOwner.trim().isEmpty()) {
            throw new IllegalArgumentException("lockOwner 不能为空");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 必须大于0");
        }

        String key = LOCK_KEY_PREFIX + submitter;
        
        try {
            // 使用 SET key value NX EX seconds 原子性设置锁
            Boolean result = redisTemplate.opsForValue().setIfAbsent(
                    key, 
                    lockOwner, 
                    ttl
            );
            
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            throw new NonceException("Redis 加锁异常: " + submitter, e);
        }
    }

    @Override
    public void unlock(String submitter, String lockOwner) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (lockOwner == null || lockOwner.trim().isEmpty()) {
            throw new IllegalArgumentException("lockOwner 不能为空");
        }

        String key = LOCK_KEY_PREFIX + submitter;
        
        try {
            // 使用 Lua 脚本保证原子性：只有 owner 匹配时才删除
            Long result = redisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(key),
                    lockOwner
            );
            
            // result == 1 表示删除成功，result == 0 表示锁不存在或 owner 不匹配
            // 这里不抛异常，因为锁可能已经过期或被其他实例释放
            if (result == null || result == 0) {
                // 可以记录日志，但不抛异常（幂等性）
            }
        } catch (Exception e) {
            // 记录日志但不抛异常，避免影响主流程
            // 生产环境应该使用日志框架记录
            throw new NonceException("Redis 释放锁异常: " + submitter, e);
        }
    }
}

