package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.exception.NonceRetryableException;
import com.work.nonce.core.repository.NonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNegative;

/**
 * 负责"如何为某个 submitter 分配正确的 nonce"。
 *
 * 当前实现采用：乐观并发控制（CAS） + 唯一约束（UNIQUE(submitter, nonce)）+ 退避重试 + 短事务。
 *
 * 关键点：
 * 1. 同一 submitter 的“发新号”通过 CAS 更新 submitter 状态游标来保证不重号；
 * 2. allocation 表通过唯一约束保证不会出现重复占号；
 * 3. 复用空洞（RECYCLABLE）使用条件更新（CAS）抢占；
 * 4. 冲突时抛出可重试异常，由外层退避重试吸收，避免长时间阻塞。
 */
@Service
public class NonceService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(NonceService.class);

    private final NonceRepository nonceRepository;
    private final NonceConfig config;

    // 通过 Spring 代理调用事务方法（避免 self-invocation 导致 @Transactional 失效）
    @Autowired
    @Lazy
    private NonceService self;

    public NonceService(NonceRepository nonceRepository,
                        NonceConfig config) {
        this.nonceRepository = nonceRepository;
        this.config = config;
    }

    /**
     * 为 submitter 分配一个安全的 nonce。
     *
     * 流程（每次尝试都是一个短事务）：
     * 1. 回收该 submitter 下过期的 RESERVED -> RECYCLABLE；
     * 2. 优先抢占最小的 RECYCLABLE（CAS）；
     * 3. 若无空洞，则 CAS 推进 submitter 的 next_local_nonce 并占号；
     * 4. 若发生并发冲突（CAS 失败/唯一约束冲突），抛出可重试异常并退避重试。
     */
    public NonceAllocation allocate(String submitter) {
        requireNonEmpty(submitter, "submitter");

        String lockOwner = generateLockOwner();

        // lockOwner 语义：本次 reservation 的持有者标识（用于回写时校验“我是否仍持有该 reservation”）
        return allocateWithDbRetry(submitter, lockOwner);
    }
    
    /**
     * 外层重试：每次尝试都必须是独立事务（短事务）
     */
    private NonceAllocation allocateWithDbRetry(String submitter, String lockOwner) {
        RuntimeException last = null;
        int maxAttempts = Math.max(1, config.getAllocateMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return self.allocateOnceTx(submitter, lockOwner);
            } catch (PessimisticLockingFailureException e) {
                last = e;
                if (attempt == 1 || attempt % 5 == 0) {
                    log.debug("DB concurrency contention for submitter={}, attempt={}/{}", submitter, attempt, maxAttempts);
                }
                sleepWithBackoff(attempt);
            } catch (NonceRetryableException e) {
                last = e;
                if (attempt == 1 || attempt % 5 == 0) {
                    log.debug("Nonce optimistic conflict for submitter={}, attempt={}/{}", submitter, attempt, maxAttempts);
                }
                sleepWithBackoff(attempt);
            } catch (RuntimeException e) {
                // 某些驱动/场景下可能不会被翻译成上述异常，保底判断一次
                if (isLikelyLockFailure(e)) {
                    last = e;
                    if (attempt == 1 || attempt % 5 == 0) {
                        log.debug("DB concurrency contention (untranslated) for submitter={}, attempt={}/{}", submitter, attempt, maxAttempts);
                    }
                    sleepWithBackoff(attempt);
                } else {
                    throw e;
                }
            }
        }
        throw new NonceException("nonce 分配繁忙（并发冲突过高），请稍后重试", last);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public NonceAllocation allocateOnceTx(String submitter, String lockOwner) {
        // 乐观并发：无行锁。冲突交由外层退避重试吸收。
        SubmitterNonceState state = nonceRepository.loadOrCreateState(submitter);

        // 回收过期的 RESERVED（reservedUntil/DB列 locked_until 语义：reservation 过期时间点）
        nonceRepository.recycleExpiredReservations(submitter, config.getReservedTimeout());

        // 先尝试复用空洞（乐观抢占）
        Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
        if (reusable.isPresent()) {
            long targetNonce = reusable.get().getNonce();
            return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner, config.getReservedTimeout());
        }

        // 没有可复用的，使用新的 nonce：CAS 更新 state.next_local_nonce
        long expected = state.getNextLocalNonce();
        long newNext = expected + 1;
        boolean updated = nonceRepository.casUpdateNextLocalNonce(submitter, expected, newNext);
        if (!updated) {
            throw new NonceRetryableException("next_local_nonce 并发冲突，请重试: " + submitter);
        }
        // reserve 新号：唯一约束冲突时会抛可重试异常
        return nonceRepository.reserveNonce(submitter, expected, lockOwner, config.getReservedTimeout());
    }
    
    // findOrGenerateNonce 已被乐观并发控制逻辑替代：空洞抢占 + state CAS 更新

    private void sleepWithBackoff(int attempt) {
        long backoffBaseMs = Math.max(1L, config.getBackoffBase().toMillis());
        long backoffMaxMs = Math.max(backoffBaseMs, config.getBackoffMax().toMillis());
        long pow = 1L << Math.min(10, Math.max(0, attempt - 1));
        long base = Math.min(backoffMaxMs, backoffBaseMs * pow);
        long jitter = ThreadLocalRandom.current().nextLong(0, backoffBaseMs);
        long sleepMs = Math.min(backoffMaxMs, base + jitter);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new NonceException("分配 nonce 等待重试被中断");
        }
    }

    private boolean isLikelyLockFailure(RuntimeException e) {
        // 兼容不同异常翻译器：优先看常见 SQLSTATE（40P01=deadlock_detected 等）
        // 注意：当前实现不依赖 SELECT FOR UPDATE，但依然可能遇到死锁/资源竞争等 DB 层并发异常。
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String msg = cur.getMessage();
            if (msg != null) {
                if (msg.contains("could not obtain lock") || msg.contains("lock_not_available") || msg.contains("deadlock detected")) {
                    return true;
                }
                if (msg.contains("55P03") || msg.contains("40P01")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
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

