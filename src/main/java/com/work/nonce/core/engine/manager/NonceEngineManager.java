package com.work.nonce.core.engine.manager;

import com.work.nonce.core.engine.performance.PerformanceNonceEngine;
import com.work.nonce.core.engine.reliable.ReliableNonceEngine;
import com.work.nonce.core.engine.spi.EngineHealthSnapshot;
import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.engine.spi.NonceEngineMode;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 统一的引擎入口，基于配置与健康度在可靠/性能模式之间智能切换。
 * <p>所有门面层最终都经由该管理器调度，确保路由策略集中可控。</p>
 */
@Primary
@Service
public class NonceEngineManager implements NonceAllocationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonceEngineManager.class);

    /** 可靠模式实现，永远基于数据库进行串行分配。 */
    private final ReliableNonceEngine reliableEngine;
    /** 性能模式实现，使用 Redis + 异步刷盘。 */
    private final PerformanceNonceEngine performanceEngine;
    /** 当前运行模式，使用原子引用保证并发场景下可见性。 */
    private final AtomicReference<NonceEngineMode> mode = new AtomicReference<>(NonceEngineMode.RELIABLE);
    /** 最近一次模式切换的时间戳，便于运维查询。 */
    private final AtomicReference<Instant> modeUpdatedAt = new AtomicReference<>(Instant.now());

    public NonceEngineManager(ReliableNonceEngine reliableEngine,
                              PerformanceNonceEngine performanceEngine) {
        this.reliableEngine = Objects.requireNonNull(reliableEngine, "reliableEngine");
        this.performanceEngine = Objects.requireNonNull(performanceEngine, "performanceEngine");
    }

    /**
     * 根据当前模式选择合适的引擎为 submitter 分配 nonce。
     *
     * @param submitter 业务唯一标识
     * @return 被 RESERVE 的 nonce 记录
     */
    @Override
    public NonceAllocation allocate(String submitter) {
        NonceEngineMode current = mode.get();
        switch (current) {
            case PERFORMANCE:
                return invokePerformance(engine -> engine.allocate(submitter),
                        () -> reliableEngine.allocate(submitter),
                        "allocate");
            case DUAL_WRITE:
                NonceAllocation allocation = reliableEngine.allocate(submitter);
                performanceEngine.mirrorReservation(allocation);
                return allocation;
            case DRAIN_AND_SYNC:
                performanceEngine.awaitDrainCompletion();
                return reliableEngine.allocate(submitter);
            case DEGRADED:
            case RELIABLE:
            default:
                return reliableEngine.allocate(submitter);
        }
    }

    /**
     * 将 nonce 标记为 USED，性能模式下若失败会自动回退到可靠模式。
     *
     * @param submitter 业务唯一标识
     * @param nonce     目标 nonce
     * @param txHash    链上交易哈希
     */
    @Override
    public void markUsed(String submitter, long nonce, String txHash) {
        NonceEngineMode current = mode.get();
        switch (current) {
            case PERFORMANCE:
                invokePerformance(engine -> {
                    engine.markUsed(submitter, nonce, txHash);
                    return null;
                }, () -> {
                    reliableEngine.markUsed(submitter, nonce, txHash);
                    return null;
                }, "markUsed");
                break;
            case DUAL_WRITE:
                reliableEngine.markUsed(submitter, nonce, txHash);
                performanceEngine.mirrorMarkUsed(submitter, nonce, txHash);
                break;
            case DRAIN_AND_SYNC:
            case DEGRADED:
            case RELIABLE:
            default:
                reliableEngine.markUsed(submitter, nonce, txHash);
                break;
        }
    }

    /**
     * 将 nonce 标记为 RECYCLABLE，性能模式同样支持回退兜底。
     *
     * @param submitter 业务唯一标识
     * @param nonce     目标 nonce
     * @param reason    回收原因
     */
    @Override
    public void markRecyclable(String submitter, long nonce, String reason) {
        NonceEngineMode current = mode.get();
        switch (current) {
            case PERFORMANCE:
                invokePerformance(engine -> {
                    engine.markRecyclable(submitter, nonce, reason);
                    return null;
                }, () -> {
                    reliableEngine.markRecyclable(submitter, nonce, reason);
                    return null;
                }, "markRecyclable");
                break;
            case DUAL_WRITE:
                reliableEngine.markRecyclable(submitter, nonce, reason);
                performanceEngine.mirrorMarkRecyclable(submitter, nonce, reason);
                break;
            case DRAIN_AND_SYNC:
            case DEGRADED:
            case RELIABLE:
            default:
                reliableEngine.markRecyclable(submitter, nonce, reason);
                break;
        }
    }

    /**
     * 切回纯可靠模式，所有流量直接落 DB。
     */
    public void forceReliableMode() {
        setMode(NonceEngineMode.RELIABLE);
    }

    /**
     * 进入 DUAL_WRITE：仍以可靠模式为准，同时镜像写 Redis，以便后续切换。
     */
    public void enterDualWrite() {
        ensurePerformanceEnabled();
        setMode(NonceEngineMode.DUAL_WRITE);
    }

    /** 激活性能模式，调用前需要完成预热与对账。 */
    public void activatePerformanceMode() {
        ensurePerformanceEnabled();
        if (mode.get() != NonceEngineMode.DUAL_WRITE) {
            throw new NonceException("进入性能模式前需要处于 DUAL_WRITE 状态");
        }
        performanceEngine.verifySwitchReady();
        setMode(NonceEngineMode.PERFORMANCE);
    }

    /** 触发排水，将模式置为 DRAIN_AND_SYNC，刷盘清空后再切换。 */
    public void requestDrainAndSync() {
        if (!mode.get().isPerformanceLike()) {
            return;
        }
        setMode(NonceEngineMode.DRAIN_AND_SYNC);
    }

    /** 获取当前运行模式。 */
    public NonceEngineMode currentMode() {
        return mode.get();
    }

    /** 获取模式最后更新时间。 */
    public Instant lastModeUpdatedAt() {
        return modeUpdatedAt.get();
    }

    /** 暴露当前激活引擎的健康快照，用于运维观测。 */
    public EngineHealthSnapshot currentHealth() {
        if (!performanceEngine.isEnabled()) {
            return null;
        }
        return performanceEngine.healthSnapshot();
    }

    private <T> T invokePerformance(Function<PerformanceNonceEngine, T> action,
                                    Supplier<T> fallback,
                                    String actionDesc) {
        try {
            return action.apply(performanceEngine);
        } catch (RuntimeException ex) {
            LOGGER.error("[nonce] performance engine {} failed, fallback to reliable", actionDesc, ex);
            degradeIfNecessary(ex);
            return fallback.get();
        }
    }

    /**
     * 根据配置决定是否自动降级，当性能链路出现异常时调用。
     */
    private void degradeIfNecessary(RuntimeException ex) {
        if (performanceEngine.isAutoDegradeEnabled()) {
            setMode(NonceEngineMode.DEGRADED);
            LOGGER.warn("[nonce] switch to DEGRADED mode due to performance engine exception: {}",
                    ex.getMessage());
        } else {
            throw ex;
        }
    }

    /** 校验是否启用了性能模式，避免误操作。 */
    private void ensurePerformanceEnabled() {
        if (!performanceEngine.isEnabled()) {
            throw new NonceException("Redis 性能模式未启用");
        }
    }

    /** 切换模式并记录时间戳，同时输出日志。 */
    private void setMode(NonceEngineMode newMode) {
        NonceEngineMode old = mode.getAndSet(newMode);
        modeUpdatedAt.set(Instant.now());
        LOGGER.info("[nonce] switch mode {} -> {}", old, newMode);
    }
}

