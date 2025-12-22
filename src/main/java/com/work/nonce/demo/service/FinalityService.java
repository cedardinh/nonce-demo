package com.work.nonce.demo.service;

import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.repository.entity.TransactionReceiptEntity;
import com.work.nonce.core.repository.mapper.TransactionReceiptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * confirmations 达标后的最终确定处理：
 * - 更新 tx_receipts.confirmations 和 confirmed 标记
 * - 推进 submitter 的 lastChainNonce
 *
 * 参考 FireFly 的思路：receipt 证明 nonce 已消耗，confirmations 提供最终确定性，
 * 最终确定后可安全推进 lastChainNonce 以支持灾备与对账。
 */
@Service
public class FinalityService {

    private static final Logger log = LoggerFactory.getLogger(FinalityService.class);
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final TransactionReceiptMapper receiptMapper;
    private final NonceRepository nonceRepository;

    public FinalityService(TransactionReceiptMapper receiptMapper, NonceRepository nonceRepository) {
        this.receiptMapper = receiptMapper;
        this.nonceRepository = nonceRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void applyConfirmations(TransactionReceiptEntity receipt, int confirmations, boolean confirmed) {
        Instant now = Instant.now();
        receiptMapper.updateConfirmations(
                receipt.getTxHash(),
                confirmations,
                confirmed,
                confirmed ? now : null,
                now
        );

        if (confirmed) {
            // EVM nonce 语义：nonce N 被确认意味着链上已连续确认到 N
            // 因此可以安全推进 lastChainNonce（单调递增）
            SubmitterNonceState state = nonceRepository.lockAndLoadState(receipt.getSubmitter());
            long current = state.getLastChainNonce();
            long target = receipt.getNonce() != null ? receipt.getNonce() : current;
            if (target > current) {
                state.setLastChainNonce(target);
                state.setUpdatedAt(now);
                nonceRepository.updateState(state);
                log.info("Advance lastChainNonce. submitter={} lastChainNonce={}", receipt.getSubmitter(), target);
            }
        }
    }
}


