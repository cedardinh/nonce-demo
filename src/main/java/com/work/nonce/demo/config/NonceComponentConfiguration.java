package com.work.nonce.demo.config;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.execution.NonceResultProcessor;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.MockChainClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 将核心组件装配为 Spring Bean，方便通过依赖注入复用。
 * 生产环境使用 PostgreSQL + Redis 实现。
 */
@Configuration
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

    // NonceService 通过 @Service 自动扫描，不需要手动创建 Bean

    @Bean
    public NonceResultProcessor nonceResultProcessor(NonceService nonceService) {
        return new NonceResultProcessor(nonceService);
    }

    @Bean
    public NonceExecutionTemplate nonceExecutionTemplate(NonceService nonceService,
                                                         NonceResultProcessor resultProcessor) {
        return new NonceExecutionTemplate(nonceService, resultProcessor);
    }

    @Bean
    public NonceComponent nonceComponent(NonceExecutionTemplate template,
                                         NonceService nonceService) {
        return new NonceComponent(template, nonceService);
    }
}

