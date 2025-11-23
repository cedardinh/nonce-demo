package com.work.nonce.core.engine.reliable;

import com.work.nonce.core.engine.spi.NonceAllocationEngine;
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
import static com.work.nonce.core.support.ValidationUtils.requireValidSubmitter;

/**
 * 可靠模式的核心实现：依赖数据库事务与 Redis 锁保障串行分配。
 * <p>策略要点：submitter 级互斥 + RESERVED 回收 + 优先复用 + 链上对账。</p>
 */
@Service
public class ReliableNonceEngine implements NonceAllocationEngine {

    /** 可靠模式操作的默认事务超时时间（秒）。 */
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    /** 数据访问层，封装与 PostgreSQL 的交互。 */
    private final NonceRepository nonceRepository;
    /** submitter 粒度的分布式锁协调器。 */
    private final SubmitterLockCoordinator lockCoordinator;

    public ReliableNonceEngine(NonceRepository nonceRepository,
                               SubmitterLockCoordinator lockCoordinator) {
        this.nonceRepository = nonceRepository;
        this.lockCoordinator = lockCoordinator;
    }

    /**
     * 为指定 submitter 分配一个安全、唯一且可串行化的 nonce。
     * <p>执行顺序：</p>
     * <ol>
     *     <li>在 submitter 维度获取 Redis 锁，避免 DB 行锁激烈竞争</li>
     *     <li>锁定并加载当前状态，回收已过期 RESERVED</li>
     *     <li>优先复用 RECYCLABLE，否则推进 nextLocalNonce 生成新号</li>
     *     <li>将目标 nonce 写成 RESERVED，返回给业务</li>
     * </ol>
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public NonceAllocation allocate(String submitter) {
        requireValidSubmitter(submitter);

        // 在单 submitter 维度加锁，确保同一时间只有一个线程进入分配流程。
        return lockCoordinator.executeWithLock(submitter, owner -> doAllocate(submitter, owner));
    }

    /** 在同一事务中完成状态锁定、回收与分配动作。 */
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

    /** 优先复用 RECYCLABLE，否则推进本地计数器。 */
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

    /** 标记 nonce 为 USED，需由链上成功回执驱动。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markUsed(String submitter, long nonce, String txHash) {
        requireValidSubmitter(submitter);
        requireNonEmpty(txHash, "txHash");
        requireNonNegative(nonce, "nonce");

        nonceRepository.markUsed(submitter, nonce, txHash);
    }

    /** 将 nonce 重新标记为 RECYCLABLE，后续可被复用。 */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void markRecyclable(String submitter, long nonce, String reason) {
        requireValidSubmitter(submitter);
        requireNonNegative(nonce, "nonce");

        // reason 可以为空，这里统一替换为空串，便于存储
        String finalReason = (reason == null) ? "" : reason;

        nonceRepository.markRecyclable(submitter, nonce, finalReason);
    }

    /** 将链上已确认的 nonce 与本地状态对齐，并补齐 Tx 状态。 */
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
     * 查询链上最新确认的 nonce，默认返回 -1（即不介入对账）。
     * <p>可由业务子类覆盖，接入真实链上 RPC。</p>
     */
    private long queryLatestChainNonce(String submitter) {
        // TODO: 注入链上客户端后实现真实查询逻辑
        return -1L;
    }
}

