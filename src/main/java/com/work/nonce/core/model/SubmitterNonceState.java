package com.work.nonce.core.model;

import java.time.Instant;

/**
 * 对应 README 中的 submitter_nonce_state 表。
 */
public class SubmitterNonceState {

    private final String submitter;
    private long lastChainNonce;
    private long nextLocalNonce;
    private Instant updatedAt;

    public SubmitterNonceState(String submitter, long lastChainNonce, long nextLocalNonce, Instant updatedAt) {
        this.submitter = submitter;
        this.lastChainNonce = lastChainNonce;
        this.nextLocalNonce = nextLocalNonce;
        this.updatedAt = updatedAt;
    }

    public static SubmitterNonceState init(String submitter) {
        return new SubmitterNonceState(submitter, -1, 0, Instant.now());
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getLastChainNonce() {
        return lastChainNonce;
    }

    public void setLastChainNonce(long lastChainNonce) {
        this.lastChainNonce = lastChainNonce;
    }

    public long getNextLocalNonce() {
        return nextLocalNonce;
    }

    public void setNextLocalNonce(long nextLocalNonce) {
        this.nextLocalNonce = nextLocalNonce;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

