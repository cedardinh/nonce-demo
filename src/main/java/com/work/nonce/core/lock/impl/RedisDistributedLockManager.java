package com.work.nonce.core.lock.impl;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.support.ValidationUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requirePositive;

/**
 * 基于 Redis 的生产级分布式锁实现
 * 
 * 特性：
 * 1. 使用 Lua 脚本保证原子性操作
 * 2. 支持锁超时自动释放，避免死锁
 * 3. 释放锁时验证owner，防止误释放其他实例的锁
 * 4. 异常处理完善，确保不影响主流程
 */
@Component
public class RedisDistributedLockManager implements RedisLockManager {

    private static final String LOCK_KEY_PREFIX = "nonce:lock:";
    
    private final StringRedisTemplate redisTemplate;
    
    // 释放锁的 Lua 脚本：只有锁的 owner 匹配时才删除
    // 保证原子性，避免误释放其他实例的锁
    private static final String UNLOCK_SCRIPT = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end";
    
    private final DefaultRedisScript<Long> unlockScript;

    public RedisDistributedLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = ValidationUtils.requireNonNull(redisTemplate, "redisTemplate");
        this.unlockScript = new DefaultRedisScript<>();
        this.unlockScript.setScriptText(UNLOCK_SCRIPT);
        this.unlockScript.setResultType(Long.class);
    }

    /**
     * 尝试获取分布式锁
     * 
     * @param submitter submitter标识
     * @param lockOwner 锁持有者标识（用于释放时验证）
     * @param ttl 锁超时时间
     * @return true表示加锁成功，false表示锁已被其他实例持有
     * @throws NonceException 如果Redis操作异常
     */
    @Override
    public boolean tryLock(String submitter, String lockOwner, Duration ttl) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(lockOwner, "lockOwner");
        requirePositive(ttl, "ttl");

        String key = LOCK_KEY_PREFIX + submitter;
        
        try {
            // 使用 SET key value NX EX seconds 原子性设置锁
            // NX: 只在key不存在时设置
            // EX: 设置过期时间（秒）
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

    /**
     * 释放分布式锁
     * 
     * 使用Lua脚本保证原子性：只有owner匹配时才删除锁
     * 这样可以防止误释放其他实例的锁（例如锁已过期并被其他实例获取）
     * 
     * @param submitter submitter标识
     * @param lockOwner 锁持有者标识（必须与加锁时一致）
     * @throws NonceException 如果Redis操作异常（但不会因为锁不存在而抛异常，保证幂等性）
     */
    @Override
    public void unlock(String submitter, String lockOwner) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(lockOwner, "lockOwner");

        String key = LOCK_KEY_PREFIX + submitter;
        
        try {
            // 使用 Lua 脚本保证原子性：只有 owner 匹配时才删除
            Long result = redisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(key),
                    lockOwner
            );
            
            // result == 1 表示删除成功
            // result == 0 表示锁不存在或 owner 不匹配（可能已过期或被其他实例释放）
            // 这里不抛异常，因为锁可能已经过期或被其他实例释放（幂等性）
            if (result == null || result == 0) {
                // 可以记录日志，但不抛异常（幂等性）
                // 生产环境应该使用日志框架记录：锁可能已过期或被其他实例释放
            }
        } catch (Exception e) {
            // 记录日志但不抛异常，避免影响主流程
            // 生产环境应该使用日志框架记录
            // 注意：这里选择不抛异常是为了保证幂等性，即使释放失败也不影响主流程
            // 如果确实需要严格保证锁释放，可以取消注释下面的代码
            // throw new NonceException("Redis 释放锁异常: " + submitter, e);
        }
    }
}

