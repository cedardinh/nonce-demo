package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * Nonce 分配记录表 Mapper
 */
public interface NonceAllocationMapper extends BaseMapper<NonceAllocationEntity> {

    /**
     * 处理过期的 RESERVED：转为 PENDING（隔离态），避免不确定情况下直接回收导致 nonce 复用事故。
     */
    int recycleExpiredReservations(@Param("submitter") String submitter,
                                   @Param("expireBefore") Instant expireBefore,
                                   @Param("now") Instant now);

    /**
     * 查找最小的 RECYCLABLE 记录
     */
    NonceAllocationEntity findOldestRecyclable(@Param("submitter") String submitter);

    /**
     * 原子地“抢占”最小的 RECYCLABLE 空洞并标记为 RESERVED，避免多实例竞争时重复捡洞。
     *
     * 使用 FOR UPDATE SKIP LOCKED：
     * - 多个节点同时执行时，只有一个会拿到同一条记录
     * - 其他节点会跳过被锁行，拿到下一条或返回 null
     */
    NonceAllocationEntity reserveOldestRecyclable(@Param("submitter") String submitter,
                                                 @Param("lockOwner") String lockOwner,
                                                 @Param("lockedUntil") Instant lockedUntil,
                                                 @Param("now") Instant now);

    /**
     * 预插入一段 RECYCLABLE 占位记录，保证即使进程宕机/缓存丢失也不会造成“不可见的 nonce 空洞”。
     * 插入采用 ON CONFLICT DO NOTHING（幂等）。
     */
    int insertRecyclableRange(@Param("submitter") String submitter,
                              @Param("startNonce") Long startNonce,
                              @Param("endNonceInclusive") Long endNonceInclusive,
                              @Param("now") Instant now);

    /**
     * 查找指定 submitter 和 nonce 的记录
     */
    NonceAllocationEntity findBySubmitterAndNonce(@Param("submitter") String submitter, @Param("nonce") Long nonce);

    /**
     * 插入或更新 nonce 为 RESERVED 状态（使用 ON CONFLICT）
     * 注意：PostgreSQL 的 ON CONFLICT 语法，WHERE 子句在 DO UPDATE 中
     */
    int reserveNonce(@Param("submitter") String submitter,
                     @Param("nonce") Long nonce,
                     @Param("lockOwner") String lockOwner,
                     @Param("lockedUntil") Instant lockedUntil,
                     @Param("updatedAt") Instant updatedAt,
                     @Param("createdAt") Instant createdAt);

    /**
     * 条件更新：将 nonce 标记为 ACCEPTED（不可复用）。
     * 仅允许从 RESERVED/PENDING 转入，避免并发覆盖。
     */
    int markAcceptedIfAllowed(@Param("submitter") String submitter,
                             @Param("nonce") Long nonce,
                             @Param("txHash") String txHash,
                             @Param("reason") String reason,
                             @Param("now") Instant now);

    /**
     * 条件更新：将 nonce 标记为 RECYCLABLE。
     * 仅允许从 RESERVED/PENDING 转入，避免把 ACCEPTED 回写成 RECYCLABLE。
     */
    int markRecyclableIfAllowed(@Param("submitter") String submitter,
                               @Param("nonce") Long nonce,
                               @Param("reason") String reason,
                               @Param("now") Instant now);

    /**
     * 条件更新：将 nonce 标记为 PENDING（隔离态）。
     * 仅允许从 RESERVED 转入（或已是 PENDING 视作幂等）。
     */
    int markPendingIfReserved(@Param("submitter") String submitter,
                              @Param("nonce") Long nonce,
                              @Param("reason") String reason,
                              @Param("now") Instant now);

    /**
     * 查询被回收的记录（用于日志）
     */
    List<NonceAllocationEntity> findExpiredReservations(@Param("submitter") String submitter,
                                                         @Param("expireBefore") Instant expireBefore);

    /**
     * 全局扫描：查找已过期的 RESERVED（用于定时任务批处理）。
     */
    List<NonceAllocationEntity> findExpiredReservedGlobal(@Param("expireBefore") Instant expireBefore,
                                                         @Param("limit") int limit);

    /**
     * 全局扫描：查找 stale 的 PENDING（用于定时对账/定案）。
     */
    List<NonceAllocationEntity> findStalePendingGlobal(@Param("before") Instant before,
                                                       @Param("limit") int limit);

    /**
     * 轻量更新：仍处于 PENDING 时刷新 updated_at，避免对账任务高频重复扫描同一条记录。
     */
    int touchPending(@Param("submitter") String submitter,
                     @Param("nonce") Long nonce,
                     @Param("reason") String reason,
                     @Param("now") Instant now);
}

