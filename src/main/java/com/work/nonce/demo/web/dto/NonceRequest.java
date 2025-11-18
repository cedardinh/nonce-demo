package com.work.nonce.demo.web.dto;

import javax.validation.constraints.NotBlank;

/**
 * REST 调用示例的请求体，仅包含业务 payload。
 */
public class NonceRequest {

    @NotBlank(message = "payload 不能为空")
    private String payload;

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}

