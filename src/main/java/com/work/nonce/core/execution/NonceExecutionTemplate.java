package com.work.nonce.core.execution;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

/**
 * 模板负责串联“获取 nonce → 执行业务 handler → 根据结果更新状态”的流程。
 */
public class NonceExecutionTemplate {

    private final NonceService nonceService;

    public NonceExecutionTemplate(NonceService nonceService) {
        this.nonceService = nonceService;
    }

    /**
     * 推荐给业务方的入口：自动完成资源获取与释放。
     */
    public NonceExecutionResult execute(String submitter, NonceExecutionHandler handler) {
        NonceAllocation allocation = nonceService.allocate(submitter);
        NonceExecutionContext ctx = new NonceExecutionContext(submitter, allocation.getNonce());
        try {
            NonceExecutionResult result = handler.handle(ctx);
            if (result == null) {
                throw new NonceException("handler 返回结果不能为空");
            }
            switch (result.getOutcome()) {
                case SUCCESS:
                    nonceService.markUsed(submitter, allocation.getNonce(), result.getTxHash());
                    break;
                case NON_RETRYABLE_FAILURE:
                    nonceService.markRecyclable(submitter, allocation.getNonce(), result.getReason());
                    break;
                case RETRYABLE_FAILURE:
                    // RETRYABLE 场景下保持 RESERVED，由业务自行重试。
                    break;
                default:
                    throw new NonceException("未知的执行结果: " + result.getOutcome());
            }
            return result;
        } catch (NonceException ex) {
            // 已经由 mark* 处理，直接抛出。
            throw ex;
        } catch (Exception ex) {
            nonceService.markRecyclable(submitter, allocation.getNonce(), "handler exception: " + ex.getMessage());
            throw new NonceException("handler 执行异常", ex);
        }
    }
}

