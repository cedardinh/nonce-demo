package com.work.nonce.demo.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量串行发送场景的请求体，允许一次性提交多个 payload。
 */
public class BatchNonceRequest {

    @NotEmpty(message = "payload 列表不能为空")
    private List<@NotBlank(message = "payload 不能为空") String> payloads;

    public List<String> getPayloads() {
        return payloads;
    }

    public void setPayloads(List<String> payloads) {
        this.payloads = payloads;
    }
}

