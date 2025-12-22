package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.TransactionReceiptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * tx_receipts 表 Mapper（最小 upsert）。
 *
 * 表结构建议（需自行在 DB 中创建）：
 * - tx_hash TEXT PRIMARY KEY
 * - submitter TEXT NOT NULL
 * - nonce BIGINT NOT NULL
 * - block_number BIGINT NOT NULL
 * - block_hash TEXT NOT NULL
 * - success BOOLEAN NOT NULL
 * - updated_at TIMESTAMP NOT NULL
 * - created_at TIMESTAMP NOT NULL
 */
public interface TransactionReceiptMapper extends BaseMapper<TransactionReceiptEntity> {

    @Insert("INSERT INTO tx_receipts(tx_hash, submitter, nonce, block_number, block_hash, success, updated_at, created_at) " +
            "VALUES(#{txHash}, #{submitter}, #{nonce}, #{blockNumber}, #{blockHash}, #{success}, #{updatedAt}, #{createdAt}) " +
            "ON CONFLICT(tx_hash) DO UPDATE SET " +
            "submitter = EXCLUDED.submitter, " +
            "nonce = EXCLUDED.nonce, " +
            "block_number = EXCLUDED.block_number, " +
            "block_hash = EXCLUDED.block_hash, " +
            "success = EXCLUDED.success, " +
            "updated_at = EXCLUDED.updated_at")
    int upsert(@Param("txHash") String txHash,
               @Param("submitter") String submitter,
               @Param("nonce") Long nonce,
               @Param("blockNumber") Long blockNumber,
               @Param("blockHash") String blockHash,
               @Param("success") Boolean success,
               @Param("updatedAt") Instant updatedAt,
               @Param("createdAt") Instant createdAt);

    @Select("SELECT tx_hash, submitter, nonce, block_number, block_hash, success, confirmations, confirmed, confirmed_at, updated_at, created_at " +
            "FROM tx_receipts " +
            "WHERE confirmed IS DISTINCT FROM true " +
            "ORDER BY updated_at ASC " +
            "LIMIT #{limit}")
    List<TransactionReceiptEntity> listUnconfirmed(@Param("limit") int limit);

    @Update("UPDATE tx_receipts SET confirmations = #{confirmations}, confirmed = #{confirmed}, confirmed_at = #{confirmedAt}, updated_at = #{updatedAt} " +
            "WHERE tx_hash = #{txHash}")
    int updateConfirmations(@Param("txHash") String txHash,
                            @Param("confirmations") Integer confirmations,
                            @Param("confirmed") Boolean confirmed,
                            @Param("confirmedAt") Instant confirmedAt,
                            @Param("updatedAt") Instant updatedAt);
}


