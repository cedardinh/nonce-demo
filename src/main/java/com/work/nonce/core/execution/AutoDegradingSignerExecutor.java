package com.work.nonce.core.execution;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * auto 模式：
 * - 优先 worker-queue（节点内串行化）
 * - 当出现“队列满/超时/执行异常”时，允许自动降级为 basic，并开启短暂熔断窗口
 *
 * 目的：在保持调用方式一致的前提下，让系统可以“自由且灵活切换模式”（配置 + 运行时降级）。
 */
public class AutoDegradingSignerExecutor implements SignerExecutor {

    private final SignerExecutor primary;  // worker-queue
    private final SignerExecutor fallback; // basic

    private final int failThreshold;
    private final Duration openDuration;

    private final AtomicInteger consecutiveFails = new AtomicInteger(0);
    private final AtomicLong openUntilMillis = new AtomicLong(0L);

    public AutoDegradingSignerExecutor(SignerExecutor primary,
                                       SignerExecutor fallback,
                                       int failThreshold,
                                       Duration openDuration) {
        this.primary = requireNonNull(primary, "primary");
        this.fallback = requireNonNull(fallback, "fallback");
        if (failThreshold <= 0) throw new IllegalArgumentException("failThreshold must be > 0");
        this.failThreshold = failThreshold;
        this.openDuration = requireNonNull(openDuration, "openDuration");
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be > 0");
        }
    }

    @Override
    public <T> T execute(String signer, Callable<T> work) {
        requireNonEmpty(signer, "signer");
        requireNonNull(work, "work");

        long now = System.currentTimeMillis();
        if (now < openUntilMillis.get()) {
            // 熔断开启：直接走 fallback（basic）
            return fallback.execute(signer, work);
        }

        try {
            T out = primary.execute(signer, work);
            consecutiveFails.set(0);
            return out;
        } catch (RuntimeException ex) {
            // 只对 worker-queue 的“资源型失败”做降级信号（队列满/超时）
            boolean degradable =
                    (ex instanceof WorkerQueueSignerExecutor.DispatchRejectedException) ||
                    (ex instanceof WorkerQueueSignerExecutor.DispatchTimeoutException);

            if (!degradable) {
                // 非资源失败：不改模式，原样抛出
                throw ex;
            }

            int fails = consecutiveFails.incrementAndGet();
            if (fails >= failThreshold) {
                openUntilMillis.set(System.currentTimeMillis() + openDuration.toMillis());
                consecutiveFails.set(0);
            }
            // 本次直接 fallback，保证“调用一致 + 自动降级”
            return fallback.execute(signer, work);
        }
    }
}


