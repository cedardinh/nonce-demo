package com.work.nonce.core.repository;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.SignerNonceState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 抽象出所有与数据库交互的操作，真实项目中可由 MyBatis/JPA 等实现。
 */
public interface NonceRepository {

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
     * 查找最小的 RELEASED 空洞，供复用（兼容旧 RECYCLABLE）。
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

