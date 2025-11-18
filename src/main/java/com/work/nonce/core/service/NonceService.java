package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.support.TransactionLockSynchronizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNegative;

/**
 * 负责"如何为某个 submitter 分配正确的 nonce"。
 * 这里的实现遵循 README 所述流程：Redis 锁 + Postgres 事务 + 空洞复用。
 * 
 * 事务边界：所有数据库操作都在事务中执行，确保数据一致性
 * 锁管理：Redis锁通过事务同步机制在事务提交后释放，避免并发问题
 */
@Service
public class NonceService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

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
     * 为 submitter 分配一个安全的 nonce。
     * 
     * 流程：
     * 1. 可选的 Redis 锁，用来减少热点 submitter 的 DB 行锁竞争
     * 2. 在事务语义下锁定 submitter 状态、回收过期 RESERVED、复用空洞或生成新号
     * 3. 将最终结果以 RESERVED 状态返回
     * 
     * 注意：此方法必须在事务中执行，确保数据一致性
     * Redis锁会在事务提交后自动释放
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public NonceAllocation allocate(String submitter) {
        requireNonEmpty(submitter, "submitter");

        String lockOwner = generateLockOwner();
        
        // 如果启用Redis，使用事务同步机制管理锁
        if (config.isRedisEnabled()) {
            return TransactionLockSynchronizer.executeWithLock(
                redisLockManager,
                submitter,
                lockOwner,
                config.getLockTtl(),
                config.isDegradeOnRedisFailure(),
                () -> doAllocate(submitter, lockOwner)
            );
        } else {
            return doAllocate(submitter, lockOwner);
        }
    }
    
    /**
     * 执行实际的分配逻辑
     */
    private NonceAllocation doAllocate(String submitter, String lockOwner) {
            // 在事务内锁定 submitter 状态行
            SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
            
            // 回收过期的 RESERVED 状态
            nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());

        // 查找可复用的空洞或生成新号
        long targetNonce = findOrGenerateNonce(submitter, state);
        
        // 预留 nonce（使用唯一约束防止重复分配）
        return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getLockTtl());
    }
    
    /**
     * 查找可复用的nonce或生成新的nonce
     */
    private long findOrGenerateNonce(String submitter, SubmitterNonceState state) {
            Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
            
            if (reusable.isPresent()) {
            return reusable.get().getNonce();
        }
        
                // 没有可复用的，使用新的 nonce
        long targetNonce = state.getNextLocalNonce();
                state.setNextLocalNonce(targetNonce + 1);
                state.setUpdatedAt(Instant.now());
                nonceRepository.updateState(state);
        
        return targetNonce;
    }
    
    /**
     * 生成锁持有者标识（包含机器标识和线程ID，便于追踪和调试）
     */
    private String generateLockOwner() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return String.format("%s-%d-%s", hostname, Thread.currentThread().getId(), UUID.randomUUID());
        } catch (Exception e) {
            return String.format("unknown-%d-%s", Thread.currentThread().getId(), UUID.randomUUID());
        }
    }

    /**
     * 标记 nonce 为已使用
     * 
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markUsed(String submitter, long nonce, String txHash) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(txHash, "txHash");
        requireNonNegative(nonce, "nonce");
        
        nonceRepository.markUsed(submitter, nonce, txHash);
    }

    /**
     * 标记 nonce 为可回收
     * 
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markRecyclable(String submitter, long nonce, String reason) {
        requireNonEmpty(submitter, "submitter");
        requireNonNegative(nonce, "nonce");
        
        // reason可以为空，但统一处理为null
        String finalReason = (reason == null) ? "" : reason;
        
        nonceRepository.markRecyclable(submitter, nonce, finalReason);
    }
}

