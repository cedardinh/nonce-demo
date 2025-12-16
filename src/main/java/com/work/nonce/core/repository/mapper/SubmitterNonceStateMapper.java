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
     * 加载状态，不加锁（乐观并发控制由 CAS 更新承担）
     */
    SubmitterNonceStateEntity selectBySubmitter(@Param("submitter") String submitter);

    /**
     * 乐观锁：CAS 更新 next_local_nonce
     *
     * @return 1 表示更新成功；0 表示 next_local_nonce 已被其他并发更新（需要重试）
     */
    int casUpdateNextLocalNonce(@Param("submitter") String submitter,
                                @Param("expectedNextLocalNonce") Long expectedNextLocalNonce,
                                @Param("newNextLocalNonce") Long newNextLocalNonce,
                                @Param("now") Instant now);

    /**
     * 插入新状态（如果不存在）
     * 注意：PostgreSQL 的 ON CONFLICT 语法
     */
    int insertIfNotExists(@Param("submitter") String submitter,
                          @Param("lastChainNonce") Long lastChainNonce,
                          @Param("nextLocalNonce") Long nextLocalNonce,
                          @Param("updatedAt") Instant updatedAt,
                          @Param("createdAt") Instant createdAt);
}

