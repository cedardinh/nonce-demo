package com.work.nonce.demo.worker;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.TransactionReceipt;
import com.work.nonce.demo.config.ConfirmationsProperties;
import com.work.nonce.demo.service.ReceiptFinalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ConfirmationsProperties confirmationsProperties;
    private final ReceiptFinalizer receiptFinalizer;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ReceiptPoller(NonceRepository nonceRepository,
                         ChainClient chainClient,
                         ConfirmationsProperties confirmationsProperties,
                         ReceiptFinalizer receiptFinalizer) {
        this.nonceRepository = nonceRepository;
        this.chainClient = chainClient;
        this.confirmationsProperties = confirmationsProperties;
        this.receiptFinalizer = receiptFinalizer;
    }

    @PostConstruct
    public void init() {
        int workers = Math.max(1, confirmationsProperties.getReceiptWorkers());
        ThreadFactory tf = r -> {
            Thread t = new Thread(r);
            t.setName("receipt-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(workers, tf);
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Scheduled(fixedDelayString = "#{@confirmationsProperties.receiptPollInterval.toMillis()}")
    public void pollReceipts() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int limit = Math.max(1, confirmationsProperties.getBatchSize());
            List<NonceAllocation> submitted = nonceRepository.listSubmittedReservations(limit);
            if (submitted.isEmpty()) {
                return;
            }

            Instant staleBefore = Instant.now().minus(confirmationsProperties.getStaleReceiptTimeout());
            boolean fetchUponEntry = confirmationsProperties.isFetchReceiptUponEntry();

            for (NonceAllocation a : submitted) {
                if (!fetchUponEntry && a.getUpdatedAt().isAfter(staleBefore)) {
                    continue;
                }
                String txHash = a.getTxHash();
                if (txHash == null || txHash.trim().isEmpty()) {
                    continue;
                }

                executor.execute(() -> {
                    Optional<TransactionReceipt> receiptOpt;
                    try {
                        receiptOpt = chainClient.getTransactionReceipt(txHash);
                    } catch (Exception e) {
                        log.warn("Receipt query failed. submitter={} nonce={} txHash={} err={}",
                                a.getSubmitter(), a.getNonce(), txHash, e.getMessage());
                        return;
                    }
                    if (!receiptOpt.isPresent()) {
                        return;
                    }
                    TransactionReceipt receipt = receiptOpt.get();
                    try {
                        receiptFinalizer.finalizeReceipt(a, receipt);
                        log.info("Receipt arrived. submitter={} nonce={} txHash={} blockNumber={} success={}",
                                a.getSubmitter(), a.getNonce(), txHash, receipt.getBlockNumber(), receipt.isSuccess());
                    } catch (Exception e) {
                        log.warn("Finalize receipt failed. submitter={} nonce={} txHash={} err={}",
                                a.getSubmitter(), a.getNonce(), txHash, e.getMessage());
                    }
                });
            }
        } finally {
            running.set(false);
        }
    }
}


