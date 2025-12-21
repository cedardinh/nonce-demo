package com.work.nonce.demo.web;

import com.work.nonce.demo.service.NonceDemoService;
import com.work.nonce.demo.config.NonceProperties;
import com.work.nonce.demo.worker.WorkerQueueDispatcher;
import com.work.nonce.demo.web.dto.NonceRequest;
import com.work.nonce.demo.web.dto.NonceResponse;
import com.work.nonce.demo.web.dto.SimpleNoncePayloadFF;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供最小可用的 REST API，启动 Spring Boot 后可直接调用验证组件行为。
 */
@RestController
@RequestMapping("/api/nonces")
public class NonceController {

    private final NonceDemoService nonceDemoService;
    private final NonceProperties properties;
    private final WorkerQueueDispatcher dispatcher;

    public NonceController(NonceDemoService nonceDemoService,
                           NonceProperties properties,
                           WorkerQueueDispatcher dispatcher) {
        this.nonceDemoService = nonceDemoService;
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    @PostMapping("/{signer}")
    public ResponseEntity<NonceResponse<SimpleNoncePayloadFF>> allocateAndExecute(@PathVariable String signer,
                                                                                  @Validated @RequestBody NonceRequest request) {
        NonceResponse<SimpleNoncePayloadFF> response;
        if ("worker-queue".equalsIgnoreCase(properties.getMode())) {
            response = dispatcher.dispatch(signer, () -> nonceDemoService.refund(signer, request.getPayload()));
        } else {
            response = nonceDemoService.refund(signer, request.getPayload());
        }
        return ResponseEntity.ok(response);
    }
}

