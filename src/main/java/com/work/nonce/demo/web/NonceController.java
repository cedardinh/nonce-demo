package com.work.nonce.demo.web;

import com.work.nonce.demo.service.NonceDemoService;
import com.work.nonce.demo.web.dto.BatchNonceRequest;
import com.work.nonce.demo.web.dto.ManualNonceAllocationResponse;
import com.work.nonce.demo.web.dto.ManualNonceOperationResponse;
import com.work.nonce.demo.web.dto.MarkRecyclableRequest;
import com.work.nonce.demo.web.dto.MarkUsedRequest;
import com.work.nonce.demo.web.dto.NonceRequest;
import com.work.nonce.demo.web.dto.NonceScenarioResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外暴露多种业务场景，方便接口层验证组件“拿号 → 写链 → 状态流转”的完整能力。
 * <p>
 * 所有接口均复用 {@link com.work.nonce.core.NonceFacade#withNonce(String, com.work.nonce.core.execution.NonceExecutionHandler)}
 * 来确保业务只关注 handler 内的核心逻辑。
 */
@RestController
@RequestMapping("/api/nonces")
public class NonceController {

    private final NonceDemoService nonceDemoService;

    public NonceController(NonceDemoService nonceDemoService) {
        this.nonceDemoService = nonceDemoService;
    }

    /**
     * 场景 1：最常见的“成功落链”流程。可用于快速验证组件的 happy-path。
     */
    @PostMapping("/{submitter}/refund")
    public ResponseEntity<NonceScenarioResponse> refund(@PathVariable String submitter,
                                                        @Validated @RequestBody NonceRequest request) {
        return ResponseEntity.ok(nonceDemoService.refund(submitter, request.getPayload()));
    }

    /**
     * 场景 2：主动制造链上错误，观测 nonce 会被自动标记为 RECYCLABLE。
     * 方便压测/联调时验证失败分支的幂等性。
     */
    @PostMapping("/{submitter}/simulate-chain-error")
    public ResponseEntity<NonceScenarioResponse> simulateChainError(@PathVariable String submitter,
                                                                    @Validated @RequestBody NonceRequest request) {
        return ResponseEntity.ok(nonceDemoService.simulateChainFailure(submitter, request.getPayload()));
    }

    /**
     * 场景 3：演示同一个 submitter 的“失败 → 重试”流程。
     * 第一次调用必 FAIL，第二次自动复用 gap nonce，并返回 SUCCESS。
     */
    @PostMapping("/{submitter}/retryable")
    public ResponseEntity<NonceScenarioResponse> retryable(@PathVariable String submitter,
                                                           @Validated @RequestBody NonceRequest request) {
        return ResponseEntity.ok(nonceDemoService.retryWithAutoRecycle(submitter, request.getPayload()));
    }

    /**
     * 场景 4：在同一个请求内串行发送多笔交易，展示组件如何保证 nextLocalNonce 递增、串行互斥。
     */
    @PostMapping("/{submitter}/batch")
    public ResponseEntity<List<NonceScenarioResponse>> batch(@PathVariable String submitter,
                                                             @Validated @RequestBody BatchNonceRequest request) {
        return ResponseEntity.ok(nonceDemoService.batchBroadcast(submitter, request.getPayloads()));
    }

    /**
     * 低阶接口：仅领取 nonce（RESERVED 状态），业务自行控制后续生命周期。
     */
    @PostMapping("/{submitter}/manual/allocate")
    public ResponseEntity<ManualNonceAllocationResponse> manualAllocate(@PathVariable String submitter) {
        return ResponseEntity.ok(nonceDemoService.manualAllocate(submitter));
    }

    /**
     * 低阶接口：在业务确认链上成功后，手动标记本次 nonce 为 USED。
     */
    @PostMapping("/{submitter}/manual/{nonce}/mark-used")
    public ResponseEntity<ManualNonceOperationResponse> manualMarkUsed(@PathVariable String submitter,
                                                                       @PathVariable long nonce,
                                                                       @Validated @RequestBody MarkUsedRequest request) {
        return ResponseEntity.ok(nonceDemoService.manualMarkUsed(submitter, nonce, request.getTxHash()));
    }

    /**
     * 低阶接口：业务失败或放弃时，显式释放 nonce，便于下次复用。
     */
    @PostMapping("/{submitter}/manual/{nonce}/mark-recyclable")
    public ResponseEntity<ManualNonceOperationResponse> manualMarkRecyclable(@PathVariable String submitter,
                                                                             @PathVariable long nonce,
                                                                             @Validated @RequestBody MarkRecyclableRequest request) {
        return ResponseEntity.ok(nonceDemoService.manualMarkRecyclable(submitter, nonce, request.getReason()));
    }
}

