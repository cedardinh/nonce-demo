package com.work.nonce.txmgr.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.txmgr.repository.entity.TxCompletionEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TxCompletionMapper extends BaseMapper<TxCompletionEntity> {

    List<TxCompletionEntity> listAfterSeq(@Param("afterSeq") Long afterSeq, @Param("limit") int limit);

    int insertCompletion(@Param("txId") java.util.UUID txId,
                         @Param("time") java.time.Instant time,
                         @Param("status") String status);
}


