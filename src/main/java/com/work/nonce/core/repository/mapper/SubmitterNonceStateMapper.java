package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.SubmitterNonceStateEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/**
 * Submitter nonce 状态表 Mapper
 */
public interface SubmitterNonceStateMapper extends BaseMapper<SubmitterNonceStateEntity> {

    /**
     * 使用 SELECT FOR UPDATE 锁定并加载状态，不存在则返回null
     */
    @Select("SELECT submitter, last_chain_nonce, next_local_nonce, updated_at, created_at " +
            "FROM submitter_nonce_state WHERE submitter = #{submitter} FOR UPDATE")
    SubmitterNonceStateEntity lockAndLoadBySubmitter(@Param("submitter") String submitter);

    /**
     * 插入新状态（如果不存在）
     * 注意：PostgreSQL 的 ON CONFLICT 语法
     */
    @org.apache.ibatis.annotations.Insert("INSERT INTO submitter_nonce_state(submitter, last_chain_nonce, next_local_nonce, updated_at, created_at) " +
            "VALUES(#{submitter}, #{lastChainNonce}, #{nextLocalNonce}, #{updatedAt}, #{createdAt}) " +
            "ON CONFLICT(submitter) DO NOTHING")
    int insertIfNotExists(@Param("submitter") String submitter,
                          @Param("lastChainNonce") Long lastChainNonce,
                          @Param("nextLocalNonce") Long nextLocalNonce,
                          @Param("updatedAt") Instant updatedAt,
                          @Param("createdAt") Instant createdAt);
}

