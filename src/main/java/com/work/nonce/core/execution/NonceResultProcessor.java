package com.work.nonce.core.execution;

import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 负责校验 handler 的返回结果，并驱动底层引擎完成状态机转换。
 * <p>该类将“业务语义校验”和“状态更新”与模板本身解耦，便于未来扩展更多状态。</p>
 */
public class NonceResultProcessor {

    /** 真正执行状态落地的引擎，实现可以是可靠或性能模式。 */
    private final NonceAllocationEngine allocationEngine;

    public NonceResultProcessor(NonceAllocationEngine allocationEngine) {
        this.allocationEngine = requireNonNull(allocationEngine, "allocationEngine");
    }

    public <T> NonceExecutionResult<T> process(String submitter,
                                               NonceAllocation allocation,
                                               NonceExecutionResult<T> result) {
        requireNonEmpty(submitter, "submitter");
        requireNonNull(allocation, "allocation");
        validateResult(result);
        updateAllocationStatus(submitter, allocation, result);
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
                allocationEngine.markUsed(submitter, allocation.getNonce(), result.getTxHash());
                break;
            case FAIL:
                String reason = result.getReason() != null ? result.getReason() : "业务返回失败";
                allocationEngine.markRecyclable(submitter, allocation.getNonce(), reason);
                break;
            default:
                throw new NonceException("未知的执行结果: " + result.getStatus());
        }
    }
}

