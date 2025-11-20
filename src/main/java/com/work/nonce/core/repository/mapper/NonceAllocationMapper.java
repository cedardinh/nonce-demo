package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import org.apache.ibatis.annotations.Param;

/**
 * Nonce 分配记录表 Mapper
 */
public interface NonceAllocationMapper extends BaseMapper<NonceAllocationEntity> {

    /**
     * 回收过期的 RESERVED 状态记录
     */
    int markReservedAsUsedUpTo(@Param("submitter") String submitter,
                               @Param("confirmedNonce") Long confirmedNonce,
                               @Param("now") java.time.Instant now);

    /** 查找 submitter 下 nonce 最小的 RECYCLABLE 记录。 */
    NonceAllocationEntity findOldestRecyclable(@Param("submitter") String submitter);

    /** 查找指定 submitter + nonce 的单条记录。 */
    NonceAllocationEntity findBySubmitterAndNonce(@Param("submitter") String submitter, @Param("nonce") Long nonce);

    /**
     * 插入或更新 nonce 为 RESERVED 状态（使用 ON CONFLICT）
     * 注意：PostgreSQL 的 ON CONFLICT 语法，WHERE 子句在 DO UPDATE 中
     */
    int reserveNonce(@Param("submitter") String submitter,
                     @Param("nonce") Long nonce,
                     @Param("lockOwner") String lockOwner,
                     @Param("updatedAt") java.time.Instant updatedAt,
                     @Param("createdAt") java.time.Instant createdAt);
}

