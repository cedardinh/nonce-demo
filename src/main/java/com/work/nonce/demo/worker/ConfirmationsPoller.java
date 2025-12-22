package com.work.nonce.demo.worker;

import com.work.nonce.core.repository.entity.TransactionReceiptEntity;
import com.work.nonce.core.repository.mapper.TransactionReceiptMapper;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.config.ConfirmationsProperties;
import com.work.nonce.demo.service.FinalityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * confirmations 轮询：
 * - 读取 tx_receipts 中未 confirmed 的记录
 * - 获取 latest block number
 * - 计算 confirmations 并在达标后推进 lastChainNonce
 *
 * 这是 FireFly confirmationsManager 的一个最小化 Java 版本（未实现 reorg 链路追踪）。
 */
@Component
public class ConfirmationsPoller {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationsPoller.class);

    private final TransactionReceiptMapper receiptMapper;
    private final ChainClient chainClient;
    private final ConfirmationsProperties confirmationsProperties;
    private final FinalityService finalityService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ConfirmationsPoller(TransactionReceiptMapper receiptMapper,
                              ChainClient chainClient,
                              ConfirmationsProperties confirmationsProperties,
                              FinalityService finalityService) {
        this.receiptMapper = receiptMapper;
        this.chainClient = chainClient;
        this.confirmationsProperties = confirmationsProperties;
        this.finalityService = finalityService;
    }

    @Scheduled(fixedDelayString = "#{@confirmationsProperties.confirmationsPollInterval.toMillis()}")
    public void pollConfirmations() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            int limit = Math.max(1, confirmationsProperties.getBatchSize());
            List<TransactionReceiptEntity> pending = receiptMapper.listUnconfirmed(limit);
            if (pending.isEmpty()) {
                return;
            }

            long latestBlock = chainClient.queryLatestBlockNumber();
            if (latestBlock < 0) {
                return;
            }

            int required = Math.max(0, confirmationsProperties.getRequired());
            for (TransactionReceiptEntity r : pending) {
                if (r.getBlockNumber() == null) {
                    continue;
                }
                long bn = r.getBlockNumber();
                int conf = latestBlock >= bn ? (int) Math.max(0L, (latestBlock - bn + 1)) : 0;
                boolean confirmed = required == 0 || conf >= required;
                try {
                    finalityService.applyConfirmations(r, conf, confirmed);
                } catch (Exception e) {
                    log.warn("Apply confirmations failed. txHash={} err={}", r.getTxHash(), e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}


