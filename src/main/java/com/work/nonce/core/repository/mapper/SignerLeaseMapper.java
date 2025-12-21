package com.work.nonce.core.repository.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * signer_lease 表 Mapper（仅包含最小 acquire/renew/steal 能力）。
 */
public interface SignerLeaseMapper {

    /**
     * 获取/续约/抢占租约，成功返回 fencing_token；失败返回 null。
     */
    Long acquireOrRenewLease(@Param("signer") String signer,
                             @Param("ownerId") String ownerId,
                             @Param("now") Instant now,
                             @Param("expiresAt") Instant expiresAt);
}


