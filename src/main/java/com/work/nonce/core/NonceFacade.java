package com.work.nonce.core;

import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.execution.NonceExecutionHandler;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.model.NonceAllocation;
import org.springframework.stereotype.Service;

/**
 * 门面层，负责向业务侧提供最精简、最稳定的 nonce 操作入口。
 * <p>内部通过 {@link NonceAllocationEngine} 自动路由到可靠模式或性能模式。</p>
 * <p>推荐作为业务方接入 nonce 能力的唯一门面 API。</p>
 */
@Service
public class NonceFacade {

    private final NonceExecutionTemplate executionTemplate;
    private final NonceAllocationEngine allocationEngine;

    public NonceFacade(NonceExecutionTemplate executionTemplate, NonceAllocationEngine allocationEngine) {
        this.executionTemplate = executionTemplate;
        this.allocationEngine = allocationEngine;
    }

    /**
     * 推荐入口：框架先分配 nonce，再执行 handler，最后依据 {@link NonceExecutionResult} 自动更新状态。
     */
    public <T> NonceExecutionResult<T> withNonce(String submitter, NonceExecutionHandler<T> handler) {
        return executionTemplate.execute(submitter, handler);
    }

    /**
     * 低阶接口：仅领取 nonce，不触发自动状态流转，适合业务自行管理生命周期的场景。
     */
    public NonceAllocation allocate(String submitter) {
        return allocationEngine.allocate(submitter);
    }

    /**
     * 业务确认链上成功后，显式将 nonce 标记为 USED。
     */
    public void markUsed(String submitter, long nonce, String txHash) {
        allocationEngine.markUsed(submitter, nonce, txHash);
    }

    /**
     * 业务失败或放弃时，显式将 nonce 回收为 RECYCLABLE，供后续复用。
     */
    public void markRecyclable(String submitter, long nonce, String reason) {
        allocationEngine.markRecyclable(submitter, nonce, reason);
    }
}


