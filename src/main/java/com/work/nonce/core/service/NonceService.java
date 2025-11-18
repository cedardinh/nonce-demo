package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责“如何为某个 submitter 分配正确的 nonce”。
 * 这里的实现遵循 README 所述流程：Redis 锁 + Postgres 事务 + 空洞复用。
 */
public class NonceService {

    private final NonceRepository nonceRepository;
    private final RedisLockManager redisLockManager;
    private final NonceConfig config;

    public NonceService(NonceRepository nonceRepository,
                        RedisLockManager redisLockManager,
                        NonceConfig config) {
        this.nonceRepository = nonceRepository;
        this.redisLockManager = redisLockManager;
        this.config = config;
    }

    /**
     * 为 submitter 分配一个安全的 nonce。方法内部包含：
     * 1. 可选的 Redis 锁，用来减少热点 submitter 的 DB 行锁竞争；
     * 2. 在事务语义下锁定 submitter 状态、回收过期 RESERVED、复用空洞或生成新号；
     * 3. 将最终结果以 RESERVED 状态返回。
     */
    public NonceAllocation allocate(String submitter) {
        String lockOwner = UUID.randomUUID().toString();
        boolean locked = false;
        if (config.isRedisEnabled()) {
            locked = tryRedisLock(submitter, lockOwner);
        }
        try {
            SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
            nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());

            Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
            long targetNonce;
            if (reusable.isPresent()) {
                targetNonce = reusable.get().getNonce();
            } else {
                targetNonce = state.getNextLocalNonce();
                state.setNextLocalNonce(targetNonce + 1);
                state.setUpdatedAt(Instant.now());
                nonceRepository.updateState(state);
            }
            return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getLockTtl());
        } finally {
            if (locked) {
                redisLockManager.unlock(submitter, lockOwner);
            }
        }
    }

    private boolean tryRedisLock(String submitter, String lockOwner) {
        try {
            boolean locked = redisLockManager.tryLock(submitter, lockOwner, config.getLockTtl());
            if (!locked && !config.isDegradeOnRedisFailure()) {
                throw new NonceException("Redis 加锁失败，且未开启降级");
            }
            return locked;
        } catch (Exception ex) {
            if (config.isDegradeOnRedisFailure()) {
                return false;
            }
            throw new NonceException("Redis 加锁异常", ex);
        }
    }

    public void markUsed(String submitter, long nonce, String txHash) {
        nonceRepository.markUsed(submitter, nonce, txHash);
    }

    public void markRecyclable(String submitter, long nonce, String reason) {
        nonceRepository.markRecyclable(submitter, nonce, reason);
    }
}

