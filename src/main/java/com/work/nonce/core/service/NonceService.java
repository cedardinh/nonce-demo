package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SignerNonceState;
import com.work.nonce.core.repository.NonceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNegative;

/**
 * 负责"如何为某个 signer 分配正确的 nonce"。
 * 这里的实现遵循 README 所述流程：Postgres 事务 + 空洞复用。
 * <p>
 * 事务边界：所有数据库操作都在事务中执行，确保数据一致性
 */
@Service
public class NonceService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final NonceRepository nonceRepository;
    private final NonceConfig config;

    public NonceService(NonceRepository nonceRepository,
                        NonceConfig config) {
        this.nonceRepository = nonceRepository;
        this.config = config;
    }

    /**
     * 为 signer 分配一个安全的 nonce。
     * <p>
     * 流程：
     * 1. 在事务语义下锁定 signer 状态、回收过期 HELD（兼容旧 RESERVED）、复用空洞或生成新号
     * 2. 将最终结果以 HELD 状态返回
     * <p>
     * 注意：此方法必须在事务中执行，确保数据一致性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public NonceAllocation allocate(String signer) {
        requireNonEmpty(signer, "signer");
        return doAllocate(signer);
    }

    /**
     * 执行实际的分配逻辑
     */
    private NonceAllocation doAllocate(String signer) {
        // 在事务内锁定 signer 状态行
        SignerNonceState state = nonceRepository.lockAndLoadState(signer);

        // 回收过期的 HELD 状态（兼容旧 RESERVED）
        nonceRepository.recycleExpiredReservations(signer, config.getReservedTimeout());

        // 查找可复用的空洞或生成新号
        long targetNonce = findOrGenerateNonce(signer, state);

        // 预留 nonce（使用唯一约束防止重复分配）
        return nonceRepository.reserveNonce(signer, targetNonce, config.getLockTtl());
    }

    /**
     * 查找可复用的nonce或生成新的nonce
     */
    private long findOrGenerateNonce(String signer, SignerNonceState state) {
        Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(signer);

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
     * 标记 nonce 为已使用
     * <p>
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markUsed(String signer, long nonce, String txHash) {
        requireNonEmpty(signer, "signer");
        requireNonEmpty(txHash, "txHash");
        requireNonNegative(nonce, "nonce");

        nonceRepository.markUsed(signer, nonce, txHash);
    }

    /**
     * 标记 nonce 为可回收
     * <p>
     * 注意：此方法必须在事务中执行，确保状态更新的原子性
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markRecyclable(String signer, long nonce, String reason) {
        requireNonEmpty(signer, "signer");
        requireNonNegative(nonce, "nonce");

        // reason可以为空，但统一处理为null
        String finalReason = (reason == null) ? "" : reason;

        nonceRepository.markRecyclable(signer, nonce, finalReason);
    }
}

