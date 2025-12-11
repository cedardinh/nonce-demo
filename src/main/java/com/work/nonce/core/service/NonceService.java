package com.work.nonce.core.service;

import com.work.nonce.core.cache.NonceCacheEntry;
import com.work.nonce.core.cache.NonceCacheManager;
import com.work.nonce.core.chain.ChainNonceClient;
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
    private final NonceCacheManager cacheManager;
    private final ChainNonceClient chainNonceClient;

    public NonceService(NonceRepository nonceRepository,
                        RedisLockManager redisLockManager,
                        NonceConfig config,
                        NonceCacheManager cacheManager,
                        ChainNonceClient chainNonceClient) {
        this.nonceRepository = nonceRepository;
        this.redisLockManager = redisLockManager;
        this.config = config;
        this.cacheManager = cacheManager;
        this.chainNonceClient = chainNonceClient;
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
                () -> allocateInternal(submitter, lockOwner)
            );
        } else {
            return allocateInternal(submitter, lockOwner);
        }
    }

    /**
     * 包含缓存加速逻辑的分配实现。
     */
    private NonceAllocation allocateInternal(String submitter, String lockOwner) {
        // 优先尝试缓存路径
        if (cacheManager.isCacheEnabled()) {
            Optional<NonceAllocation> cached = tryAllocateFromCache(submitter, lockOwner);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        // 缓存 miss 或冲突失败，走完整刷新路径
        return doAllocate(submitter, lockOwner);
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
        NonceAllocation allocation = nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getReservedTimeout());

        // 更新缓存为“下一个候选值”
        if (cacheManager.isCacheEnabled()) {
            long nextCandidate = Math.max(state.getNextLocalNonce(), targetNonce + 1);
            cacheManager.put(submitter, new NonceCacheEntry(Instant.now(), nextCandidate));
        }

        return allocation;
    }
    
    /**
     * 查找可复用的nonce或生成新的nonce
     */
    private long findOrGenerateNonce(String submitter, SubmitterNonceState state) {
        Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
        if (reusable.isPresent()) {
            return reusable.get().getNonce();
        }

        // 没有可复用的，使用新的 nonce，必要时与链上对齐
        long dbNext = state.getNextLocalNonce();
        long chainNext = fetchChainNext(submitter, dbNext);
        long targetNonce = Math.max(dbNext, chainNext);

        state.setNextLocalNonce(targetNonce + 1);
        state.setUpdatedAt(Instant.now());
        nonceRepository.updateState(state);

        return targetNonce;
    }

    private long fetchChainNext(String submitter, long fallback) {
        if (!config.isChainQueryEnabled()) {
            return fallback;
        }
        long chainNonce = fallback;
        for (int i = 0; i < config.getChainQueryMaxRetries(); i++) {
            try {
                chainNonce = chainNonceClient.getLatestNonce(submitter);
                break;
            } catch (Exception e) {
                if (i == config.getChainQueryMaxRetries() - 1) {
                    // 最后一次重试失败，回退到 fallback
                    return fallback;
                }
            }
        }
        // 如果链上返回负数，视作不可用
        if (chainNonce < 0) {
            return fallback;
        }
        return chainNonce;
    }

    private Optional<NonceAllocation> tryAllocateFromCache(String submitter, String lockOwner) {
        Optional<NonceCacheEntry> entryOpt = cacheManager.getIfPresent(submitter);
        if (!entryOpt.isPresent()) {
            return Optional.empty();
        }
        NonceCacheEntry entry = entryOpt.get();
        long candidate = entry.getAndIncrementNonce();
        try {
            // 为了保证全局正确性，必须推进 submitter 的 DB 游标，避免缓存分配导致游标落后后续“倒退发号”
            SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
            if (candidate < state.getNextLocalNonce()) {
                // 缓存落后，丢弃缓存，走慢路径重新对齐
                cacheManager.invalidate(submitter);
                return Optional.empty();
            }
            state.setNextLocalNonce(candidate + 1);
            state.setUpdatedAt(Instant.now());
            nonceRepository.updateState(state);

            NonceAllocation allocation = nonceRepository.reserveNonce(submitter, candidate, lockOwner, config.getReservedTimeout());
            return Optional.of(allocation);
        } catch (Exception ex) {
            // 并发冲突/状态异常，清理缓存后走慢路径
            cacheManager.invalidate(submitter);
            return Optional.empty();
        }
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
    public void markUsed(String submitter, long nonce, String txHash, String reason) {
        requireNonEmpty(submitter, "submitter");
        requireNonNegative(nonce, "nonce");

        String finalTxHash = (txHash == null || txHash.trim().isEmpty()) ? null : txHash.trim();
        String finalReason = (reason == null) ? "" : reason;

        nonceRepository.markUsed(submitter, nonce, finalTxHash, finalReason);
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

