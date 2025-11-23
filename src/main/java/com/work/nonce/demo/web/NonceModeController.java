package com.work.nonce.demo.web;

import com.work.nonce.core.engine.manager.NonceEngineManager;
import com.work.nonce.demo.web.dto.NonceModeStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露简单的运维接口，便于在 demo 场景下操作模式切换。
 */
@RestController
@RequestMapping("/api/nonces/mode")
public class NonceModeController {

    private final NonceEngineManager engineManager;

    public NonceModeController(NonceEngineManager engineManager) {
        this.engineManager = engineManager;
    }

    @GetMapping
    public ResponseEntity<NonceModeStatusResponse> status() {
        return ResponseEntity.ok(NonceModeStatusResponse.fromManager(engineManager));
    }

    @PostMapping("/reliable")
    public ResponseEntity<Void> forceReliable() {
        engineManager.forceReliableMode();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dual-write")
    public ResponseEntity<Void> enterDualWrite() {
        engineManager.enterDualWrite();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/performance")
    public ResponseEntity<Void> activatePerformance() {
        engineManager.activatePerformanceMode();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/drain")
    public ResponseEntity<Void> drainAndSync() {
        engineManager.requestDrainAndSync();
        return ResponseEntity.ok().build();
    }
}

