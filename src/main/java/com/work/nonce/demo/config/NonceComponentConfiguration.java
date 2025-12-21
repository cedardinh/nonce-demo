package com.work.nonce.demo.config;

import com.work.nonce.core.NonceComponent;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.execution.AutoDegradingSignerExecutor;
import com.work.nonce.core.execution.DirectSignerExecutor;
import com.work.nonce.core.execution.SignerExecutor;
import com.work.nonce.core.execution.WorkerQueueSignerExecutor;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.MockChainClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * 将核心组件装配为 Spring Bean，方便通过依赖注入复用。
 * 生产环境使用 PostgreSQL + Redis 实现。
 */
@Configuration
@EnableConfigurationProperties(NonceProperties.class)
public class NonceComponentConfiguration {

    // PostgresNonceRepository 通过 @Repository 自动扫描

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
        String nodeId = properties.getNodeId();
        if (nodeId == null || nodeId.trim().isEmpty()) {
            nodeId = UUID.randomUUID().toString();
        }
        return new NonceConfig(
                properties.getLockTtl(),
                properties.getReservedTimeout(),
                properties.getLeaseTtl(),
                nodeId
        );
    }

    // NonceService 通过 @Service 自动扫描，不需要手动创建 Bean

    @Bean
    public NonceExecutionTemplate nonceExecutionTemplate(NonceService nonceService) {
        return new NonceExecutionTemplate(nonceService);
    }

    @Bean
    public SignerExecutor signerExecutor(NonceProperties properties) {
        // basic / worker-queue / auto 统一由 SignerExecutor 抽象实现
        String mode = properties.getMode() == null ? "basic" : properties.getMode().trim().toLowerCase();
        SignerExecutor basic = new DirectSignerExecutor();

        switch (mode) {
            case "worker-queue": {
                return new WorkerQueueSignerExecutor(
                        Math.max(1, properties.getWorkerCount()),
                        Math.max(1, properties.getWorkerQueueCapacity()),
                        properties.getWorkerQueueDispatchTimeout(),
                        "nonce-worker-"
                );
            }
            case "auto": {
                SignerExecutor worker = new WorkerQueueSignerExecutor(
                        Math.max(1, properties.getWorkerCount()),
                        Math.max(1, properties.getWorkerQueueCapacity()),
                        properties.getWorkerQueueDispatchTimeout(),
                        "nonce-worker-"
                );
                return new AutoDegradingSignerExecutor(
                        worker,
                        basic,
                        Math.max(1, properties.getWorkerQueueDegradeFailThreshold()),
                        properties.getWorkerQueueDegradeOpenDuration()
                );
            }
            case "basic":
            default:
                return basic;
        }
    }

    @Bean
    public NonceComponent nonceComponent(NonceExecutionTemplate template,
                                         NonceService nonceService,
                                         SignerExecutor signerExecutor) {
        return new NonceComponent(template, nonceService, signerExecutor);
    }
}

