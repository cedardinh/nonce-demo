package com.work.nonce.demo.web.dto;

/**
 * 手动回收 nonce 时的请求体，reason 可选。
 */
public class MarkRecyclableRequest {

    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

