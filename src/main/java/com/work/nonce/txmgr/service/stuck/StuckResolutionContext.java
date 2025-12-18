package com.work.nonce.txmgr.service.stuck;

import java.time.Instant;
import java.util.UUID;

/**
 * STUCK 候选的上下文信息（只读）。
 */
public class StuckResolutionContext {

    private final Instant now;
    private final String submitter;
    private final UUID txId;
    private final Long nonce;
    private final String txHash;
    private final String state;
    private final String lastError;
    private final int submitAttempts;
    private final int maxAttempts;
    private final long ageMillis;
    private final long pendingQueueDepth;

    public StuckResolutionContext(Instant now,
                                  String submitter,
                                  UUID txId,
                                  Long nonce,
                                  String txHash,
                                  String state,
                                  String lastError,
                                  int submitAttempts,
                                  int maxAttempts,
                                  long ageMillis,
                                  long pendingQueueDepth) {
        this.now = now;
        this.submitter = submitter;
        this.txId = txId;
        this.nonce = nonce;
        this.txHash = txHash;
        this.state = state;
        this.lastError = lastError;
        this.submitAttempts = submitAttempts;
        this.maxAttempts = maxAttempts;
        this.ageMillis = ageMillis;
        this.pendingQueueDepth = pendingQueueDepth;
    }

    public Instant getNow() {
        return now;
    }

    public String getSubmitter() {
        return submitter;
    }

    public UUID getTxId() {
        return txId;
    }

    public Long getNonce() {
        return nonce;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public int getSubmitAttempts() {
        return submitAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getAgeMillis() {
        return ageMillis;
    }

    public long getPendingQueueDepth() {
        return pendingQueueDepth;
    }
}


