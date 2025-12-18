package com.work.nonce.txmgr.domain;

import java.time.Instant;

public class LeaseDecision {
    private final boolean leader;
    private final long fencingToken;
    private final Instant expiresAt;

    public LeaseDecision(boolean leader, long fencingToken, Instant expiresAt) {
        this.leader = leader;
        this.fencingToken = fencingToken;
        this.expiresAt = expiresAt;
    }

    public boolean isLeader() {
        return leader;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}


