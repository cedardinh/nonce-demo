package com.work.nonce.txmgr.web;

import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.service.NotLeaderException;
import com.work.nonce.txmgr.service.TxService;
import com.work.nonce.txmgr.service.writer.TransactionWriter;
import com.work.nonce.txmgr.web.dto.CreateTxRequest;
import com.work.nonce.txmgr.web.dto.TxView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * 111最终方案.md：poll-only 对外契约 + requestId 可选幂等 + not leader=409。
 */
@RestController
@RequestMapping("/api/v1/tx")
public class TxController {

    private final TxService txService;
    private final TransactionWriter writer;

    public TxController(TxService txService, TransactionWriter writer) {
        this.txService = txService;
        this.writer = writer;
    }

    @PostMapping
    public ResponseEntity<TxView> create(@Validated @RequestBody CreateTxRequest req) throws ExecutionException, InterruptedException {
        // 入口幂等预检（requestId 存在才启用）
        if (req.getRequestId() != null && !req.getRequestId().trim().isEmpty()) {
            ManagedTxEntity existing = txService.getByRequestId(req.getSubmitter(), req.getRequestId());
            if (existing != null) {
                return ResponseEntity.ok(toView(existing));
            }
        }

        try {
            ManagedTxEntity created = writer.submitCreate(req.getSubmitter(), req.getRequestId(), req.getPayload()).get();
            // 事务提交后异步 submit（里程碑1：仅示例性触发）
            writer.submitToChainAsync(created);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(toView(created));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof NotLeaderException) {
                throw (NotLeaderException) cause;
            }
            throw ee;
        }
    }

    @GetMapping("/{txId}")
    public ResponseEntity<TxView> get(@PathVariable String txId) {
        ManagedTxEntity tx = txService.getById(UUID.fromString(txId));
        if (tx == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(tx));
    }

    @GetMapping("/by-request")
    public ResponseEntity<TxView> getByRequest(@RequestParam("submitter") String submitter,
                                               @RequestParam("requestId") String requestId) {
        ManagedTxEntity tx = txService.getByRequestId(submitter, requestId);
        if (tx == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(tx));
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<String> handleNotLeader(NotLeaderException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    private TxView toView(ManagedTxEntity e) {
        TxView v = new TxView();
        v.setTxId(e.getTxId());
        v.setSubmitter(e.getSubmitter());
        v.setRequestId(e.getRequestId());
        v.setNonce(e.getNonce());
        v.setTxHash(e.getTxHash());
        v.setState(e.getState());
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        return v;
    }
}


