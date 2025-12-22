package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Nonce 分配记录表 Mapper
 */
public interface NonceAllocationMapper extends BaseMapper<NonceAllocationEntity> {

    /**
     * 回收过期的 RESERVED 状态记录
     */
    @Update("UPDATE submitter_nonce_allocation " +
            "SET status = 'RECYCLABLE', lock_owner = NULL, locked_until = NULL, updated_at = #{now}, reason = '超时回收' " +
            "WHERE submitter = #{submitter} " +
            "AND status = 'RESERVED' " +
            "AND (tx_hash IS NULL OR tx_hash = '') " +
            "AND locked_until IS NOT NULL " +
            "AND locked_until < #{expireBefore}")
    int recycleExpiredReservations(@Param("submitter") String submitter,
                                   @Param("expireBefore") Instant expireBefore,
                                   @Param("now") Instant now);

    /**
     * 查找最小的 RECYCLABLE 记录
     */
    @Select("SELECT id, submitter, nonce, status, lock_owner, locked_until, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE submitter = #{submitter} AND status = 'RECYCLABLE' " +
            "ORDER BY nonce ASC LIMIT 1")
    NonceAllocationEntity findOldestRecyclable(@Param("submitter") String submitter);

    /**
     * 查找指定 submitter 和 nonce 的记录
     */
    @Select("SELECT id, submitter, nonce, status, lock_owner, locked_until, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE submitter = #{submitter} AND nonce = #{nonce}")
    NonceAllocationEntity findBySubmitterAndNonce(@Param("submitter") String submitter, @Param("nonce") Long nonce);

    /**
     * 插入或更新 nonce 为 RESERVED 状态（使用 ON CONFLICT）
     * 注意：PostgreSQL 的 ON CONFLICT 语法，WHERE 子句在 DO UPDATE 中
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO submitter_nonce_allocation(submitter, nonce, status, lock_owner, locked_until, updated_at, created_at) " +
            "VALUES(#{submitter}, #{nonce}, 'RESERVED', #{lockOwner}, #{lockedUntil}, #{updatedAt}, #{createdAt}) " +
            "ON CONFLICT(submitter, nonce) " +
            "DO UPDATE SET status = 'RESERVED', lock_owner = #{lockOwner}, locked_until = #{lockedUntil}, updated_at = #{updatedAt} " +
            "WHERE submitter_nonce_allocation.status != 'USED'")
    int reserveNonce(@Param("submitter") String submitter,
                     @Param("nonce") Long nonce,
                     @Param("lockOwner") String lockOwner,
                     @Param("lockedUntil") Instant lockedUntil,
                     @Param("updatedAt") Instant updatedAt,
                     @Param("createdAt") Instant createdAt);

    /**
     * 查询被回收的记录（用于日志）
     */
    @Select("SELECT id, submitter, nonce, status, lock_owner, locked_until, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE submitter = #{submitter} " +
            "AND status = 'RESERVED' " +
            "AND (tx_hash IS NULL OR tx_hash = '') " +
            "AND locked_until IS NOT NULL " +
            "AND locked_until < #{expireBefore}")
    List<NonceAllocationEntity> findExpiredReservations(@Param("submitter") String submitter,
                                                         @Param("expireBefore") Instant expireBefore);

    /**
     * 标记为已提交（绑定 txHash，但仍处于 RESERVED，以 receipt 为准驱动后续 markUsed）
     */
    @Update("UPDATE submitter_nonce_allocation " +
            "SET tx_hash = #{txHash}, lock_owner = NULL, locked_until = NULL, updated_at = #{now} " +
            "WHERE submitter = #{submitter} AND nonce = #{nonce} AND status = 'RESERVED'")
    int markSubmitted(@Param("submitter") String submitter,
                      @Param("nonce") Long nonce,
                      @Param("txHash") String txHash,
                      @Param("now") Instant now);

    /**
     * 查询一批已提交但未落 receipt 的 reservation（status=RESERVED 且 txHash 不为空）
     */
    @Select("SELECT id, submitter, nonce, status, lock_owner, locked_until, tx_hash, reason, updated_at, created_at " +
            "FROM submitter_nonce_allocation " +
            "WHERE status = 'RESERVED' " +
            "AND tx_hash IS NOT NULL AND tx_hash != '' " +
            "ORDER BY updated_at ASC " +
            "LIMIT #{limit}")
    List<NonceAllocationEntity> listSubmittedReservations(@Param("limit") int limit);
}

