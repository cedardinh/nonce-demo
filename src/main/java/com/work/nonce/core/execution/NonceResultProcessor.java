package com.work.nonce.core.execution;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 将“校验 handler 返回值 + 根据结果更新 allocation 状态”封装到独立组件，
 * 避免模板类承担过多职责，也便于未来扩展更多状态机分支。
 */
public class NonceResultProcessor {

    private final NonceService nonceService;

    public NonceResultProcessor(NonceService nonceService) {
        this.nonceService = requireNonNull(nonceService, "nonceService");
    }

    public <T> NonceExecutionResult<T> process(String submitter,
                                               NonceAllocation allocation,
                                               NonceExecutionResult<T> result) {
        requireNonEmpty(submitter, "submitter");
        requireNonNull(allocation, "allocation");
        validateResult(result);                      // 先确保 handler 返回值合法
        updateAllocationStatus(submitter, allocation, result); // 再驱动状态更新
        return result;
    }

    private void validateResult(NonceExecutionResult<?> result) {
        if (result == null) {
            throw new NonceException("handler 返回结果不能为空");
        }

        if (result.getStatus() == NonceExecutionResult.Status.SUCCESS) {
            String txHash = result.getTxHash();
            if (txHash == null || txHash.trim().isEmpty()) {
                throw new NonceException("SUCCESS 状态必须提供 txHash");
            }
        }
    }

    private void updateAllocationStatus(String submitter,
                                        NonceAllocation allocation,
                                        NonceExecutionResult<?> result) {
        switch (result.getStatus()) {
            case SUCCESS:
                nonceService.markUsed(submitter, allocation.getNonce(), result.getTxHash());
                break;
            case FAIL:
            String reason = result.getReason() != null ? result.getReason() : "fail";
                nonceService.markRecyclable(submitter, allocation.getNonce(), reason);
                break;
            default:
                throw new NonceException("未知的执行结果: " + result.getStatus());
        }
    }
}

