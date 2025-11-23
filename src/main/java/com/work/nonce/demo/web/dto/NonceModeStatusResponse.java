package com.work.nonce.demo.web.dto;

import com.work.nonce.core.engine.manager.NonceEngineManager;
import com.work.nonce.core.engine.performance.PerformanceHealthSnapshot;
import com.work.nonce.core.engine.spi.EngineHealthSnapshot;
import com.work.nonce.core.engine.spi.NonceEngineMode;

import java.time.Instant;

public class NonceModeStatusResponse {

    private NonceEngineMode mode;
    private Instant lastUpdatedAt;
    private boolean redisHealthy;
    private boolean flushHealthy;
    private long pendingEvents;
    private Instant lastFlushSuccessAt;
    private Instant lastFlushFailureAt;

    public static NonceModeStatusResponse fromManager(NonceEngineManager manager) {
        NonceModeStatusResponse resp = new NonceModeStatusResponse();
        resp.mode = manager.currentMode();
        resp.lastUpdatedAt = manager.lastModeUpdatedAt();
        EngineHealthSnapshot health = manager.currentHealth();
        if (health instanceof PerformanceHealthSnapshot) {
            PerformanceHealthSnapshot performanceHealth = (PerformanceHealthSnapshot) health;
            resp.redisHealthy = performanceHealth.isRedisHealthy();
            resp.flushHealthy = performanceHealth.isFlushHealthy();
            resp.pendingEvents = performanceHealth.getPendingEvents();
            resp.lastFlushSuccessAt = performanceHealth.getLastFlushSuccessAt();
            resp.lastFlushFailureAt = performanceHealth.getLastFlushFailureAt();
        }
        return resp;
    }

    public NonceEngineMode getMode() {
        return mode;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public boolean isRedisHealthy() {
        return redisHealthy;
    }

    public boolean isFlushHealthy() {
        return flushHealthy;
    }

    public long getPendingEvents() {
        return pendingEvents;
    }

    public Instant getLastFlushSuccessAt() {
        return lastFlushSuccessAt;
    }

    public Instant getLastFlushFailureAt() {
        return lastFlushFailureAt;
    }
}

