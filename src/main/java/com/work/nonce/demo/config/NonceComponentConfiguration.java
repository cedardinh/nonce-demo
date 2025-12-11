package com.work.nonce.demo.config;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.cache.NonceCacheManager;
import com.work.nonce.core.chain.ChainNonceClient;
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
        return new NonceConfig(
                properties.isRedisEnabled(),
                properties.getLockTtl(),
                properties.getReservedTimeout(),
                properties.isDegradeOnRedisFailure(),
                properties.isCacheEnabled(),
                properties.getCacheSize(),
                properties.getCacheTimeout(),
                properties.isChainQueryEnabled(),
                properties.getChainQueryMaxRetries()
        );
    }

    @Bean
    public ChainNonceClient chainNonceClient(ChainClient chainClient) {
        // demo 的 queryLatestNonce 返回的是“已使用的最高 nonce”，这里转换为“下一个可用 nonce”
        return submitter -> {
            long latestUsed = chainClient.queryLatestNonce(submitter);
            return latestUsed < 0 ? -1L : (latestUsed + 1);
        };
    }

    @Bean
    public NonceCacheManager nonceCacheManager(NonceConfig nonceConfig) {
        return new NonceCacheManager(nonceConfig);
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

