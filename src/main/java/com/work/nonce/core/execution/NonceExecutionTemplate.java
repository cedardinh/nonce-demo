package com.work.nonce.core.execution;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 模板负责串联"获取 nonce → 执行业务 handler → 根据结果更新状态"的流程。
 * 
 * 职责：
 * 1. 统一管理nonce的生命周期
 * 2. 根据handler执行结果自动更新nonce状态
 * 3. 确保异常情况下资源正确释放
 */
public class NonceExecutionTemplate {

    private final NonceService nonceService;

    public NonceExecutionTemplate(NonceService nonceService) {
        this.nonceService = requireNonNull(nonceService, "nonceService");
    }

    /**
     * 推荐给业务方的入口：自动完成资源获取与释放。
     * 
     * 流程：
     * 1. 分配nonce
     * 2. 执行业务handler
     * 3. 根据执行结果更新nonce状态（SUCCESS -> USED, NON_RETRYABLE_FAILURE -> RECYCLABLE）
     * 4. RETRYABLE_FAILURE保持RESERVED状态，由业务自行重试
     * 
     * @param submitter submitter标识
     * @param handler 业务处理逻辑
     * @return 执行结果
     * @throws NonceException 如果handler返回null或执行过程中发生异常
     */
    public NonceExecutionResult execute(String submitter, NonceExecutionHandler handler) {
        requireNonEmpty(submitter, "submitter");
        requireNonNull(handler, "handler");
        
        NonceAllocation allocation = null;
        try {
            // 分配nonce
            allocation = nonceService.allocate(submitter);
        NonceExecutionContext ctx = new NonceExecutionContext(submitter, allocation.getNonce());
            
            // 执行业务handler
            NonceExecutionResult result = handler.handle(ctx);
            validateResult(result);
            
            // 根据执行结果更新状态
            updateAllocationStatus(submitter, allocation, result);
            
            return result;
            
        } catch (NonceException ex) {
            // NonceException直接抛出，不重复处理
            throw ex;
        } catch (Exception ex) {
            // 其他异常：保守处理，默认将 nonce 视为“可能已消耗”，避免误回收导致 nonce 冲突风险
            if (allocation != null) {
                try {
                    String reason = "handler exception (unknown if submitted): "
                            + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                    nonceService.markUsed(submitter, allocation.getNonce(), null, reason);
                } catch (Exception recycleEx) {
                    throw new NonceException("handler 执行异常且标记 nonce 失败", ex);
                }
            }
            throw new NonceException("handler 执行异常", ex);
        }
    }
    
    /**
     * 校验执行结果
     */
    private void validateResult(NonceExecutionResult result) {
        if (result == null) {
            throw new NonceException("handler 返回结果不能为空");
        }
        
        // 校验SUCCESS状态必须提供txHash
        if (result.getOutcome() == NonceExecutionResult.Outcome.SUCCESS) {
            if (result.getTxHash() == null || result.getTxHash().trim().isEmpty()) {
                throw new NonceException("SUCCESS 状态必须提供 txHash");
            }
        }
    }
    
    /**
     * 根据执行结果更新allocation状态
     */
    private void updateAllocationStatus(String submitter, NonceAllocation allocation, NonceExecutionResult result) {
        // 只要 nonce 被认为已消耗，就必须标记为 USED，避免被回收复用造成 nonce 冲突风险
        if (result.isNonceConsumed()) {
            String reason = result.getReason() != null ? result.getReason() : "";
            nonceService.markUsed(submitter, allocation.getNonce(), result.getTxHash(), reason);
            return;
        }

        switch (result.getOutcome()) {
            case SUCCESS:
                // 业务成功，标记为已使用
                nonceService.markUsed(submitter, allocation.getNonce(), result.getTxHash(), null);
                break;
                
            case NON_RETRYABLE_FAILURE:
                // 不可重试的失败，回收nonce
                String reason = result.getReason() != null ? result.getReason() : "non-retryable failure";
                nonceService.markRecyclable(submitter, allocation.getNonce(), reason);
                break;
                
            case RETRYABLE_FAILURE:
                // 可重试的失败，保持RESERVED状态，由业务自行重试
                // 不进行任何操作
                break;
                
            default:
                throw new NonceException("未知的执行结果: " + result.getOutcome());
        }
    }
}

