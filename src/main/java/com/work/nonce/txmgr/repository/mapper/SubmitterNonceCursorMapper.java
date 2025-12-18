package com.work.nonce.txmgr.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.txmgr.repository.entity.SubmitterNonceCursorEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

public interface SubmitterNonceCursorMapper extends BaseMapper<SubmitterNonceCursorEntity> {

    SubmitterNonceCursorEntity selectBySubmitter(@Param("submitter") String submitter);

    int insertIfNotExists(@Param("submitter") String submitter,
                          @Param("nextNonce") long nextNonce,
                          @Param("fencingToken") long fencingToken,
                          @Param("updatedAt") Instant updatedAt);

    int updateNextNonceFenced(@Param("submitter") String submitter,
                              @Param("nextNonce") long nextNonce,
                              @Param("nodeId") String nodeId,
                              @Param("token") long token,
                              @Param("updatedAt") Instant updatedAt);
}


