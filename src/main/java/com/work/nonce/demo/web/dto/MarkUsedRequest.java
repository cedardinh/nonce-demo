package com.work.nonce.demo.web.dto;

import javax.validation.constraints.NotBlank;

/**
 * 手动标记 USED 时需要的请求参数。
 */
public class MarkUsedRequest {

    @NotBlank(message = "txHash 不能为空")
    private String txHash;

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }
}

