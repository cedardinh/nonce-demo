package com.work.nonce.txmgr.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.txmgr.repository.entity.SubmitterLeaseEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

public interface SubmitterLeaseMapper extends BaseMapper<SubmitterLeaseEntity> {

    Instant selectDbNow();

    SubmitterLeaseEntity selectForUpdate(@Param("submitter") String submitter);

    int insertNew(@Param("submitter") String submitter,
                  @Param("ownerNode") String ownerNode,
                  @Param("fencingToken") long fencingToken,
                  @Param("expiresAt") Instant expiresAt,
                  @Param("updatedAt") Instant updatedAt);

    int renew(@Param("submitter") String submitter,
              @Param("ownerNode") String ownerNode,
              @Param("fencingToken") long fencingToken,
              @Param("expiresAt") Instant expiresAt,
              @Param("updatedAt") Instant updatedAt);

    int takeover(@Param("submitter") String submitter,
                 @Param("newOwnerNode") String newOwnerNode,
                 @Param("newFencingToken") long newFencingToken,
                 @Param("expiresAt") Instant expiresAt,
                 @Param("updatedAt") Instant updatedAt);
}


