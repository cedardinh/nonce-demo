package com.work.nonce.demo.config;

import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.web3j.Web3jChainClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * Web3j 装配：
 * 当 chain.mode=web3j 时启用。
 */
@Configuration
@ConditionalOnProperty(prefix = "chain", name = "mode", havingValue = "web3j")
public class Web3jConfiguration {

    @Bean
    public Web3j web3j(ChainProperties properties) {
        // 这里使用 HTTP RPC；如需订阅区块事件，可改为 WebSocketService 并引入对应依赖与生命周期管理
        return Web3j.build(new HttpService(properties.getRpcUrl()));
    }

    @Bean
    public ChainClient web3jChainClient(Web3j web3j) {
        return new Web3jChainClient(web3j);
    }
}


