package com.work.nonce.demo.service;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.demo.web.dto.ManualNonceAllocationResponse;
import com.work.nonce.demo.web.dto.ManualNonceOperationResponse;
import com.work.nonce.demo.web.dto.NonceScenarioResponse;
import com.work.nonce.demo.web.dto.SimpleNoncePayloadFF;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 示例业务服务：展示业务如何通过 NonceComponent 获取 nonce 并写链。
 */
@Service
public class NonceDemoService {

    private final NonceComponent nonceComponent;
    private final ChainClient chainClient;
    private final Map<String, Boolean> firstFailFlags = new ConcurrentHashMap<>();

    public NonceDemoService(NonceComponent nonceComponent, ChainClient chainClient) {
        this.nonceComponent = nonceComponent;
        this.chainClient = chainClient;
    }

    /**
     * 低阶流程：只领取 nonce，业务后续自行决定何时提交/回收。
     */
    public ManualNonceAllocationResponse manualAllocate(String submitter) {
        NonceAllocation allocation = nonceComponent.allocate(submitter);
        return ManualNonceAllocationResponse.fromAllocation(allocation,
                "当前线程独占该 nonce，需业务在流程完成后手动标记状态");
    }

    /**
     * 低阶流程：业务确认链上成功后，手动标记为 USED。
     */
    public ManualNonceOperationResponse manualMarkUsed(String submitter, long nonce, String txHash) {
        nonceComponent.markUsed(submitter, nonce, txHash);
        return ManualNonceOperationResponse.success("手动标记 USED",
                submitter, nonce, "txHash=" + txHash);
    }

    /**
     * 低阶流程：业务无法完成，显式释放本次 nonce，供后续复用。
     */
    public ManualNonceOperationResponse manualMarkRecyclable(String submitter, long nonce, String reason) {
        nonceComponent.markRecyclable(submitter, nonce, reason);
        return ManualNonceOperationResponse.success("手动回收 RECYCLABLE",
                submitter, nonce, reason == null ? "无额外原因" : reason);
    }

    /**
     * 最常见的“拿号-写链-落库”闭环，展示 SUCCESS 分支。
     */
    public NonceScenarioResponse refund(String submitter, String payload) {
        AtomicLong nonceHolder = new AtomicLong(-1L);
        NonceExecutionResult<SimpleNoncePayloadFF> result = nonceComponent.withNonce(submitter, ctx -> {
            nonceHolder.set(ctx.getNonce());
            String txHash = chainClient.sendTransaction(ctx.getSubmitter(), ctx.getNonce(), payload);
            SimpleNoncePayloadFF responsePayload = new SimpleNoncePayloadFF(txHash, payload);
            return NonceExecutionResult.success(txHash, responsePayload);
        });
        return adaptResult("标准成功流程", submitter, nonceHolder.get(), result, "链上与业务均成功，nonce 标记为 USED");
    }

    /**
     * 主动制造 FAIL 结果，观察组件如何把 nonce 回收并允许下一次重试复用。
     */
    public NonceScenarioResponse simulateChainFailure(String submitter, String payload) {
        AtomicLong nonceHolder = new AtomicLong(-1L);
        NonceExecutionResult<SimpleNoncePayloadFF> result = nonceComponent.withNonce(submitter, ctx -> {
            nonceHolder.set(ctx.getNonce());
            return NonceExecutionResult.fail("模拟链上 RPC 失败，nonce 立即回收", (SimpleNoncePayloadFF) null);
        });
        return adaptResult("链上失败自动回收", submitter, nonceHolder.get(), result,
                "本次调用不会消耗序列号，可直接重试");
    }

    /**
     * 第一次调用必然 FAIL（演示回收），第二次起恢复 SUCCESS（复用洞号），演示同 submitter 的幂等重试。
     */
    public NonceScenarioResponse retryWithAutoRecycle(String submitter, String payload) {
        boolean failThisRound = firstFailFlags.putIfAbsent(submitter, Boolean.TRUE) == null;
        AtomicLong nonceHolder = new AtomicLong(-1L);
        NonceExecutionResult<SimpleNoncePayloadFF> result = nonceComponent.withNonce(submitter, ctx -> {
            nonceHolder.set(ctx.getNonce());
            if (failThisRound) {
                return NonceExecutionResult.fail("第一次调用模拟失败，nonce 被回收到 RECYCLABLE", (SimpleNoncePayloadFF) null);
            }
            String txHash = chainClient.sendTransaction(ctx.getSubmitter(), ctx.getNonce(), payload);
            SimpleNoncePayloadFF payloadFF = new SimpleNoncePayloadFF(txHash, payload);
            return NonceExecutionResult.success(txHash, payloadFF);
        });
        if (!failThisRound && result.getStatus() == NonceExecutionResult.Status.SUCCESS) {
            firstFailFlags.remove(submitter);
        }
        String message = failThisRound
                ? "请稍后重试：组件已清除锁并回收 nonce"
                : "命中上次失败的 gap，组件自动复用同一个 nonce";
        return adaptResult("自动重试示例", submitter, nonceHolder.get(), result, message);
    }

    /**
     * 在同一个请求内批量调用组件，模拟“串行批量发送”场景。
     */
    public List<NonceScenarioResponse> batchBroadcast(String submitter, List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return Collections.emptyList();
        }
        List<NonceScenarioResponse> responses = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            String payload = payloads.get(i);
            AtomicLong nonceHolder = new AtomicLong(-1L);
            int order = i + 1;
            NonceExecutionResult<SimpleNoncePayloadFF> result = nonceComponent.withNonce(submitter, ctx -> {
                nonceHolder.set(ctx.getNonce());
                String txHash = chainClient.sendTransaction(ctx.getSubmitter(), ctx.getNonce(), payload);
                return NonceExecutionResult.success(txHash, new SimpleNoncePayloadFF(txHash, payload));
            });
            responses.add(adaptResult("批量串行第 " + order + " 笔", submitter, nonceHolder.get(), result,
                    "同一线程串行调度，观察 nextLocalNonce 自增"));
        }
        return responses;
    }

    private NonceScenarioResponse adaptResult(String scenario,
                                              String submitter,
                                              long nonce,
                                              NonceExecutionResult<SimpleNoncePayloadFF> result,
                                              String message) {
        if (result.getStatus() == NonceExecutionResult.Status.SUCCESS) {
            SimpleNoncePayloadFF payload = result.getPayload();
            String payloadEcho = payload != null ? payload.getPayloadEcho() : null;
            return NonceScenarioResponse.success(scenario, submitter, nonce, result.getTxHash(), payloadEcho, message);
        }
        return NonceScenarioResponse.fail(scenario, submitter, nonce, result.getReason(), message);
    }
}

