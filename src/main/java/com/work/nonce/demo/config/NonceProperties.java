package com.work.nonce.demo.config;

import com.work.nonce.core.config.AllocationStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅存在于 demo/业务包，用于从 application.yml 读取配置。
 * 再由配置类转换为 core 包所需的 {@link com.work.nonce.core.config.NonceConfig}。
 */
@ConfigurationProperties(prefix = "nonce")
public class NonceProperties {

    private boolean redisEnabled = true;
    private Duration lockTtl = Duration.ofSeconds(10);
    private Duration reservedTimeout = Duration.ofSeconds(30);
    private boolean degradeOnRedisFailure = true;

    /**
     * 分配策略：
     * - CACHE_RANGE：适用于 submitter 粘性路由/一致性哈希场景（默认）
     * - DB_ONLY：适用于 submitter 轮询到不同节点场景（避免本地缓存 thrash）
     */
    private AllocationStrategy allocationStrategy = AllocationStrategy.CACHE_RANGE;

    /**
     * submitter 级别覆盖：这些 submitter 强制走 DB_ONLY。
     *
     * <p>适用于混合场景：大多数 submitter 可粘性路由，但少数 submitter 会被轮询到不同节点。</p>
     */
    private List<String> dbOnlySubmitters = new ArrayList<>();

    // 缓存配置
    private boolean cacheEnabled = true;
    private int cacheSize = 1000;
    private Duration cacheTimeout = Duration.ofHours(1);
    private int preAllocateSize = 50;

    // 链上查询配置
    private boolean chainQueryEnabled = true;
    private int chainQueryMaxRetries = 3;

    // 过期回收降频
    private Duration recycleThrottle = Duration.ofSeconds(5);

    /**
     * PENDING（隔离态）的最大持续时间。超过该时间会触发对账任务进行“定案”（ACCEPTED 或 RECYCLABLE）。
     */
    private Duration pendingMaxAge = Duration.ofMinutes(5);

    /**
     * PENDING（隔离态）的硬上限。超过该时间不再自动定案/回收，进入“冻结/人工处理”路径（只告警与保留）。
     */
    private Duration pendingHardMaxAge = Duration.ofHours(2);

    /**
     * 是否启用定时对账任务（处理超时 RESERVED -> PENDING，及 PENDING 定案）。
     */
    private boolean reconcileEnabled = true;

    /**
     * 每次对账任务处理的最大记录数（批量大小）。
     */
    private int reconcileBatchSize = 200;

    /**
     * 对账任务执行间隔（fixedDelay，毫秒）。
     */
    private long reconcileIntervalMs = 5000L;

    /**
     * 是否启用历史数据清理（建议生产改为分区/归档；demo 提供可选的批量删除）。
     */
    private boolean cleanupEnabled = false;

    /**
     * 热窗口保留天数（建议 7~14 天）。
     */
    private int hotRetentionDays = 14;

    /**
     * 清理任务间隔（毫秒）。
     */
    private long cleanupIntervalMs = 3600_000L;

    /**
     * 单次清理最大删除条数（避免长事务）。
     */
    private int cleanupBatchSize = 5_000;

    // 账户是否可能被外部系统共用
    private boolean sharedAccount = false;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

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

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }

    public void setDegradeOnRedisFailure(boolean degradeOnRedisFailure) {
        this.degradeOnRedisFailure = degradeOnRedisFailure;
    }

    public AllocationStrategy getAllocationStrategy() {
        return allocationStrategy;
    }

    public void setAllocationStrategy(AllocationStrategy allocationStrategy) {
        this.allocationStrategy = allocationStrategy;
    }

    public List<String> getDbOnlySubmitters() {
        return dbOnlySubmitters;
    }

    public void setDbOnlySubmitters(List<String> dbOnlySubmitters) {
        this.dbOnlySubmitters = dbOnlySubmitters;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public Duration getCacheTimeout() {
        return cacheTimeout;
    }

    public void setCacheTimeout(Duration cacheTimeout) {
        this.cacheTimeout = cacheTimeout;
    }

    public int getPreAllocateSize() {
        return preAllocateSize;
    }

    public void setPreAllocateSize(int preAllocateSize) {
        this.preAllocateSize = preAllocateSize;
    }

    public boolean isChainQueryEnabled() {
        return chainQueryEnabled;
    }

    public void setChainQueryEnabled(boolean chainQueryEnabled) {
        this.chainQueryEnabled = chainQueryEnabled;
    }

    public int getChainQueryMaxRetries() {
        return chainQueryMaxRetries;
    }

    public void setChainQueryMaxRetries(int chainQueryMaxRetries) {
        this.chainQueryMaxRetries = chainQueryMaxRetries;
    }

    public Duration getRecycleThrottle() {
        return recycleThrottle;
    }

    public void setRecycleThrottle(Duration recycleThrottle) {
        this.recycleThrottle = recycleThrottle;
    }

    public Duration getPendingMaxAge() {
        return pendingMaxAge;
    }

    public void setPendingMaxAge(Duration pendingMaxAge) {
        this.pendingMaxAge = pendingMaxAge;
    }

    public Duration getPendingHardMaxAge() {
        return pendingHardMaxAge;
    }

    public void setPendingHardMaxAge(Duration pendingHardMaxAge) {
        this.pendingHardMaxAge = pendingHardMaxAge;
    }

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public int getHotRetentionDays() {
        return hotRetentionDays;
    }

    public void setHotRetentionDays(int hotRetentionDays) {
        this.hotRetentionDays = hotRetentionDays;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }

    public boolean isSharedAccount() {
        return sharedAccount;
    }

    public void setSharedAccount(boolean sharedAccount) {
        this.sharedAccount = sharedAccount;
    }
}

