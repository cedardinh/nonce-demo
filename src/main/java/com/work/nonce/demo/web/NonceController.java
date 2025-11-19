package com.work.nonce.demo.web;

import com.work.nonce.demo.service.NonceDemoService;
import com.work.nonce.demo.web.dto.NonceRequest;
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

    public NonceController(NonceDemoService nonceDemoService) {
        this.nonceDemoService = nonceDemoService;
    }

    @PostMapping("/{submitter}")
    public ResponseEntity<SimpleNoncePayloadFF> allocateAndExecute(@PathVariable String submitter,
                                                                   @Validated @RequestBody NonceRequest request) {
        SimpleNoncePayloadFF response = nonceDemoService.refund(submitter, request.getPayload());
        return ResponseEntity.ok(response);
    }
}

