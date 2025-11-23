package com.work.nonce.core.repository;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 定期清理历史数据，防止数据库无限增长。
 * <p>
 * 主要职责：
 * <ul>
 *     <li>清理过期的 USED 记录（根据配置的保留天数）</li>
 *     <li>回收长时间处于 RESERVED 状态的记录（可能是孤儿记录）</li>
 *     <li>记录清理统计，供监控告警使用</li>
 * </ul>
 * </p>
 */
@Component
public class NonceDataCleanupTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonceDataCleanupTask.class);

    private final NonceAllocationMapper allocationMapper;
    private final NonceConfig config;

    public NonceDataCleanupTask(NonceAllocationMapper allocationMapper, NonceConfig config) {
        this.allocationMapper = Objects.requireNonNull(allocationMapper, "allocationMapper");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 每天凌晨 3 点执行一次历史数据清理。
     * <p>避开业务高峰期，减少对生产的影响。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(timeout = 600) // 清理任务最多执行 10 分钟
    public void cleanupHistoricalData() {
        if (config.getUsedRecordRetentionDays() <= 0) {
            LOGGER.debug("[nonce-cleanup] usedRecordRetentionDays=0, skip cleanup");
            return;
        }

        Instant cutoffTime = Instant.now().minus(config.getUsedRecordRetentionDays(), ChronoUnit.DAYS);
        LOGGER.info("[nonce-cleanup] Starting cleanup for USED records older than {} (retention: {} days)",
                cutoffTime, config.getUsedRecordRetentionDays());

        try {
            // TODO: 需要在 Mapper 中实现 deleteUsedRecordsBefore 方法
            // int deleted = allocationMapper.deleteUsedRecordsBefore(cutoffTime);
            // LOGGER.info("[nonce-cleanup] Deleted {} USED records older than {}", deleted, cutoffTime);
            
            LOGGER.warn("[nonce-cleanup] deleteUsedRecordsBefore() not implemented yet, skipping USED cleanup");
        } catch (Exception e) {
            LOGGER.error("[nonce-cleanup] Failed to cleanup USED records", e);
        }
    }

    /**
     * 每小时执行一次，回收过期的 RESERVED 状态记录。
     * <p>这些记录可能是由于：</p>
     * <ul>
     *     <li>进程崩溃，未来得及标记 USED/RECYCLABLE</li>
     *     <li>业务 handler 超时但回收失败</li>
     *     <li>分布式锁过期但事务未提交</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "#{T(java.time.Duration).ofHours(1).toMillis()}")
    @Transactional(timeout = 300) // 最多执行 5 分钟
    public void reclaimStaleReserved() {
        Instant staleThreshold = Instant.now().minus(config.getStaleReservedTimeout());
        LOGGER.info("[nonce-cleanup] Starting reclaim for RESERVED records older than {} (timeout: {})",
                staleThreshold, config.getStaleReservedTimeout());

        try {
            // TODO: 需要在 Mapper 中实现 markStaleReservedAsRecyclable 方法
            // int reclaimed = allocationMapper.markStaleReservedAsRecyclable(staleThreshold, Instant.now());
            // LOGGER.info("[nonce-cleanup] Reclaimed {} stale RESERVED records", reclaimed);
            
            LOGGER.warn("[nonce-cleanup] markStaleReservedAsRecyclable() not implemented yet, skipping stale RESERVED cleanup");
        } catch (Exception e) {
            LOGGER.error("[nonce-cleanup] Failed to reclaim stale RESERVED records", e);
        }
    }
}

