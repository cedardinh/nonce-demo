package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.SignerNonceStateEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * signer nonce 状态表 Mapper。
 */
public interface SignerNonceStateMapper extends BaseMapper<SignerNonceStateEntity> {

    /**
     * 使用 SELECT FOR UPDATE 锁定并加载状态，不存在则返回 null。
     */
    SignerNonceStateEntity lockAndLoadBySigner(@Param("signer") String signer);

    /**
     * 插入新状态（如果不存在）。
     * 注意：PostgreSQL 的 ON CONFLICT 语法。
     */
    int insertIfNotExists(@Param("signer") String signer,
                          @Param("nextLocalNonce") Long nextLocalNonce,
                          @Param("updatedAt") Instant updatedAt,
                          @Param("createdAt") Instant createdAt);
}


