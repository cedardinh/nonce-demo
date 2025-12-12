package com.work.nonce.core.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.core.repository.entity.SubmitterNonceStateEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * Submitter nonce 状态表 Mapper
 */
public interface SubmitterNonceStateMapper extends BaseMapper<SubmitterNonceStateEntity> {

    /**
     * 使用 SELECT FOR UPDATE 锁定并加载状态，不存在则返回null
     */
    SubmitterNonceStateEntity lockAndLoadBySubmitter(@Param("submitter") String submitter);

    /**
     * 插入新状态（如果不存在）
     * 注意：PostgreSQL 的 ON CONFLICT 语法
     */
    int insertIfNotExists(@Param("submitter") String submitter,
                          @Param("lastChainNonce") Long lastChainNonce,
                          @Param("nextLocalNonce") Long nextLocalNonce,
                          @Param("updatedAt") Instant updatedAt,
                          @Param("createdAt") Instant createdAt);

    /**
     * 原子预分配一段 nonce 区间 [start, start+batchSize) 并返回 start。
     * 语义：next_local_nonce = max(next_local_nonce, minNext) + batchSize
     */
    Long allocateNonceRangeStart(@Param("submitter") String submitter,
                                 @Param("minNext") Long minNext,
                                 @Param("batchSize") Integer batchSize,
                                 @Param("now") Instant now);
}

