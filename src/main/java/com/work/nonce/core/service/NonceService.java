package com.work.nonce.core.service;

import com.work.nonce.core.cache.NonceCacheEntry;
import com.work.nonce.core.cache.NonceCacheManager;
import com.work.nonce.core.chain.ChainNonceClient;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.support.TransactionLockSynchronizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private static final int RESERVE_RETRY_TIMES = 2;

    private final NonceRepository nonceRepository;
    private final RedisLockManager redisLockManager;
    private final NonceConfig config;
    private final NonceCacheManager cacheManager;
    private final ChainNonceClient chainNonceClient;
    private final Cache<String, Long> lastRecycleAtMs;

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
        Duration throttle = config.getRecycleThrottle();
        if (throttle == null) {
            throttle = Duration.ZERO;
        }
        this.lastRecycleAtMs = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(throttle.isZero() ? Duration.ofSeconds(30) : throttle.multipliedBy(2))
                .build();
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
        if (config.isCacheEffective(submitter) && cacheManager.isCacheEnabled()) {
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
        // 回收过期的 RESERVED 状态（多节点部署时这里会成为 DB 热点，因此做本地降频）
        maybeRecycleExpiredReservations(submitter);

        // 1) 优先原子抢占最小的 RECYCLABLE 空洞（多节点并发安全）
        Optional<NonceAllocation> recycled = nonceRepository.reserveOldestRecyclable(submitter, lockOwner, config.getReservedTimeout());
        if (recycled.isPresent()) {
            return recycled.get();
        }

        // 2) 无空洞：从 DB 原子预分配一段区间，再从区间内取一个 nonce
        long minNext = fetchChainNext(submitter, 0L);
        int batchSize = (config.isCacheEffective(submitter) && cacheManager.isCacheEnabled())
                ? (config.isSharedAccount() ? 1 : config.getPreAllocateSize())
                : 1;
        long start = nonceRepository.allocateNonceRangeStart(submitter, minNext, batchSize, Instant.now());
        // start 会在本次请求中使用；仅当 batchSize>1 且策略允许时，缓存从 start+1 开始递增
        if (batchSize > 1 && config.isCacheEffective(submitter) && cacheManager.isCacheEnabled()) {
            NonceCacheEntry entry = new NonceCacheEntry(Instant.now(), start + 1, start + batchSize);
            cacheManager.put(submitter, entry);
        }

        long targetNonce = start;
        return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getReservedTimeout());
    }

    private void maybeRecycleExpiredReservations(String submitter) {
        long now = System.currentTimeMillis();
        long throttleMs = Math.max(0L, config.getRecycleThrottle().toMillis());
        if (throttleMs <= 0L) {
            recycleExpiredReservations(submitter);
            return;
        }
        Long last = lastRecycleAtMs.getIfPresent(submitter);
        if (last != null && (now - last) < throttleMs) {
            return;
        }
        lastRecycleAtMs.put(submitter, now);
        recycleExpiredReservations(submitter);
    }

    private void recycleExpiredReservations(String submitter) {
        if (!config.isSharedAccount()) {
            nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());
            return;
        }

        // sharedAccount 模式：回收前先查链确认，避免误回收导致 nonce 重用/nonce too low
        long chainNext = fetchChainNext(submitter, -1L);
        if (chainNext < 0) {
            // 链上不可用时回退到原始回收策略（保守：仍会回收，业务侧可通过配置关闭 sharedAccount）
            nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());
            return;
        }

        List<com.work.nonce.core.model.NonceAllocation> expired = nonceRepository.findExpiredReservations(submitter, Instant.now());
        for (com.work.nonce.core.model.NonceAllocation allocation : expired) {
            long nonce = allocation.getNonce();
            if (chainNext > nonce) {
                // 链上 next 已推进，说明该 nonce 大概率已被消耗（本系统或外部系统）
                nonceRepository.markUsed(submitter, nonce, null, "expired reserved but chainNext=" + chainNext);
            } else {
                // 不确定是否已提交/是否会被替换，进入 PENDING 隔离态，由对账任务最终判定
                nonceRepository.markPending(submitter, nonce, "超时进入PENDING(sharedAccount)");
            }
        }
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
        if (candidate >= entry.getEndExclusive()) {
            cacheManager.invalidate(submitter);
            return Optional.empty();
        }
        for (int i = 0; i < RESERVE_RETRY_TIMES; i++) {
            try {
                NonceAllocation allocation = nonceRepository.reserveNonce(submitter, candidate, lockOwner, config.getReservedTimeout());
                return Optional.of(allocation);
            } catch (Exception ex) {
                if (i == RESERVE_RETRY_TIMES - 1) {
                    cacheManager.invalidate(submitter);
                    return Optional.empty();
                }
            }
        }
        cacheManager.invalidate(submitter);
        return Optional.empty();
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

    /**
     * 标记 nonce 为 PENDING（隔离态）。
     * 用于 handler 异常、不确定是否已提交、或超时回收但链上疑似占用等场景。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markPending(String submitter, long nonce, String reason) {
        requireNonEmpty(submitter, "submitter");
        requireNonNegative(nonce, "nonce");
        String finalReason = (reason == null) ? "" : reason;
        nonceRepository.markPending(submitter, nonce, finalReason);
    }
}

