package com.work.nonce.core;

import com.work.nonce.core.execution.NonceExecutionHandler;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

/**
 * 门面（Facade）层，对业务侧暴露最少的调用面。
 */
public class NonceComponent {

    private final NonceExecutionTemplate executionTemplate;
    private final NonceService nonceService;

    public NonceComponent(NonceExecutionTemplate executionTemplate, NonceService nonceService) {
        this.executionTemplate = executionTemplate;
        this.nonceService = nonceService;
    }

    /**
     * 推荐用法：在 handler 中执行业务逻辑，模板自动根据执行结果处理状态。
     */
    public NonceExecutionResult withNonce(String submitter, NonceExecutionHandler handler) {
        return executionTemplate.execute(submitter, handler);
    }

    /**
     * 低阶接口，允许业务先领取 nonce，再在合适的时机显式标记 USED/RECYCLABLE。
     */
    public NonceAllocation allocate(String submitter) {
        return nonceService.allocate(submitter);
    }

    public void markUsed(String submitter, long nonce, String txHash) {
        nonceService.markUsed(submitter, nonce, txHash);
    }

    public void markRecyclable(String submitter, long nonce, String reason) {
        nonceService.markRecyclable(submitter, nonce, reason);
    }
}

