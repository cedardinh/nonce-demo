package com.work.nonce.demo.service;

import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import com.work.nonce.demo.config.NonceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * demo 级历史数据清理：
 * - 默认关闭（生产建议用分区/归档）
 * - 仅清理 ACCEPTED/RECYCLABLE/历史 USED（兼容），不触碰 RESERVED/PENDING
 */
@Component
@ConditionalOnProperty(prefix = "nonce", name = "cleanup-enabled", havingValue = "true")
public class NonceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NonceCleanupJob.class);

    private final NonceProperties properties;
    private final NonceAllocationMapper allocationMapper;

    public NonceCleanupJob(NonceProperties properties, NonceAllocationMapper allocationMapper) {
        this.properties = properties;
        this.allocationMapper = allocationMapper;
    }

    @Scheduled(fixedDelayString = "${nonce.cleanup-interval-ms:3600000}")
    public void runOnce() {
        int days = Math.max(1, properties.getHotRetentionDays());
        int limit = Math.max(100, properties.getCleanupBatchSize());
        Instant before = Instant.now().minus(days, ChronoUnit.DAYS);

        int deleted = allocationMapper.deleteOldFinalizedAllocations(before, limit);
        if (deleted > 0) {
            log.info("nonce cleanup deleted {} rows before {}", deleted, before);
        }
    }
}

