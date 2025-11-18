package com.work.nonce.demo.service;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.web.dto.NonceResponse;
import com.work.nonce.demo.web.dto.SimpleNoncePayloadFF;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

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

    public NonceResponse<SimpleNoncePayloadFF> refund(String submitter, String payload) {
        AtomicLong nonceHolder = new AtomicLong();
        NonceExecutionResult result = nonceComponent.withNonce(submitter, ctx -> {
            nonceHolder.set(ctx.getNonce());
            String txHash = chainClient.sendTransaction(ctx.getSubmitter(), ctx.getNonce(), payload);
            return NonceExecutionResult.success(txHash);
        });
        SimpleNoncePayloadFF responsePayload = new SimpleNoncePayloadFF(result.getTxHash(), payload);
        return NonceResponse.of(submitter, nonceHolder.get(), responsePayload);
    }
}

