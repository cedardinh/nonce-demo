package com.work.nonce.core.service;

import com.work.nonce.core.lock.SubmitterLockCoordinator;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNegative;

/**
 * 负责"如何为某个 submitter 分配正确的 nonce"。
 * 核心策略：Redis 锁串行化热 submitter + 数据库事务保证状态一致 + nonce 优先复用。
 */
@Service
public class NonceService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final NonceRepository nonceRepository;
    private final SubmitterLockCoordinator lockCoordinator;

    public NonceService(NonceRepository nonceRepository,
                        SubmitterLockCoordinator lockCoordinator) {
        this.nonceRepository = nonceRepository;
        this.lockCoordinator = lockCoordinator;
    }

    /**
     * 为 submitter 分配一个安全的 nonce。
     * <p>
     * 流程：
     * 1. 可选的 Redis 锁，用来减少热点 submitter 的 DB 行锁竞争
     * 2. 在事务语义下锁定 submitter 状态、回收过期 RESERVED、复用 nonce 或生成新号
     * 3. 将最终结果以 RESERVED 状态返回
     * <p>
     * 注意：此方法必须在事务中执行，确保数据一致性
     * Redis锁会在事务提交后自动释放
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public NonceAllocation allocate(String submitter) {
        requireNonEmpty(submitter, "submitter");

        // 在单 submitter 维度加锁，确保同一时间只有一个线程进入分配流程。
        return lockCoordinator.executeWithLock(submitter, owner -> doAllocate(submitter, owner));
    }

    /**
     * 在数据库事务中完成“锁定状态 → 回收超时 RESERVED → 复用或生成 nonce → 标记为 RESERVED”的完整流程。
     */
    private NonceAllocation doAllocate(String submitter, String lockOwner) {
        // 在事务内锁定 submitter 状态行
        SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
        // 将链上已确认的 nonce 与本地状态对齐，并标记已使用的记录
        syncWithChain(submitter, state);
        // 查找可复用的nonce或生成新的nonce
        long targetNonce = findOrGenerateNonce(submitter, state);
        // 将 nonce 标记为 RESERVED（可能是新建，也可能是复用）。
        return nonceRepository.reserveNonce(submitter, targetNonce, lockOwner);
    }

    /**
     * 查找可复用的nonce或生成新的nonce
     */
    private long findOrGenerateNonce(String submitter, SubmitterNonceState state) {
        Optional<NonceAllocation> reusable = nonceRepository.findOldestRecyclable(submitter);
        if (reusable.isPresent()) {
            return reusable.get().getNonce();
        }

        // 没有可复用的 nonce，发下一顺位的本地计数
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
    public void markUsed(String submitter, long nonce, String txHash) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(txHash, "txHash");
        requireNonNegative(nonce, "nonce");

        nonceRepository.markUsed(submitter, nonce, txHash);
    }

    /**
     * 标记 nonce 为可回收（不过业务本身的异常不确定在异常发生之前是否完成了nonce的消耗，后续需要需要跟链上最新的nonce对比进行纠偏）
     * <p>
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
     * 将链上已确认的 nonce 与本地状态对齐，并标记已使用的记录
     *
     * @param submitter
     * @param state
     */
    private void syncWithChain(String submitter, SubmitterNonceState state) {
        long chainNonce = queryLatestChainNonce(submitter);
        if (chainNonce >= 0) {
            nonceRepository.confirmReservedWithChain(submitter, chainNonce);
            if (chainNonce > state.getLastChainNonce()) {
                state.setLastChainNonce(chainNonce);
                nonceRepository.updateLastChainNonce(submitter, chainNonce);
            }
        }
    }

    /**
     * 子类可覆盖此方法，以自定义“查询链上最新已确认 nonce” 的方式。
     * 默认返回 -1，表示暂不介入对账。
     */
    private long queryLatestChainNonce(String submitter) {
        //todo 查询链上最新的nonce
        return -1L;
    }
}

