package com.work.nonce.demo.config;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.MockChainClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将核心组件装配为 Spring Bean，方便通过依赖注入复用。
 * 生产环境使用 PostgreSQL（乐观锁/CAS + 唯一约束 + 重试退避 + 短事务 + 条件更新）。
 */
@Configuration
@EnableConfigurationProperties(NonceProperties.class)
public class NonceComponentConfiguration {

    // PostgresNonceRepository 通过 @Repository 自动扫描
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
        return new NonceConfig(
                properties.getReservedTimeout(),
                properties.getAllocateMaxAttempts(),
                properties.getBackoffBase(),
                properties.getBackoffMax()
        );
    }

    // NonceService 通过 @Service 自动扫描，不需要手动创建 Bean

    @Bean
    public NonceExecutionTemplate nonceExecutionTemplate(NonceService nonceService) {
        return new NonceExecutionTemplate(nonceService);
    }

    @Bean
    public NonceComponent nonceComponent(NonceExecutionTemplate template,
                                         NonceService nonceService) {
        return new NonceComponent(template, nonceService);
    }
}

