package com.work.nonce.demo.service;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.web.dto.SimpleNoncePayloadFF;
import org.springframework.stereotype.Service;

/**
 * 示例业务服务：展示业务如何通过 NonceComponent 获取 nonce 并写链。
 */
@Service
public class NonceDemoService {

    private final NonceComponent nonceComponent;
    private final ChainClient chainClient;

    public NonceDemoService(NonceComponent nonceComponent, ChainClient chainClient) {
        this.nonceComponent = nonceComponent;
        this.chainClient = chainClient;
    }

    public SimpleNoncePayloadFF refund(String submitter, String payload) {
        NonceExecutionResult<SimpleNoncePayloadFF> result = nonceComponent.withNonce(submitter, ctx -> {
            String txHash = chainClient.sendTransaction(ctx.getSubmitter(), ctx.getNonce(), payload);
            SimpleNoncePayloadFF responsePayload = new SimpleNoncePayloadFF(txHash, payload);
            return NonceExecutionResult.success(txHash, responsePayload);
        });
        return result.getPayload();
    }
}

