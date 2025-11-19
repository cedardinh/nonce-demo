package com.work.nonce.core.execution;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 模板负责串联"获取 nonce → 执行业务 handler → 根据结果更新状态"的流程。
 * <p>
 * 职责：
 * 1. 统一管理nonce的生命周期
 * 2. 根据handler执行结果自动更新nonce状态
 * 3. 确保异常情况下资源正确释放
 */
public class NonceExecutionTemplate {

    private final NonceService nonceService;
    private final NonceResultProcessor resultProcessor;

    public NonceExecutionTemplate(NonceService nonceService, NonceResultProcessor resultProcessor) {
        this.nonceService = requireNonNull(nonceService, "nonceService");
        this.resultProcessor = requireNonNull(resultProcessor, "resultProcessor");
    }

    /**
     * 推荐给业务方的入口：自动完成资源获取与释放。
     * <p>
     * 流程：
     * 1. 分配nonce
     * 2. 执行业务handler
     * 3. 根据执行结果更新nonce状态（SUCCESS -> USED, NON_RETRYABLE_FAILURE -> RECYCLABLE）
     * 4. RETRYABLE_FAILURE保持RESERVED状态，由业务自行重试
     *
     * @param submitter submitter标识
     * @param handler   业务处理逻辑
     * @return 执行结果
     * @throws NonceException 如果handler返回null或执行过程中发生异常
     */
    public <T> NonceExecutionResult<T> execute(String submitter, NonceExecutionHandler<T> handler) {
        requireNonEmpty(submitter, "submitter");
        requireNonNull(handler, "handler");

        NonceAllocation allocation = null;
        try {
            // 分配nonce
            allocation = nonceService.allocate(submitter);
            NonceExecutionContext ctx = new NonceExecutionContext(submitter, allocation.getNonce());
            NonceExecutionResult<T> result = handler.handle(ctx);
            return resultProcessor.process(submitter, allocation, result);

        } catch (NonceException ex) {
            // NonceException直接抛出，不重复处理
            throw ex;
        } catch (Exception ex) {
            markAllocationSafely(submitter, allocation, ex);
            throw new NonceException("handler 执行异常", ex);
        }
    }

    private void markAllocationSafely(String submitter, NonceAllocation allocation, Exception original) {
        if (allocation == null) {
            return;
        }
        try {
            String reason = "handler exception: " + (original.getMessage() != null
                    ? original.getMessage()
                    : original.getClass().getSimpleName());
            nonceService.markRecyclable(submitter, allocation.getNonce(), reason);
        } catch (Exception recycleEx) {
            throw new NonceException("handler 执行异常且回收nonce失败", original);
        }
    }
}

