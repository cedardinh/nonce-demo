package com.work.nonce.demo.web.dto;

import com.work.nonce.core.model.NonceAllocation;

/**
 * 低阶接口示例：返回 allocate 结果，方便前端或调试工具查看锁持有者与当前状态。
 */
public class ManualNonceAllocationResponse {

    private final String submitter;
    private final long nonce;
    private final String status;
    private final String lockOwner;
    private final String message;

    private ManualNonceAllocationResponse(String submitter,
                                          long nonce,
                                          String status,
                                          String lockOwner,
                                          String message) {
        this.submitter = submitter;
        this.nonce = nonce;
        this.status = status;
        this.lockOwner = lockOwner;
        this.message = message;
    }

    public static ManualNonceAllocationResponse fromAllocation(NonceAllocation allocation, String message) {
        return new ManualNonceAllocationResponse(
                allocation.getSubmitter(),
                allocation.getNonce(),
                allocation.getStatus().name(),
                allocation.getLockOwner(),
                message
        );
    }

    public String getSubmitter() {
        return submitter;
    }

    public long getNonce() {
        return nonce;
    }

    public String getStatus() {
        return status;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public String getMessage() {
        return message;
    }
}

