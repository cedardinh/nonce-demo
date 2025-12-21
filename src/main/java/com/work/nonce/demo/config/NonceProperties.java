package com.work.nonce.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 仅存在于 demo/业务包，用于从 application.yml 读取配置。
 * 再由配置类转换为 core 包所需的 {@link com.work.nonce.core.config.NonceConfig}。
 */
@ConfigurationProperties(prefix = "nonce")
public class NonceProperties {

    private Duration lockTtl = Duration.ofSeconds(10);
    private Duration reservedTimeout = Duration.ofSeconds(30);
    private Duration leaseTtl = Duration.ofSeconds(10);
    private String nodeId = "";

    /**
     * basic | worker-queue | auto
     * - basic: 直接执行（不做节点内按 signer 串行化）
     * - worker-queue: hash(signer) -> 固定单线程 worker 队列
     * - auto: 优先 worker-queue，遇到队列满/超时等资源压力时自动降级到 basic（并短暂熔断）
     */
    private String mode = "basic";

    /**
     * worker-queue 模式下的 worker 数（每个 worker 一个单线程队列）。
     */
    private int workerCount = 8;

    /**
     * 单个 worker 队列容量（有界，防 OOM）。
     */
    private int workerQueueCapacity = 2000;

    /**
     * dispatch 等待 worker 执行完成的超时时间（超时可触发 auto 降级）。
     */
    private Duration workerQueueDispatchTimeout = Duration.ofSeconds(3);

    /**
     * auto 模式：连续失败达到阈值后开启熔断（进入 basic）。
     */
    private int workerQueueDegradeFailThreshold = 5;

    /**
     * auto 模式：熔断开启时长。
     */
    private Duration workerQueueDegradeOpenDuration = Duration.ofSeconds(10);

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public void setReservedTimeout(Duration reservedTimeout) {
        this.reservedTimeout = reservedTimeout;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public void setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getWorkerQueueCapacity() {
        return workerQueueCapacity;
    }

    public void setWorkerQueueCapacity(int workerQueueCapacity) {
        this.workerQueueCapacity = workerQueueCapacity;
    }

    public Duration getWorkerQueueDispatchTimeout() {
        return workerQueueDispatchTimeout;
    }

    public void setWorkerQueueDispatchTimeout(Duration workerQueueDispatchTimeout) {
        this.workerQueueDispatchTimeout = workerQueueDispatchTimeout;
    }

    public int getWorkerQueueDegradeFailThreshold() {
        return workerQueueDegradeFailThreshold;
    }

    public void setWorkerQueueDegradeFailThreshold(int workerQueueDegradeFailThreshold) {
        this.workerQueueDegradeFailThreshold = workerQueueDegradeFailThreshold;
    }

    public Duration getWorkerQueueDegradeOpenDuration() {
        return workerQueueDegradeOpenDuration;
    }

    public void setWorkerQueueDegradeOpenDuration(Duration workerQueueDegradeOpenDuration) {
        this.workerQueueDegradeOpenDuration = workerQueueDegradeOpenDuration;
    }
}

