package com.work.nonce.core.repository;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 抽象出所有与数据库交互的操作，真实项目中可由 MyBatis/JPA 等实现。
 */
public interface NonceRepository {

    /**
     * 以 {@code SELECT ... FOR UPDATE} 的语义读取一行 submitter 状态，不存在则初始化。
     */
    SubmitterNonceState lockAndLoadState(String submitter);

    /**
     * 更新 submitter 的 nextLocalNonce / lastChainNonce。
     */
    void updateState(SubmitterNonceState state);

    /**
     * 回收该 submitter 下超时未处理的 RESERVED。
     *
     * @return 被回收的 allocation 列表，便于记录日志。
     */
    List<NonceAllocation> recycleExpiredReservations(String submitter, Duration reservedTimeout);

    /**
     * 查找最小的 RECYCLABLE 空洞，供复用。
     */
    Optional<NonceAllocation> findOldestRecyclable(String submitter);

    /**
     * 将 nonce 标记为 RESERVED（可能是新建，也可能是复用）。
     */
    NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration lockTtl);

    /**
     * 成功执行业务后，标记 allocation 为 USED，并附加 txHash 等信息。
     */
    void markUsed(String submitter, long nonce, String txHash, String reason);

    /**
     * 执行失败或放弃时，将 allocation 标记为 RECYCLABLE。
     */
    void markRecyclable(String submitter, long nonce, String reason);
}

