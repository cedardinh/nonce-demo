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
     * 回收过期的 RESERVED 状态记录
     */
    int recycleExpiredReservations(@Param("submitter") String submitter,
                                   @Param("now") Instant now);

    /**
     * 查找最小的 RECYCLABLE 记录
     */
    NonceAllocationEntity findOldestRecyclable(@Param("submitter") String submitter);

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
                     @Param("reservedUntil") Instant reservedUntil,
                     @Param("updatedAt") Instant updatedAt,
                     @Param("createdAt") Instant createdAt);

    /**
     * 乐观锁：抢占一个 RECYCLABLE nonce（CAS：要求 status='RECYCLABLE'）
     *
     * @return 1=成功抢到；0=被并发抢走/状态变化（需重试）
     */
    int claimRecyclable(@Param("submitter") String submitter,
                        @Param("nonce") Long nonce,
                        @Param("lockOwner") String lockOwner,
                        @Param("reservedUntil") Instant reservedUntil,
                        @Param("now") Instant now);

    /**
     * 查询被回收的记录（用于日志）
     */
    List<NonceAllocationEntity> findExpiredReservations(@Param("submitter") String submitter,
                                                       @Param("now") Instant now);

    /**
     * 安全地将 RESERVED -> USED：要求 lock_owner 匹配（防止超时回收/复用后旧请求迟到回写）
     */
    int markUsedIfReserved(@Param("submitter") String submitter,
                           @Param("nonce") Long nonce,
                           @Param("lockOwner") String lockOwner,
                           @Param("txHash") String txHash,
                           @Param("now") Instant now);

    /**
     * 安全地将 RESERVED -> RECYCLABLE：要求 lock_owner 匹配
     */
    int markRecyclableIfReserved(@Param("submitter") String submitter,
                                 @Param("nonce") Long nonce,
                                 @Param("lockOwner") String lockOwner,
                                 @Param("reason") String reason,
                                 @Param("now") Instant now);
}

