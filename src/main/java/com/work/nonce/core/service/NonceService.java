package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.exception.LeaseNotOwnedException;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.repository.NonceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.lang.NonNull;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNegative;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 负责"如何为某个 signer 分配正确的 nonce"。
 * 这里的实现遵循 README 所述流程：Postgres 事务 + gap nonce 复用。
 * <p>
 * 事务边界：所有数据库操作都在事务中执行，确保数据一致性
 */
@Service
public class NonceService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final int CAS_MAX_ATTEMPTS = 5;

    private final NonceRepository nonceRepository;
    private final NonceConfig config;
    private final LeaseService leaseService;
    private final TransactionTemplate txTemplate;

    public NonceService(NonceRepository nonceRepository,
                        NonceConfig config,
                        LeaseService leaseService,
                        @NonNull PlatformTransactionManager transactionManager) {
        this.nonceRepository = nonceRepository;
        this.config = config;
        this.leaseService = leaseService;
        requireNonNull(transactionManager, "transactionManager");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        this.txTemplate = template;
    }
    

    private <T> T withLeaseRetry(Supplier<T> attemptWork) {
        // 有限次重试：只覆盖“短暂竞争”场景（如 failover 切换瞬间）
        // 若 lease 被其他节点稳定持有/续约，长时间 sleep 也不会成功，应尽快返回可重试错误交给上游退避。
        final int maxAttempts = 3;
        long backoffMs = 8L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return attemptWork.get();
            } catch (LeaseNotOwnedException ex) {
                if (attempt == maxAttempts) throw ex;
                // 0.7x ~ 1.3x：轻量抖动，避免同步冲撞
                double factor = 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
                long sleepMs = Math.max(1L, (long) (backoffMs * factor));
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                // 8ms -> 16ms -> 32ms（上限 50ms）
                backoffMs = Math.min(backoffMs * 2, 50L);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * 为 signer 分配一个安全的 nonce。
     * <p>
     * 流程：
     * 1. 在事务语义下锁定 signer 状态、回收过期 HELD（兼容旧 RESERVED）、复用 gap nonce 或生成新号
     * 2. 将最终结果以 HELD 状态返回
     * <p>
     * 注意：此方法必须在事务中执行，确保数据一致性
     */
    public NonceAllocation allocate(String signer) {
        requireNonEmpty(signer, "signer");
        return withLeaseRetry(() ->
                txTemplate.execute(status -> {
                    long token = leaseService.acquireOrThrow(signer);
                    return doAllocateWithToken(signer, token);
                })
        );
    }

    private NonceAllocation doAllocateWithToken(String signer, long token) {
        // 回收超时未处理的 HELD（释放为 RELEASED 供复用）；带 fencing token，确保只有当前 lease owner 才能执行该状态推进
        nonceRepository.recycleExpiredReservationsFenced(signer, config.getReservedTimeout(), token);

        Instant now = Instant.now();
        Instant lockedUntil = now.plus(config.getLockTtl());

        // 1) gap nonce 优先：claim 最小 RELEASED
        //尝试 claim 最小的 RELEASED gap nonce（更新为 HELD 并设置 locked_until），成功返回 nonce；失败返回 null。
        Long claimed = nonceRepository.claimOldestRecyclable(signer, lockedUntil, now, token);
        if (claimed != null) {
            //将 nonce 标记为 HELD（插入或更新），并写入 fencing_token。
            return nonceRepository.reserveNonceFenced(signer, claimed, config.getLockTtl(), token);
        }

        // 2) 无 gap nonce：next_local_nonce CAS（方案B）
        nonceRepository.ensureStateExists(signer);
        for (int i = 0; i < CAS_MAX_ATTEMPTS; i++) {
            Long x = nonceRepository.loadNextLocalNonce(signer);
            if (x == null) {
                nonceRepository.ensureStateExists(signer);
                continue;
            }
            Instant ts = Instant.now();
            int updated = nonceRepository.casAdvanceNextLocalNonce(signer, x, x + 1, token, ts);
            if (updated == 1) {
                return nonceRepository.reserveNonceFenced(signer, x, config.getLockTtl(), token);
            }
        }
        throw new NonceException("allocate 并发冲突重试耗尽: " + signer);
    }

    /**
     * 标记 nonce 为已使用
     * <p>
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    public void markUsed(String signer, long nonce, String txHash) {
        requireNonEmpty(signer, "signer");
        requireNonEmpty(txHash, "txHash");
        requireNonNegative(nonce, "nonce");

        withLeaseRetry(() -> txTemplate.execute(status -> {
            long token = leaseService.acquireOrThrow(signer);
            nonceRepository.markUsedFenced(signer, nonce, txHash, token);
            return null;
        }));
    }

    /**
     * 标记 nonce 为可回收
     * <p>
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    public void markRecyclable(String signer, long nonce, String reason) {
        requireNonEmpty(signer, "signer");
        requireNonNegative(nonce, "nonce");

        // reason可以为空，但统一处理为null
        String finalReason = (reason == null) ? "" : reason;

        withLeaseRetry(() -> txTemplate.execute(status -> {
            long token = leaseService.acquireOrThrow(signer);
            nonceRepository.markRecyclableFenced(signer, nonce, finalReason, token);
            return null;
        }));
    }
}

