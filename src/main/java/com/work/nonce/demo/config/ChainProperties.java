package com.work.nonce.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 链连接配置（demo/宿主侧）。
 *
 * mode=mock: 使用 MockChainClient
 * mode=web3j: 使用 Web3jChainClient
 */
@ConfigurationProperties(prefix = "chain")
public class ChainProperties {

    /**
     * mock 或 web3j
     */
    private String mode = "mock";

    /**
     * Web3j HTTP RPC 地址，例如 http://localhost:8545
     */
    private String rpcUrl = "http://localhost:8545";

    /**
     * RPC 请求超时（当前用于占位，具体超时由底层 HTTP 客户端决定）
     */
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRpcUrl() {
        return rpcUrl;
    }

    public void setRpcUrl(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}


