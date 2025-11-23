package com.work.nonce.core.engine.performance;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 将 Redis 中的刷盘事件批量写回 PostgreSQL，并维护健康状态。
 */
@Component
public class NonceFlushWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonceFlushWorker.class);

    /** DB 访问层，负责真正的持久化写入。 */
    private final NonceRepository nonceRepository;
    /** Redis 队列，用于拉取待刷盘事件。 */
    private final NonceFlushQueue flushQueue;
    /** 性能引擎，用来回写健康状态与内存快照。 */
    private final PerformanceNonceEngine performanceEngine;
    /** 组件配置，提供批量大小/调度频率等参数。 */
    private final NonceConfig config;
    /** Spring 事务模板，确保刷盘操作具备 ACID 语义。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造函数，注入依赖并初始化事务模板。
     */
    public NonceFlushWorker(NonceRepository nonceRepository,
                            NonceFlushQueue flushQueue,
                            PerformanceNonceEngine performanceEngine,
                            NonceConfig config,
                            PlatformTransactionManager transactionManager) {
        this.nonceRepository = Objects.requireNonNull(nonceRepository, "nonceRepository");
        this.flushQueue = Objects.requireNonNull(flushQueue, "flushQueue");
        this.performanceEngine = Objects.requireNonNull(performanceEngine, "performanceEngine");
        this.config = Objects.requireNonNull(config, "config");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "#{@nonceConfig.flushInterval.toMillis()}",
            initialDelayString = "#{@nonceConfig.flushInterval.toMillis()}")
    /**
     * 按固定频率消费 Redis 队列：拉取 → 事务落库 → ACK → 更新健康状态。
     */
    public void drain() {
        if (!performanceEngine.isEnabled()) {
            return;
        }
        List<PerformanceFlushQueueEntry> entries = flushQueue.pullBatch(config.getFlushBatchSize());
        if (entries.isEmpty()) {
            return;
        }
        try {
            for (PerformanceFlushQueueEntry entry : entries) {
                transactionTemplate.execute(status -> {
                    applyEvent(entry.getEvent());
                    return null;
                });
                flushQueue.ack(entry);
                performanceEngine.onFlushCommitted(entry.getEvent());
            }
            performanceEngine.recordFlushSuccess(entries.size());
        } catch (Exception ex) {
            LOGGER.error("[nonce] flush worker batch failed, requeue", ex);
            flushQueue.nack(entries);
            performanceEngine.recordFlushFailure(ex);
        }
    }

    /**
     * 根据事件类型调度不同的数据库操作。
     *
     * @param event 待刷盘事件
     */
    private void applyEvent(PerformanceFlushEvent event) {
        switch (event.getType()) {
            case RESERVE:
                applyReserve(event);
                break;
            case MARK_USED:
                nonceRepository.markUsed(event.getSubmitter(), event.getNonce(), event.getTxHash());
                break;
            case MARK_RECYCLABLE:
                nonceRepository.markRecyclable(event.getSubmitter(), event.getNonce(), event.getReason());
                break;
            default:
                break;
        }
    }

    /**
     * 处理 RESERVE 事件：必要时推进 nextLocalNonce，并写入 RESERVED 记录。
     *
     * @param event RESERVE 事件
     */
    private void applyReserve(PerformanceFlushEvent event) {
        SubmitterNonceState state = nonceRepository.lockAndLoadState(event.getSubmitter());
        if (event.isIncrementsState() && event.getNextLocalNonce() > state.getNextLocalNonce()) {
            state.setNextLocalNonce(event.getNextLocalNonce());
            state.setUpdatedAt(Instant.now());
            nonceRepository.updateState(state);
        }
        nonceRepository.reserveNonce(event.getSubmitter(), event.getNonce(),
                PerformanceNonceEngine.PERFORMANCE_LOCK_OWNER);
    }
}

