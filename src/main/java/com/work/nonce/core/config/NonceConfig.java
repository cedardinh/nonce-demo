package com.work.nonce.core.config;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 纯组件侧的配置定义，不依赖任意框架。宿主应用（如 Spring Boot）只需在装配时
 * 将自身读取到的配置参数注入即可，确保 core 包保持与业务、框架解耦。
 */
public class NonceConfig {

    private final boolean redisEnabled;
    private final Duration lockTtl;
    private final Duration reservedTimeout;
    private final boolean degradeOnRedisFailure;

    /**
     * 分配策略：决定是否值得启用“本地缓存 + 区间预分配”等优化。
     * 正确性始终由 DB 原子语义 + 唯一约束兜底。
     */
    private final AllocationStrategy allocationStrategy;

    /**
     * submitter 级别的策略覆盖：这些 submitter 会强制走 DB_ONLY（适用于轮询/非粘性路由）。
     *
     * <p>说明：该列表只影响“是否使用本地缓存/区间预分配”的选择，不改变正确性兜底逻辑。</p>
     */
    private final Set<String> dbOnlySubmitters;

    // 缓存相关
    private final boolean cacheEnabled;
    private final int cacheSize;
    private final Duration cacheTimeout;
    /**
     * 从 DB 一次性预分配的 nonce 数量（区间大小）。
     * 取值越大，越能减少 state 行锁竞争，但进程崩溃/回滚时可能引入更多“未使用 gap”（可接受）。
     */
    private final int preAllocateSize;

    // 链上查询相关
    private final boolean chainQueryEnabled;
    private final int chainQueryMaxRetries;

    // 过期回收降频（同一 submitter 的最小回收间隔）
    private final Duration recycleThrottle;

    /**
     * PENDING（隔离态）的最大持续时间，超过后由对账逻辑决定 ACCEPTED 或 RECYCLABLE。
     */
    private final Duration pendingMaxAge;

    /**
     * PENDING（隔离态）的硬上限：超过后不再自动回收/定案，进入人工处理（只保留 + 告警）。
     */
    private final Duration pendingHardMaxAge;

    /**
     * 是否启用定时对账（demo/宿主侧可能用到；core 仅存储配置）。
     */
    private final boolean reconcileEnabled;

    private final int reconcileBatchSize;

    /**
     * 账户是否可能被外部系统共用（shared account）。
     * true 时会启用更保守策略：更频繁查链、预分配区间缩小（建议为1）、回收前查链确认等。
     */
    private final boolean sharedAccount;

    public NonceConfig(boolean redisEnabled,
                       Duration lockTtl,
                       Duration reservedTimeout,
                       boolean degradeOnRedisFailure,
                       AllocationStrategy allocationStrategy,
                       Set<String> dbOnlySubmitters,
                       boolean cacheEnabled,
                       int cacheSize,
                       Duration cacheTimeout,
                       int preAllocateSize,
                       boolean chainQueryEnabled,
                       int chainQueryMaxRetries,
                       Duration recycleThrottle,
                       Duration pendingMaxAge,
                       Duration pendingHardMaxAge,
                       boolean reconcileEnabled,
                       int reconcileBatchSize,
                       boolean sharedAccount) {
        this.redisEnabled = redisEnabled;
        this.lockTtl = lockTtl;
        this.reservedTimeout = reservedTimeout;
        this.degradeOnRedisFailure = degradeOnRedisFailure;
        this.allocationStrategy = allocationStrategy == null ? AllocationStrategy.CACHE_RANGE : allocationStrategy;
        this.dbOnlySubmitters = dbOnlySubmitters == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(dbOnlySubmitters));
        this.cacheEnabled = cacheEnabled;
        this.cacheSize = cacheSize;
        this.cacheTimeout = cacheTimeout;
        this.preAllocateSize = preAllocateSize;
        this.chainQueryEnabled = chainQueryEnabled;
        this.chainQueryMaxRetries = chainQueryMaxRetries;
        this.recycleThrottle = recycleThrottle;
        this.pendingMaxAge = pendingMaxAge;
        this.pendingHardMaxAge = pendingHardMaxAge;
        this.reconcileEnabled = reconcileEnabled;
        this.reconcileBatchSize = reconcileBatchSize;
        this.sharedAccount = sharedAccount;
    }

    public static NonceConfig defaultConfig() {
        return new NonceConfig(
                true,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                true,
                AllocationStrategy.CACHE_RANGE,
                Collections.emptySet(),
                true,
                1000,
                Duration.ofHours(1),
                50,
                true,
                3,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                Duration.ofHours(2),
                true,
                200,
                false
        );
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public Duration getReservedTimeout() {
        return reservedTimeout;
    }

    public boolean isDegradeOnRedisFailure() {
        return degradeOnRedisFailure;
    }

    public AllocationStrategy getAllocationStrategy() {
        return allocationStrategy;
    }

    public boolean isDbOnlyStrategy() {
        return allocationStrategy == AllocationStrategy.DB_ONLY;
    }

    public boolean isCacheRangeStrategy() {
        return allocationStrategy == AllocationStrategy.CACHE_RANGE;
    }

    public Set<String> getDbOnlySubmitters() {
        return dbOnlySubmitters;
    }

    public AllocationStrategy resolveStrategy(String submitter) {
        if (submitter != null && dbOnlySubmitters.contains(submitter)) {
            return AllocationStrategy.DB_ONLY;
        }
        return allocationStrategy;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * 缓存“是否真的能带来净收益”。
     *
     * <p>例如轮询打到不同节点时，本地缓存/区间缓存会发生 thrash（净收益为负），因此强制关闭。</p>
     */
    public boolean isCacheEffective() {
        return isCacheEffective(null);
    }

    public boolean isCacheEffective(String submitter) {
        if (!cacheEnabled) {
            return false;
        }
        if (resolveStrategy(submitter) == AllocationStrategy.DB_ONLY) {
            return false;
        }
        if (sharedAccount) {
            // sharedAccount 下 batchSize 强制为 1，本地缓存基本无意义
            return false;
        }
        return preAllocateSize > 1;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public Duration getCacheTimeout() {
        return cacheTimeout;
    }

    public int getPreAllocateSize() {
        return preAllocateSize;
    }

    public boolean isChainQueryEnabled() {
        return chainQueryEnabled;
    }

    public int getChainQueryMaxRetries() {
        return chainQueryMaxRetries;
    }

    public Duration getRecycleThrottle() {
        return recycleThrottle;
    }

    public Duration getPendingMaxAge() {
        return pendingMaxAge;
    }

    public Duration getPendingHardMaxAge() {
        return pendingHardMaxAge;
    }

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public boolean isSharedAccount() {
        return sharedAccount;
    }
}

