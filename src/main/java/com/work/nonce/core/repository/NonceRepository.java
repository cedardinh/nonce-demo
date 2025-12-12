package com.work.nonce.core.repository;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SubmitterNonceState;

import java.time.Duration;
import java.time.Instant;
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
     * 查询该 submitter 下已过期（locked_until < now）的 RESERVED 记录列表。
     * 用于“回收前先查链确认”的共享账户模式。
     */
    List<NonceAllocation> findExpiredReservations(String submitter, Instant now);

    /**
     * 查找最小的 RECYCLABLE 空洞，供复用。
     */
    Optional<NonceAllocation> findOldestRecyclable(String submitter);

    /**
     * 原子抢占最小 RECYCLABLE 空洞并转为 RESERVED（多节点并发安全）。
     */
    Optional<NonceAllocation> reserveOldestRecyclable(String submitter, String lockOwner, Duration lockTtl);

    /**
     * 从 submitter 状态表中原子地预分配一段 nonce 区间，返回区间起点 startNonce。
     * 该操作会将 nextLocalNonce 一次性推进 batchSize。
     *
     * @param minNext 若 DB 当前游标落后于 minNext，则先对齐到 minNext 再分配
     */
    long allocateNonceRangeStart(String submitter, long minNext, int batchSize, Instant now);

    /**
     * 将 nonce 标记为 RESERVED（可能是新建，也可能是复用）。
     */
    NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration lockTtl);

    /**
     * 成功执行业务后，标记 allocation 为 USED，并附加 txHash 等信息。
     */
    void markUsed(String submitter, long nonce, String txHash, String reason);

    /**
     * 标记为 PENDING（隔离态），用于处理不确定是否已提交/是否被链占用的情况。
     */
    void markPending(String submitter, long nonce, String reason);

    /**
     * 执行失败或放弃时，将 allocation 标记为 RECYCLABLE。
     */
    void markRecyclable(String submitter, long nonce, String reason);
}

