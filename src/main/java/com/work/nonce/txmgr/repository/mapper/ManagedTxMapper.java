package com.work.nonce.txmgr.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.UUID;

public interface ManagedTxMapper extends BaseMapper<ManagedTxEntity> {

    ManagedTxEntity selectByTxId(@Param("txId") UUID txId);

    ManagedTxEntity selectBySubmitterAndRequestId(@Param("submitter") String submitter, @Param("requestId") String requestId);

    int insertManagedTx(@Param("txId") UUID txId,
                        @Param("submitter") String submitter,
                        @Param("requestId") String requestId,
                        @Param("nonce") long nonce,
                        @Param("payload") String payload,
                        @Param("state") String state,
                        @Param("token") long token,
                        @Param("createdAt") Instant createdAt,
                        @Param("updatedAt") Instant updatedAt);

    int updateTxHashFenced(@Param("txId") UUID txId,
                           @Param("submitter") String submitter,
                           @Param("txHash") String txHash,
                           @Param("state") String state,
                           @Param("nextResubmitAt") Instant nextResubmitAt,
                           @Param("nodeId") String nodeId,
                           @Param("token") long token,
                           @Param("updatedAt") Instant updatedAt);

    int updateNextResubmitAtFenced(@Param("txId") UUID txId,
                                   @Param("submitter") String submitter,
                                   @Param("nextResubmitAt") Instant nextResubmitAt,
                                   @Param("lastError") String lastError,
                                   @Param("nodeId") String nodeId,
                                   @Param("token") long token,
                                   @Param("updatedAt") Instant updatedAt);

    int markStuckFenced(@Param("txId") UUID txId,
                        @Param("submitter") String submitter,
                        @Param("subState") String subState,
                        @Param("lastError") String lastError,
                        @Param("nodeId") String nodeId,
                        @Param("token") long token,
                        @Param("updatedAt") Instant updatedAt);

    int updateReceiptFenced(@Param("txId") UUID txId,
                            @Param("submitter") String submitter,
                            @Param("receipt") String receipt,
                            @Param("nodeId") String nodeId,
                            @Param("token") long token,
                            @Param("updatedAt") Instant updatedAt);

    int updateFinalStateFenced(@Param("txId") UUID txId,
                               @Param("submitter") String submitter,
                               @Param("state") String state,
                               @Param("confirmedAt") Instant confirmedAt,
                               @Param("nodeId") String nodeId,
                               @Param("token") long token,
                               @Param("updatedAt") Instant updatedAt);

    java.util.List<ManagedTxEntity> listTrackingWithoutReceipt(@Param("limit") int limit);

    java.util.List<ManagedTxEntity> listDueResubmits(@Param("limit") int limit);

    java.util.List<ManagedTxEntity> listTrackingWithReceipt(@Param("limit") int limit);

    Long countTrackingPending(@Param("submitter") String submitter);

    ManagedTxEntity selectOldestTrackingPending(@Param("submitter") String submitter);

    java.util.List<String> listSubmittersWithTrackingPending(@Param("limit") int limit);

    int updateLastReceiptCheckAtFenced(@Param("txId") UUID txId,
                                       @Param("submitter") String submitter,
                                       @Param("lastReceiptCheckAt") Instant lastReceiptCheckAt,
                                       @Param("nodeId") String nodeId,
                                       @Param("token") long token,
                                       @Param("updatedAt") Instant updatedAt);
}


