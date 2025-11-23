package com.work.nonce.demo.config;

import com.work.nonce.core.NonceFacade;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.execution.NonceResultProcessor;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.MockChainClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 将核心组件装配为 Spring Bean，方便通过依赖注入复用。
 * 生产环境使用 PostgreSQL + Redis 实现。
 */
@Configuration
@EnableConfigurationProperties(NonceProperties.class)
public class NonceComponentConfiguration {

    // PostgresNonceRepository 和 RedisDistributedLockManager 通过 @Repository 和 @Component 自动扫描
    // 不需要手动创建 Bean，Spring 会自动注入

    /**
     * ChainClient 实现（业务方需要替换为自己的实现）
     */
    @Bean
    public ChainClient chainClient() {
        // 生产环境应该替换为真实的链客户端实现
        return new MockChainClient();
    }

    @Bean
    public NonceConfig nonceConfig(NonceProperties properties) {
        return properties.toConfig();
    }

    @Bean
    public NonceResultProcessor nonceResultProcessor(NonceAllocationEngine allocationEngine) {
        return new NonceResultProcessor(allocationEngine);
    }

    @Bean
    public NonceExecutionTemplate nonceExecutionTemplate(NonceAllocationEngine allocationEngine,
                                                         NonceResultProcessor resultProcessor) {
        return new NonceExecutionTemplate(allocationEngine, resultProcessor);
    }

    @Bean
    public NonceFacade nonceFacade(NonceExecutionTemplate template,
                                   NonceAllocationEngine allocationEngine) {
        return new NonceFacade(template, allocationEngine);
    }
}

