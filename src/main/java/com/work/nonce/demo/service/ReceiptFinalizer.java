package com.work.nonce.demo.service;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.repository.mapper.TransactionReceiptMapper;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.demo.chain.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 将 receipt “落库 + markUsed” 放在同一事务里，保证一致性。
 */
@Service
public class ReceiptFinalizer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptFinalizer.class);
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final TransactionReceiptMapper receiptMapper;
    private final NonceService nonceService;

    public ReceiptFinalizer(TransactionReceiptMapper receiptMapper, NonceService nonceService) {
        this.receiptMapper = receiptMapper;
        this.nonceService = nonceService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void finalizeReceipt(NonceAllocation allocation, TransactionReceipt receipt) {
        Instant now = Instant.now();
        try {
            // receipt 落库用于审计/对账；如果表尚未创建，降级不阻塞主流程
            receiptMapper.upsert(
                    receipt.getTxHash(),
                    allocation.getSubmitter(),
                    allocation.getNonce(),
                    receipt.getBlockNumber(),
                    receipt.getBlockHash(),
                    receipt.isSuccess(),
                    now,
                    now
            );
        } catch (Exception e) {
            log.warn("Persist tx_receipts failed (will continue markUsed). txHash={} err={}",
                    receipt.getTxHash(), e.getMessage());
        }
        nonceService.markUsed(allocation.getSubmitter(), allocation.getNonce(), receipt.getTxHash());
    }
}


