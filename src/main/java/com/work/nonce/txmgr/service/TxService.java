package com.work.nonce.txmgr.service;

import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.entity.TxCompletionEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.TxCompletionMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

@Service
public class TxService {

    private final ManagedTxMapper txMapper;
    private final TxCompletionMapper completionMapper;

    public TxService(ManagedTxMapper txMapper, TxCompletionMapper completionMapper) {
        this.txMapper = txMapper;
        this.completionMapper = completionMapper;
    }

    public ManagedTxEntity getById(UUID txId) {
        requireNonNull(txId, "txId");
        return txMapper.selectByTxId(txId);
    }

    public ManagedTxEntity getByRequestId(String submitter, String requestId) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(requestId, "requestId");
        return txMapper.selectBySubmitterAndRequestId(submitter, requestId);
    }

    public List<TxCompletionEntity> listCompletions(Long afterSeq, int limit) {
        int l = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return completionMapper.listAfterSeq(afterSeq, l);
    }
}


