package com.work.nonce.demo.config;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.lock.RedisLockManager;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.core.support.InMemoryNonceRepository;
import com.work.nonce.core.support.InMemoryRedisLockManager;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.MockChainClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将核心组件装配为 Spring Bean，方便通过依赖注入复用。
 * 若以后需要接入真实的 Redis/Postgres，只需替换对应 Bean。
 */
@Configuration
@EnableConfigurationProperties(NonceProperties.class)
public class NonceComponentConfiguration {

    @Bean
    public NonceRepository nonceRepository() {
        return new InMemoryNonceRepository();
    }

    @Bean
    public RedisLockManager redisLockManager() {
        return new InMemoryRedisLockManager();
    }

    @Bean
    public ChainClient chainClient() {
        return new MockChainClient();
    }

    @Bean
    public NonceConfig nonceConfig(NonceProperties properties) {
        return new NonceConfig(
                properties.isRedisEnabled(),
                properties.getLockTtl(),
                properties.getReservedTimeout(),
                properties.isDegradeOnRedisFailure()
        );
    }

    @Bean
    public NonceService nonceService(NonceRepository repository,
                                     RedisLockManager redisLockManager,
                                     NonceConfig nonceConfig) {
        return new NonceService(repository, redisLockManager, nonceConfig);
    }

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

