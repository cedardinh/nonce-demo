package com.work.nonce.core.engine.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.engine.spi.NonceAllocationEngine;
import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.SubmitterLockCoordinator;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.NonceAllocationStatus;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 主导的性能模式实现，配合 {@link NonceFlushQueue} 完成“快写 Redis → 异步刷盘”。
 */
@Service
public class PerformanceNonceEngine implements NonceAllocationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceNonceEngine.class);
    /**
     * 每个 submitter 的计数器 key 前缀。
     */
    private static final String COUNTER_KEY_TEMPLATE = "nonce:counter:%s";
    /**
     * 可复用池 ZSet key 前缀。
     */
    private static final String POOL_KEY_TEMPLATE = "nonce:recycle:%s";
    /**
     * Allocation 快照哈希 key 前缀。
     */
    private static final String CACHE_KEY_TEMPLATE = "nonce:alloc:%s";
    /**
     * 写入 RESERVED 时使用的锁 owner 标识。
     */
    public static final String PERFORMANCE_LOCK_OWNER = "performance-engine";

    /**
     * Redis 客户端，操作计数器/回收池/缓存。
     */
    private final StringRedisTemplate redisTemplate;
    /**
     * submitter 级锁，保证 Redis 与逻辑的一致性。
     */
    private final SubmitterLockCoordinator lockCoordinator;
    /**
     * DB 访问层，用于对账与刷盘。
     */
    private final NonceRepository nonceRepository;
    /**
     * 组件配置。
     */
    private final NonceConfig config;
    /**
     * 用于序列化 AllocationSnapshot。
     */
    private final ObjectMapper objectMapper;
    /**
     * 刷盘队列，用于投递事件。
     */
    private final NonceFlushQueue flushQueue;
    /**
     * 用于为 Redis snapshot 补全一个唯一 ID。
     */
    private final AtomicLong syntheticId = new AtomicLong(1L << 50);
    /**
     * 保证计数器单调递增的 Lua 脚本。
     */
    private final DefaultRedisScript<Long> counterMaxScript;
    /**
     * 性能链路健康度跟踪器。
     */
    private final PerformanceHealthTracker healthTracker = new PerformanceHealthTracker();

    public PerformanceNonceEngine(StringRedisTemplate redisTemplate,
                                  SubmitterLockCoordinator lockCoordinator,
                                  NonceRepository nonceRepository,
                                  NonceConfig config,
                                  ObjectMapper objectMapper,
                                  NonceFlushQueue flushQueue) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.lockCoordinator = Objects.requireNonNull(lockCoordinator, "lockCoordinator");
        this.nonceRepository = Objects.requireNonNull(nonceRepository, "nonceRepository");
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.flushQueue = Objects.requireNonNull(flushQueue, "flushQueue");
        this.counterMaxScript = buildCounterMaxScript();
    }


    /**
     * 性能模式下的 allocate：利用 Redis 计数器/回收池生成 nonce，并投递刷盘事件。
     *
     * @param submitter 业务标识
     * @return 由 Redis 生成的 RESERVED 记录
     */
    @Override
    public NonceAllocation allocate(String submitter) {
        ensureEnabled();
        return lockCoordinator.executeWithLock(submitter, owner -> doAllocate(submitter));
    }


    /**
     * 标记 nonce 为 USED：同步更新 Redis 快照、回收池，并投递刷盘事件。
     */
    @Override
    public void markUsed(String submitter, long nonce, String txHash) {
        ensureEnabled();
        lockCoordinator.executeWithLock(submitter, owner -> {
            AllocationSnapshot snapshot = loadSnapshot(submitter, nonce)
                    .orElse(AllocationSnapshot.synthetic(submitter, nonce, false));
            snapshot.markUsed(txHash);
            persistSnapshot(snapshot);
            removeFromRecyclePool(submitter, nonce);
            flushQueue.publish(PerformanceFlushEvent.markUsed(snapshot));
            return null;
        });
    }


    /**
     * 标记 nonce 为 RECYCLABLE：写入快照并放回回收池，等待下次复用。
     */
    @Override
    public void markRecyclable(String submitter, long nonce, String reason) {
        ensureEnabled();
        lockCoordinator.executeWithLock(submitter, owner -> {
            AllocationSnapshot snapshot = loadSnapshot(submitter, nonce)
                    .orElse(AllocationSnapshot.synthetic(submitter, nonce, false));
            snapshot.markRecyclable(reason);
            persistSnapshot(snapshot);
            addToRecyclePool(submitter, nonce);
            flushQueue.publish(PerformanceFlushEvent.markRecyclable(snapshot));
            return null;
        });
    }

    /**
     * @return 是否启用了性能模式
     */
    public boolean isEnabled() {
        return config.isRedisEnabled();
    }

    /**
     * @return Redis 异常时是否自动降级
     */
    public boolean isAutoDegradeEnabled() {
        return config.isDegradeOnRedisFailure();
    }

    /**
     * DUAL_WRITE 阶段调用：将可靠模式的分配镜像到 Redis，并校准计数器。
     */
    public void mirrorReservation(NonceAllocation allocation) {
        if (!isEnabled()) {
            return;
        }
        AllocationSnapshot snapshot = AllocationSnapshot.fromAllocation(allocation, true);
        try {
            // 确保计数器存在：从 DB 同步最新 nextLocalNonce
            ensureCounterInitialized(allocation.getSubmitter());
            //使用 Lua 将计数器提升至指定值（仅允许更大）
            raiseCounterTo(allocation.getSubmitter(), allocation.getNonce());
            // 将最新的 AllocationSnapshot 写入 Redis 哈希，供重放与查询使用
            persistSnapshot(snapshot);
        } catch (RuntimeException ex) {
            healthTracker.redisFailure(ex);
            throw ex;
        }
    }

    /**
     * DUAL_WRITE 阶段调用：镜像 reliable 模式的 USED 事件。
     */
    public void mirrorMarkUsed(String submitter, long nonce, String txHash) {
        if (!isEnabled()) {
            return;
        }
        AllocationSnapshot snapshot = loadSnapshot(submitter, nonce)
                .orElse(AllocationSnapshot.synthetic(submitter, nonce, false));
        snapshot.markUsed(txHash);
        persistSnapshot(snapshot);
        deleteSnapshot(submitter, nonce);
        removeFromRecyclePool(submitter, nonce);
    }

    /**
     * DUAL_WRITE 阶段调用：镜像 reliable 模式的 RECYCLABLE 事件。
     */
    public void mirrorMarkRecyclable(String submitter, long nonce, String reason) {
        if (!isEnabled()) {
            return;
        }
        AllocationSnapshot snapshot = loadSnapshot(submitter, nonce)
                .orElse(AllocationSnapshot.synthetic(submitter, nonce, false));
        snapshot.markRecyclable(reason);
        persistSnapshot(snapshot);
        addToRecyclePool(submitter, nonce);
    }

    /**
     * 校验性能模式切换前的必要条件（队列需清空等）。
     */
    public void verifySwitchReady() {
        if (!isEnabled()) {
            throw new NonceException("Redis 性能模式未启用");
        }
        if (flushQueue.pendingSize() > 0) {
            throw new NonceException("刷盘队列未清空，无法切换到性能模式");
        }
    }

    /**
     * 阻塞等待刷盘队列清空，供 DRAIN_AND_SYNC 使用。
     */
    public void awaitDrainCompletion() {
        if (!isEnabled()) {
            return;
        }
        Duration drainTimeout = config.getDrainTimeout();
        long deadline = System.currentTimeMillis() + drainTimeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (flushQueue.pendingSize() == 0) {
                return;
            }
            sleepQuietly(100);
        }
        throw new NonceException("等待 flush 队列清空超时");
    }

    /**
     * @return 当前的性能链路健康快照
     */
    public PerformanceHealthSnapshot healthSnapshot() {
        return new PerformanceHealthSnapshot(
                healthTracker.isRedisHealthy(),
                healthTracker.isFlushHealthy(),
                flushQueue.pendingSize(),
                healthTracker.getLastFlushSuccessAt(),
                healthTracker.getLastFlushFailureAt());
    }

    /**
     * 被 flush worker 调用，记录一次成功刷盘。
     */
    public void recordFlushSuccess(int processedCount) {
        healthTracker.flushSuccess(processedCount);
    }

    /**
     * 被 flush worker 调用，记录一次刷盘失败。
     */
    public void recordFlushFailure(Exception ex) {
        healthTracker.flushFailure(ex);
    }

    /**
     * 刷盘成功后调用，清理内存快照（例如 USED 记录可以删除）。
     */
    public void onFlushCommitted(PerformanceFlushEvent event) {
        if (!isEnabled()) {
            return;
        }
        if (event.getType() == PerformanceFlushEvent.Type.MARK_USED) {
            deleteSnapshot(event.getSubmitter(), event.getNonce());
        }
    }

    /**
     * 内部实现：串行执行选号、落缓存、写入 flush 队列。
     */
    private NonceAllocation doAllocate(String submitter) {
        try {
            AllocationSnapshot snapshot = pickNonce(submitter);
            persistSnapshot(snapshot);
            flushQueue.publish(PerformanceFlushEvent.reserve(snapshot));
            return snapshot.toAllocation(syntheticId.incrementAndGet());
        } catch (RuntimeException ex) {
            healthTracker.redisFailure(ex);
            throw ex;
        }
    }

    /**
     * 先尝试从回收池弹出，否则使用自增计数器。
     */
    private AllocationSnapshot pickNonce(String submitter) {
        Long recycled = popRecycle(submitter);
        if (recycled != null) {
            return AllocationSnapshot.reserved(submitter, recycled, false);
        }
        long nonce = nextCounter(submitter);
        return AllocationSnapshot.reserved(submitter, nonce, true);
    }

    /**
     * 递增并返回 submitter 的 Redis 计数器。
     */
    private long nextCounter(String submitter) {
        ensureCounterInitialized(submitter);
        Long next = redisTemplate.opsForValue().increment(counterKey(submitter));
        if (next == null) {
            throw new NonceException("Redis 计数器返回 null");
        }
        return next;
    }

    /**
     * 确保计数器存在：从 DB 同步最新 nextLocalNonce。
     */
    private void ensureCounterInitialized(String submitter) {
        String key = counterKey(submitter);
        Boolean exists = redisTemplate.hasKey(key);
        if (exists) {
            return;
        }
        SubmitterNonceState state = nonceRepository.lockAndLoadState(submitter);
        long initial = Math.max(-1, state.getNextLocalNonce() - 1);
        redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(initial));
    }

    /**
     * 使用 Lua 将计数器提升至指定值（仅允许更大）。
     */
    private void raiseCounterTo(String submitter, long nonce) {
        String key = counterKey(submitter);
        redisTemplate.execute(counterMaxScript, Collections.singletonList(key), String.valueOf(nonce));
    }

    /**
     * 从回收池弹出最小 nonce，若没有则返回 null。
     */
    private Long popRecycle(String submitter) {
        String key = recycleKey(submitter);
        ZSetOperations.TypedTuple<String> tuple = redisTemplate.opsForZSet().popMin(key);
        if (tuple == null) {
            return null;
        }
        try {
            return Long.parseLong(tuple.getValue());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 将 nonce 放入回收池 ZSet，分值即 nonce 大小。
     */
    private void addToRecyclePool(String submitter, long nonce) {
        String key = recycleKey(submitter);
        redisTemplate.opsForZSet().add(key, String.valueOf(nonce), nonce);
    }

    /**
     * 从回收池删除指定 nonce。
     */
    private void removeFromRecyclePool(String submitter, long nonce) {
        String key = recycleKey(submitter);
        redisTemplate.opsForZSet().remove(key, String.valueOf(nonce));
    }

    /**
     * 将最新的 AllocationSnapshot 写入 Redis 哈希，供重放与查询使用。
     */
    private void persistSnapshot(AllocationSnapshot snapshot) {
        String key = cacheKey(snapshot.getSubmitter());
        try {
            String payload = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForHash().put(key, String.valueOf(snapshot.getNonce()), payload);
        } catch (JsonProcessingException e) {
            throw new NonceException("序列化 AllocationSnapshot 失败", e);
        }
    }

    /**
     * 从 Redis 哈希加载某个 nonce 的快照，解析失败时自动清理脏数据。
     */
    private Optional<AllocationSnapshot> loadSnapshot(String submitter, long nonce) {
        String key = cacheKey(submitter);
        Object payload = redisTemplate.opsForHash().get(key, String.valueOf(nonce));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            AllocationSnapshot snapshot = objectMapper.readValue(payload.toString(), AllocationSnapshot.class);
            return Optional.of(snapshot);
        } catch (JsonProcessingException e) {
            redisTemplate.opsForHash().delete(key, String.valueOf(nonce));
            return Optional.empty();
        }
    }

    /**
     * 刷盘完成或状态终结后，从缓存中删除对应快照。
     */
    private void deleteSnapshot(String submitter, long nonce) {
        String key = cacheKey(submitter);
        redisTemplate.opsForHash().delete(key, String.valueOf(nonce));
    }

    /**
     * 构建“计数器取最大值”Lua 脚本，保证单调递增。
     */
    private DefaultRedisScript<Long> buildCounterMaxScript() {
        String script = "local current = redis.call('get', KEYS[1]) "
                + "if (not current) or (tonumber(ARGV[1]) > tonumber(current)) then "
                + "redis.call('set', KEYS[1], ARGV[1]) "
                + "return tonumber(ARGV[1]) "
                + "else "
                + "return tonumber(current) "
                + "end";
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setResultType(Long.class);
        redisScript.setScriptText(script);
        return redisScript;
    }

    /**
     * 计算计数器 key。
     */
    private String counterKey(String submitter) {
        return String.format(COUNTER_KEY_TEMPLATE, submitter);
    }

    /**
     * 计算回收池 key。
     */
    private String recycleKey(String submitter) {
        return String.format(POOL_KEY_TEMPLATE, submitter);
    }

    /**
     * 计算 allocation 快照哈希 key。
     */
    private String cacheKey(String submitter) {
        return String.format(CACHE_KEY_TEMPLATE, submitter);
    }

    /**
     * 若性能模式未启用则抛出异常，防止误调用。
     */
    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new NonceException("性能模式已关闭");
        }
    }

    /**
     * 包装 Thread.sleep，忽略中断但恢复线程标记。
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Redis 中缓存的 nonce 视图。
     */
    public static class AllocationSnapshot {

        /**
         * submitter 标识。
         */
        private String submitter;
        /**
         * 当前 nonce 值。
         */
        private long nonce;
        /**
         * 是否需要推进 nextLocalNonce。
         */
        private boolean incrementsState;
        /**
         * 当前状态（RESERVED / USED / RECYCLABLE）。
         */
        private NonceAllocationStatus status;
        /**
         * 链上交易哈希，仅用于 USED。
         */
        private String txHash;
        /**
         * 回收理由，仅用于 RECYCLABLE。
         */
        private String reason;
        /**
         * 最近一次更新时间戳（毫秒）。
         */
        private long updatedAt;

        public AllocationSnapshot() {
        }

        public static AllocationSnapshot reserved(String submitter, long nonce, boolean incrementsState) {
            AllocationSnapshot snapshot = new AllocationSnapshot();
            snapshot.submitter = submitter;
            snapshot.nonce = nonce;
            snapshot.incrementsState = incrementsState;
            snapshot.status = NonceAllocationStatus.RESERVED;
            snapshot.updatedAt = Instant.now().toEpochMilli();
            return snapshot;
        }

        public static AllocationSnapshot fromAllocation(NonceAllocation allocation, boolean incrementsState) {
            AllocationSnapshot snapshot = new AllocationSnapshot();
            snapshot.submitter = allocation.getSubmitter();
            snapshot.nonce = allocation.getNonce();
            snapshot.incrementsState = incrementsState;
            snapshot.status = allocation.getStatus();
            snapshot.txHash = allocation.getTxHash();
            snapshot.updatedAt = Instant.now().toEpochMilli();
            return snapshot;
        }

        public static AllocationSnapshot synthetic(String submitter, long nonce, boolean incrementsState) {
            return reserved(submitter, nonce, incrementsState);
        }

        /**
         * 将快照转换为领域模型对象，供上层返回给业务。
         */
        public NonceAllocation toAllocation(long syntheticId) {
            return new NonceAllocation(
                    syntheticId,
                    submitter,
                    nonce,
                    status,
                    PERFORMANCE_LOCK_OWNER,
                    txHash,
                    Instant.ofEpochMilli(updatedAt)
            );
        }

        /**
         * 标记为 USED 并刷新更新时间。
         */
        public void markUsed(String txHash) {
            this.status = NonceAllocationStatus.USED;
            this.txHash = txHash;
            this.updatedAt = Instant.now().toEpochMilli();
        }

        /**
         * 标记为 RECYCLABLE，记录原因。
         */
        public void markRecyclable(String reason) {
            this.status = NonceAllocationStatus.RECYCLABLE;
            this.reason = reason;
            this.updatedAt = Instant.now().toEpochMilli();
        }

        /**
         * @return submitter
         */
        public String getSubmitter() {
            return submitter;
        }

        /**
         * @return nonce 值
         */
        public long getNonce() {
            return nonce;
        }

        /**
         * @return 是否需推进 nextLocalNonce
         */
        public boolean isIncrementsState() {
            return incrementsState;
        }

        /**
         * @return 当前状态
         */
        public NonceAllocationStatus getStatus() {
            return status;
        }

        /**
         * @return 链上哈希
         */
        public String getTxHash() {
            return txHash;
        }

        /**
         * @return 回收原因
         */
        public String getReason() {
            return reason;
        }

        /**
         * @return 更新时间戳
         */
        public long getUpdatedAt() {
            return updatedAt;
        }

        /**
         * 设置 submitter（供反序列化使用）。
         */
        public void setSubmitter(String submitter) {
            this.submitter = submitter;
        }

        /**
         * 设置 nonce 值。
         */
        public void setNonce(long nonce) {
            this.nonce = nonce;
        }

        /**
         * 设置是否触发 nextLocalNonce 自增。
         */
        public void setIncrementsState(boolean incrementsState) {
            this.incrementsState = incrementsState;
        }

        /**
         * 设置当前状态。
         */
        public void setStatus(NonceAllocationStatus status) {
            this.status = status;
        }

        /**
         * 设置交易哈希。
         */
        public void setTxHash(String txHash) {
            this.txHash = txHash;
        }

        /**
         * 设置回收原因。
         */
        public void setReason(String reason) {
            this.reason = reason;
        }

        /**
         * 设置更新时间戳。
         */
        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    /**
     * 简单的健康状态追踪器。
     */
    private static class PerformanceHealthTracker {
        private volatile boolean redisHealthy = true;
        private volatile boolean flushHealthy = true;
        private volatile Instant lastFlushSuccessAt = Instant.EPOCH;
        private volatile Instant lastFlushFailureAt = Instant.EPOCH;

        /**
         * 记录 Redis 异常，标记链路不健康。
         */
        void redisFailure(Exception ex) {
            redisHealthy = false;
            LOGGER.error("[nonce] redis operation failed", ex);
        }

        /**
         * 刷盘成功后恢复健康标记，并刷新成功时间。
         */
        void flushSuccess(int processedCount) {
            redisHealthy = true;
            flushHealthy = true;
            lastFlushSuccessAt = Instant.now();
        }

        /**
         * 刷盘失败时记录失败时间，供外部降级判断。
         */
        void flushFailure(Exception ex) {
            flushHealthy = false;
            lastFlushFailureAt = Instant.now();
            LOGGER.error("[nonce] flush worker failed", ex);
        }

        boolean isRedisHealthy() {
            return redisHealthy;
        }

        boolean isFlushHealthy() {
            return flushHealthy;
        }

        Instant getLastFlushSuccessAt() {
            return lastFlushSuccessAt;
        }

        Instant getLastFlushFailureAt() {
            return lastFlushFailureAt;
        }
    }
}

