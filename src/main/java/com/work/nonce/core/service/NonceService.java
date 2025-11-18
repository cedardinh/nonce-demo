package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责"如何为某个 submitter 分配正确的 nonce"。
 * 这里的实现遵循 README 所述流程：Redis 锁 + Postgres 事务 + 空洞复用。
 */
@Service
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
     * 
     * 注意：此方法必须在事务中执行，确保数据一致性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public NonceAllocation allocate(String submitter) {
        // 参数验证
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }

        // 生成锁持有者标识（包含机器标识和线程ID，便于追踪）
        String lockOwner = generateLockOwner();
        boolean locked = false;
        
        if (config.isRedisEnabled()) {
            locked = tryRedisLock(submitter, lockOwner);
        }
        
        try {
            // 在事务内锁定 submitter 状态行
            SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
            
            // 回收过期的 RESERVED 状态
            nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());

            // 查找可复用的空洞
            Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
            long targetNonce;
            
            if (reusable.isPresent()) {
                targetNonce = reusable.get().getNonce();
            } else {
                // 没有可复用的，使用新的 nonce
                targetNonce = state.getNextLocalNonce();
                state.setNextLocalNonce(targetNonce + 1);
                state.setUpdatedAt(Instant.now());
                nonceRepository.updateState(state);
            }
            
            // 预留 nonce（使用唯一约束防止重复分配）
            return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getLockTtl());
        } finally {
            // Redis 锁在事务提交后释放（通过 TransactionSynchronizationManager）
            // 但为了兼容性，这里先释放，生产环境建议使用事务同步机制
            if (locked) {
                redisLockManager.unlock(submitter, lockOwner);
            }
        }
    }
    
    /**
     * 生成锁持有者标识
     */
    private String generateLockOwner() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname + "-" + Thread.currentThread().getId() + "-" + UUID.randomUUID().toString();
        } catch (Exception e) {
            return "unknown-" + Thread.currentThread().getId() + "-" + UUID.randomUUID().toString();
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

    /**
     * 标记 nonce 为已使用
     * 注意：此方法必须在事务中执行
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void markUsed(String submitter, long nonce, String txHash) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (txHash == null || txHash.trim().isEmpty()) {
            throw new IllegalArgumentException("txHash 不能为空");
        }
        
        nonceRepository.markUsed(submitter, nonce, txHash);
    }

    /**
     * 标记 nonce 为可回收
     * 注意：此方法必须在事务中执行
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void markRecyclable(String submitter, long nonce, String reason) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (reason == null) {
            reason = "";
        }
        
        nonceRepository.markRecyclable(submitter, nonce, reason);
    }
}

