package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/**
 * Nonce 分配记录表 Mapper
 */
public interface NonceAllocationMapper extends BaseMapper<NonceAllocationEntity> {

    /**
     * 回收过期的 RESERVED 状态记录
     */
    @Update("UPDATE submitter_nonce_allocation " +
            "SET status = 'USED', lock_owner = NULL, updated_at = #{now}, reason = '链上确认自动标记' " +
            "WHERE submitter = #{submitter} " +
            "AND status = 'RESERVED' " +
            "AND nonce <= #{confirmedNonce}")
    int markReservedAsUsedUpTo(@Param("submitter") String submitter,
                               @Param("confirmedNonce") Long confirmedNonce,
                               @Param("now") Instant now);

    /** 查找 submitter 下 nonce 最小的 RECYCLABLE 记录。 */
    @Select("SELECT id, submitter, nonce, status, lock_owner, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE submitter = #{submitter} AND status = 'RECYCLABLE' " +
            "ORDER BY nonce ASC LIMIT 1")
    NonceAllocationEntity findOldestRecyclable(@Param("submitter") String submitter);

    /** 查找指定 submitter + nonce 的单条记录。 */
    @Select("SELECT id, submitter, nonce, status, lock_owner, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE submitter = #{submitter} AND nonce = #{nonce}")
    NonceAllocationEntity findBySubmitterAndNonce(@Param("submitter") String submitter, @Param("nonce") Long nonce);

    /**
     * 插入或更新 nonce 为 RESERVED 状态（使用 ON CONFLICT）
     * 注意：PostgreSQL 的 ON CONFLICT 语法，WHERE 子句在 DO UPDATE 中
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO submitter_nonce_allocation(submitter, nonce, status, lock_owner, updated_at, created_at) " +
            "VALUES(#{submitter}, #{nonce}, 'RESERVED', #{lockOwner}, #{updatedAt}, #{createdAt}) " +
            "ON CONFLICT(submitter, nonce) " +
            "DO UPDATE SET status = 'RESERVED', lock_owner = #{lockOwner}, updated_at = #{updatedAt} " +
            "WHERE submitter_nonce_allocation.status != 'USED'")
    int reserveNonce(@Param("submitter") String submitter,
                     @Param("nonce") Long nonce,
                     @Param("lockOwner") String lockOwner,
                     @Param("updatedAt") Instant updatedAt,
                     @Param("createdAt") Instant createdAt);
}

