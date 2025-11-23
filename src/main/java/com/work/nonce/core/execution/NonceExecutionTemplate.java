package com.work.nonce.core.execution;

import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;

import static com.work.nonce.core.support.ValidationUtils.requireNonNull;
import static com.work.nonce.core.support.ValidationUtils.requireValidSubmitter;

/**
 * 模板模式封装“获取 nonce → 执行业务 handler → 按结果更新状态”完整链路。
 * <p>核心职责：</p>
 * <ol>
 *     <li>统一管理 nonce 生命周期，保证锁与状态变化始终成对出现</li>
 *     <li>根据业务返回的 {@link NonceExecutionResult} 自动更新状态机</li>
 *     <li>在异常场景下兜底回收资源，避免脏数据积压</li>
 * </ol>
 */
public class NonceExecutionTemplate {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonceExecutionTemplate.class);

    private final NonceAllocationEngine allocationEngine;
    private final NonceResultProcessor resultProcessor;
    /**
     * 业务 handler 执行线程池，可避免阻塞调用线程。
     */
    private final ExecutorService executor;
    /**
     * handler 最大执行时长，超时后将回收当前 nonce 并抛出统一异常。
     * 为保持兼容，默认不启用超时（<=0 表示不限制）。
     */
    private final long handlerTimeoutMillis;

    public NonceExecutionTemplate(NonceAllocationEngine allocationEngine,
                                  NonceResultProcessor resultProcessor) {
        this(allocationEngine, resultProcessor, null, Duration.ZERO);
    }

    /**
     * 扩展构造函数：允许宿主传入自定义线程池与超时配置。
     */
    public NonceExecutionTemplate(NonceAllocationEngine allocationEngine,
                                  NonceResultProcessor resultProcessor,
                                  ExecutorService executor,
                                  Duration handlerTimeout) {
        this.allocationEngine = requireNonNull(allocationEngine, "allocationEngine");
        this.resultProcessor = requireNonNull(resultProcessor, "resultProcessor");
        this.executor = (executor != null) ? executor : ForkJoinPool.commonPool();
        this.handlerTimeoutMillis = (handlerTimeout != null) ? handlerTimeout.toMillis() : 0L;
    }

    /**
     * 推荐入口：对调用方屏蔽所有资源获取、状态流转细节。
     * <p>执行步骤：</p>
     * <ol>
     *     <li>按 submitter 分配一个新的 nonce</li>
     *     <li>构造 {@link NonceExecutionContext} 并执行业务 handler</li>
     *     <li>由 {@link NonceResultProcessor} 根据结果标记 USED / RECYCLABLE</li>
     *     <li>若 handler 抛出异常，则自动回收本次 nonce 并向上抛出统一异常</li>
     * </ol>
     *
     * @param submitter 业务唯一标识
     * @param handler   实际的业务逻辑
     * @throws NonceException handler 返回非法结果或执行异常时抛出
     */
    public <T> NonceExecutionResult<T> execute(String submitter, NonceExecutionHandler<T> handler) {
        requireValidSubmitter(submitter);
        requireNonNull(handler, "handler");

        NonceAllocation allocation = null;
        try {
            // 1. 领取 nonce 并构造上下文
            allocation = allocationEngine.allocate(submitter);
            NonceExecutionContext ctx = new NonceExecutionContext(submitter, allocation.getNonce());

            // 2. 在独立线程中执行业务 handler，必要时施加超时控制
            Callable<NonceExecutionResult<T>> task = () -> handler.handle(ctx);
            NonceExecutionResult<T> result;
            if (handlerTimeoutMillis > 0) {
                Future<NonceExecutionResult<T>> future = executor.submit(task);
                try {
                    result = future.get(handlerTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    TimeoutException ex = new TimeoutException("handler 执行超时: " + handlerTimeoutMillis + "ms");
                    markAllocationSafely(submitter, allocation, ex);
                    throw new NonceException("handler 执行超时", ex);
                }
            } else {
                result = task.call();
            }
            return resultProcessor.process(submitter, allocation, result);

        } catch (NonceException ex) {
            // 业务/状态异常直接透出，避免重复封装
            throw ex;
        } catch (Exception ex) {
            markAllocationSafely(submitter, allocation, ex);
            throw new NonceException("handler 执行异常", ex);
        }
    }

    /**
     * handler 抛出受检/运行时异常时，尝试将已领取的 nonce 回收到 RECYCLABLE，避免锁死。
     */
    private void markAllocationSafely(String submitter, NonceAllocation allocation, Exception original) {
        if (allocation == null) {
            return;
        }
        try {
            String detail = original.getMessage() != null ? original.getMessage() : original.getClass().getSimpleName();
            // 避免 reason 过长撑爆存储，这里进行简单截断
            if (detail.length() > 200) {
                detail = detail.substring(0, 200) + "...";
            }
            String reason = "handler 异常: " + detail;
            allocationEngine.markRecyclable(submitter, allocation.getNonce(), reason);
        } catch (Exception recycleEx) {
            // 回收失败仅记录日志与指标，避免掩盖原始业务异常
            LOGGER.error("[nonce] 回收 nonce 失败，submitter={}, nonce={}, original={}, recycleError={}",
                    submitter,
                    allocation.getNonce(),
                    original.getClass().getSimpleName(),
                    recycleEx.getClass().getSimpleName(),
                    recycleEx);
        }
    }
}

