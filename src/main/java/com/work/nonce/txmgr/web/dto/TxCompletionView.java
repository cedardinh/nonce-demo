package com.work.nonce.txmgr.web.dto;

import java.time.Instant;
import java.util.UUID;

public class TxCompletionView {
    private Long seq;
    private UUID txId;
    private Instant time;
    private String status;

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public UUID getTxId() {
        return txId;
    }

    public void setTxId(UUID txId) {
        this.txId = txId;
    }

    public Instant getTime() {
        return time;
    }

    public void setTime(Instant time) {
        this.time = time;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}


