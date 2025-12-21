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
     * 回收过期的 HELD 状态记录（兼容旧 RESERVED）
     */
    int recycleExpiredReservations(@Param("signer") String signer,
                                   @Param("expireBefore") Instant expireBefore,
                                   @Param("now") Instant now);

    int recycleExpiredReservationsFenced(@Param("signer") String signer,
                                         @Param("expireBefore") Instant expireBefore,
                                         @Param("now") Instant now,
                                         @Param("fencingToken") Long fencingToken);

    /**
     * 查找最小的 RELEASED 记录（兼容旧 RECYCLABLE）
     */
    NonceAllocationEntity findOldestRecyclable(@Param("signer") String signer);

    /**
     * 查找指定 signer 和 nonce 的记录
     */
    NonceAllocationEntity findBySignerAndNonce(@Param("signer") String signer, @Param("nonce") Long nonce);

    /**
     * claim 最小 RELEASED：将其更新为 HELD 并设置 locked_until，成功返回 nonce，失败返回 null。
     */
    Long claimOldestRecyclable(@Param("signer") String signer,
                               @Param("lockedUntil") Instant lockedUntil,
                               @Param("now") Instant now,
                               @Param("fencingToken") Long fencingToken);

    /**
     * 插入或更新 nonce 为 HELD 状态（使用 ON CONFLICT）
     * 注意：PostgreSQL 的 ON CONFLICT 语法，WHERE 子句在 DO UPDATE 中
     */
    int reserveNonce(@Param("signer") String signer,
                     @Param("nonce") Long nonce,
                     @Param("lockedUntil") Instant lockedUntil,
                     @Param("updatedAt") Instant updatedAt,
                     @Param("createdAt") Instant createdAt,
                     @Param("fencingToken") Long fencingToken);

    int markUsedFenced(@Param("id") Long id,
                       @Param("txHash") String txHash,
                       @Param("now") Instant now,
                       @Param("fencingToken") Long fencingToken);

    int markRecyclableFenced(@Param("id") Long id,
                             @Param("reason") String reason,
                             @Param("now") Instant now,
                             @Param("fencingToken") Long fencingToken);

    /**
     * 查询被回收的记录（用于日志）
     */
    List<NonceAllocationEntity> findExpiredReservations(@Param("signer") String signer,
                                                         @Param("expireBefore") Instant expireBefore);
}

