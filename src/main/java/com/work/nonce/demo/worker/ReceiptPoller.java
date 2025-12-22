package com.work.nonce.demo.worker;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.TransactionReceipt;
import com.work.nonce.demo.config.NonceProperties;
import com.work.nonce.demo.service.ReceiptFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 最小闭环：后台轮询 receipt，receipt 出现后才把 nonce 标记为 USED（链已消耗）。
 *
 * 这是对 receipt-confirmations.md 里“receipt 驱动 USED”建议的最小落地。
 * confirmations/finality 可在此基础上继续扩展（引入新区块驱动与确认数统计）。
 */
@Component
public class ReceiptPoller {

    private static final Logger log = LoggerFactory.getLogger(ReceiptPoller.class);

    private final NonceRepository nonceRepository;
    private final ChainClient chainClient;
    private final NonceProperties nonceProperties;
    private final ReceiptFinalizer receiptFinalizer;

    public ReceiptPoller(NonceRepository nonceRepository,
                         ChainClient chainClient,
                         NonceProperties nonceProperties,
                         ReceiptFinalizer receiptFinalizer) {
        this.nonceRepository = nonceRepository;
        this.chainClient = chainClient;
        this.nonceProperties = nonceProperties;
        this.receiptFinalizer = receiptFinalizer;
    }

    @Scheduled(fixedDelayString = "#{@nonceProperties.receiptPollInterval.toMillis()}")
    public void pollReceipts() {
        int limit = Math.max(1, nonceProperties.getReceiptPollBatchSize());
        List<NonceAllocation> submitted = nonceRepository.listSubmittedReservations(limit);
        if (submitted.isEmpty()) {
            return;
        }

        for (NonceAllocation a : submitted) {
            String txHash = a.getTxHash();
            if (txHash == null || txHash.trim().isEmpty()) {
                continue;
            }
            Optional<TransactionReceipt> receiptOpt = chainClient.getTransactionReceipt(txHash);
            if (!receiptOpt.isPresent()) {
                continue;
            }
            TransactionReceipt receipt = receiptOpt.get();
            try {
                // receipt 出现 => nonce 已被链消耗 => 落库 receipt + 标记 USED
                receiptFinalizer.finalizeReceipt(a, receipt);
                log.info("Receipt arrived. submitter={} nonce={} txHash={} blockNumber={} success={}",
                        a.getSubmitter(), a.getNonce(), txHash, receipt.getBlockNumber(), receipt.isSuccess());
            } catch (Exception e) {
                // 幂等/并发可能导致重复标记，这里只打日志，不让 poller 停止
                log.warn("Failed to markUsed after receipt. submitter={} nonce={} txHash={} err={}",
                        a.getSubmitter(), a.getNonce(), txHash, e.getMessage());
            }
        }
    }
}


