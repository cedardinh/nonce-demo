package com.work.nonce.core.repository;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SignerNonceState;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 抽象出所有与数据库交互的操作，真实项目中可由 MyBatis/JPA 等实现。
 */
public interface NonceRepository {

    /**
     * 获取/续约 signer 租约，成功返回 fencingToken；失败（非 owner 且未过期）返回 null。
     * <p>
     * 语义：
     * - 不存在：插入（token=1）
     * - 已过期：抢占（token=token+1，owner=调用方）
     * - 未过期且 owner=调用方：续约（token 不变）
     */
    Long acquireOrRenewLease(String signer, String ownerId, Duration leaseTtl);

    /**
     * 确保 signer_nonce_state 存在（并发安全的 INSERT IF NOT EXISTS）。
     */
    void ensureStateExists(String signer);

    /**
     * 读取当前 next_local_nonce（无行锁）。
     */
    Long loadNextLocalNonce(String signer);

    /**
     * CAS 推进 next_local_nonce：expected -> newValue，并推进 fencing_token。
     *
     * @return 影响行数（1=成功，0=并发冲突或旧 token）
     */
    int casAdvanceNextLocalNonce(String signer, long expected, long newValue, long fencingToken, Instant now);

    /**
     * 尝试 claim 最小的 RELEASED gap nonce（更新为 HELD 并设置 locked_until），成功返回 nonce；失败返回 null。
     */
    Long claimOldestRecyclable(String signer, Instant lockedUntil, Instant now, long fencingToken);

    /**
     * 将 nonce 标记为 HELD（插入或更新），并写入 fencing_token。
     */
    NonceAllocation reserveNonceFenced(String signer, long nonce, Duration lockTtl, long fencingToken);

    /**
     * fenced 回收超时 HELD。
     *
     * @return 被回收的 allocation 列表（用于日志/观测）
     */
    List<NonceAllocation> recycleExpiredReservationsFenced(String signer, Duration reservedTimeout, long fencingToken);

    void markUsedFenced(String signer, long nonce, String txHash, long fencingToken);

    void markRecyclableFenced(String signer, long nonce, String reason, long fencingToken);

    /**
     * 以 {@code SELECT ... FOR UPDATE} 的语义读取一行 signer 状态，不存在则初始化。
     */
    SignerNonceState lockAndLoadState(String signer);

    /**
     * 更新 signer 的 nextLocalNonce。
     */
    void updateState(SignerNonceState state);

    /**
     * 回收该 signer 下超时未处理的 HELD（兼容旧 RESERVED）。
     *
     * @return 被回收的 allocation 列表，便于记录日志。
     */
    List<NonceAllocation> recycleExpiredReservations(String signer, Duration reservedTimeout);

    /**
     * 查找最小的 RELEASED gap nonce，供复用（兼容旧 RECYCLABLE）。
     */
    Optional<NonceAllocation> findOldestRecyclable(String signer);

    /**
     * 将 nonce 标记为 HELD（可能是新建，也可能是复用）。
     */
    NonceAllocation reserveNonce(String signer, long nonce, Duration lockTtl);

    /**
     * 成功执行业务后，标记 allocation 为 CONSUMED，并附加 txHash 等信息。
     */
    void markUsed(String signer, long nonce, String txHash);

    /**
     * 执行失败或放弃时，将 allocation 标记为 RELEASED。
     */
    void markRecyclable(String signer, long nonce, String reason);
}

