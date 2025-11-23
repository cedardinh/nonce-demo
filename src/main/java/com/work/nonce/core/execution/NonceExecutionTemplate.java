package com.work.nonce.core.execution;

import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

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

    private final NonceAllocationEngine allocationEngine;
    private final NonceResultProcessor resultProcessor;

    public NonceExecutionTemplate(NonceAllocationEngine allocationEngine, NonceResultProcessor resultProcessor) {
        this.allocationEngine = requireNonNull(allocationEngine, "allocationEngine");
        this.resultProcessor = requireNonNull(resultProcessor, "resultProcessor");
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
        requireNonEmpty(submitter, "submitter");
        requireNonNull(handler, "handler");

        NonceAllocation allocation = null;
        try {
            // 1. 领取 nonce 并构造上下文
            allocation = allocationEngine.allocate(submitter);
            NonceExecutionContext ctx = new NonceExecutionContext(submitter, allocation.getNonce());
            NonceExecutionResult<T> result = handler.handle(ctx);
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
            String reason = "handler 异常: " + detail;
            allocationEngine.markRecyclable(submitter, allocation.getNonce(), reason);
        } catch (Exception recycleEx) {
            throw new NonceException("handler 执行异常且回收nonce失败", original);
        }
    }
}

