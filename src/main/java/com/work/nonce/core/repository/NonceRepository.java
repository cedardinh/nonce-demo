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
     *
     * 注意：在“receipt 驱动 USED”的语义下，USED 应理解为“链上已消耗该 nonce（receipt 已出现）”，
     * 而不是“业务逻辑已经成功”。业务成功/失败应以 receipt.success 或业务自身结果为准。
     */
    void markUsed(String submitter, long nonce, String txHash);

    /**
     * 交易已成功提交到链上（已获得 txHash），但尚未拿到 receipt。
     *
     * 语义：仍保持 RESERVED（占用该 nonce），但写入 txHash，后续由 receipt 轮询/确认模块驱动 markUsed。
     */
    void markSubmitted(String submitter, long nonce, String txHash);

    /**
     * 执行失败或放弃时，将 allocation 标记为 RECYCLABLE。
     */
    void markRecyclable(String submitter, long nonce, String reason);

    /**
     * 查询一批“已提交但尚未标记 USED”的 reservation（即 status=RESERVED 且 txHash 不为空）。
     * 供后台 receipt 轮询任务使用。
     */
    List<NonceAllocation> listSubmittedReservations(int limit);
}

