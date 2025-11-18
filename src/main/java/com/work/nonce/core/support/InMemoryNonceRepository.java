package com.work.nonce.core.support;

import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.NonceAllocationStatus;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯内存实现，方便在没有 Postgres 的环境下演示组件行为。
 * 注意：该实现仅用于 demo，不具备跨进程一致性。
 */
public class InMemoryNonceRepository implements NonceRepository {

    private final Map<String, SubmitterNonceState> stateTable = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, NonceAllocation>> allocationTable = new ConcurrentHashMap<>();
    private final Map<String, Object> submitterLocks = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    private Object mutex(String submitter) {
        return submitterLocks.computeIfAbsent(submitter, key -> new Object());
    }

    @Override
    public SubmitterNonceState lockAndLoadState(String submitter) {
        synchronized (mutex(submitter)) {
            SubmitterNonceState state = stateTable.computeIfAbsent(submitter, SubmitterNonceState::init);
            return new SubmitterNonceState(state.getSubmitter(), state.getLastChainNonce(),
                    state.getNextLocalNonce(), state.getUpdatedAt());
        }
    }

    @Override
    public void updateState(SubmitterNonceState state) {
        synchronized (mutex(state.getSubmitter())) {
            stateTable.put(state.getSubmitter(), state);
        }
    }

    @Override
    public List<NonceAllocation> recycleExpiredReservations(String submitter, Duration reservedTimeout) {
        Instant now = Instant.now();
        Instant expireBefore = now.minus(reservedTimeout);
        List<NonceAllocation> recycled = new ArrayList<>();
        synchronized (mutex(submitter)) {
            Map<Long, NonceAllocation> allocations =
                    allocationTable.computeIfAbsent(submitter, key -> new ConcurrentHashMap<>());
            allocations.values().forEach(allocation -> {
                if (allocation.getStatus() == NonceAllocationStatus.RESERVED
                        && allocation.getLockedUntil() != null
                        && allocation.getLockedUntil().isBefore(expireBefore)) {
                    allocation.setStatus(NonceAllocationStatus.RECYCLABLE);
                    allocation.setLockOwner(null);
                    allocation.setLockedUntil(null);
                    allocation.setUpdatedAt(now);
                    recycled.add(allocation);
                }
            });
        }
        return recycled;
    }

    @Override
    public Optional<NonceAllocation> findOldestRecyclable(String submitter) {
        synchronized (mutex(submitter)) {
            Map<Long, NonceAllocation> allocations =
                    allocationTable.computeIfAbsent(submitter, key -> new ConcurrentHashMap<>());
            return allocations.values().stream()
                    .filter(a -> a.getStatus() == NonceAllocationStatus.RECYCLABLE)
                    .min(Comparator.comparingLong(NonceAllocation::getNonce));
        }
    }

    @Override
    public NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration lockTtl) {
        synchronized (mutex(submitter)) {
            Map<Long, NonceAllocation> allocations =
                    allocationTable.computeIfAbsent(submitter, key -> new ConcurrentHashMap<>());
            NonceAllocation allocation = allocations.computeIfAbsent(nonce, key ->
                    new NonceAllocation(idGenerator.getAndIncrement(), submitter, nonce,
                            NonceAllocationStatus.RESERVED, lockOwner,
                            Instant.now().plus(lockTtl), null, Instant.now()));
            allocation.setStatus(NonceAllocationStatus.RESERVED);
            allocation.setLockOwner(lockOwner);
            allocation.setLockedUntil(Instant.now().plus(lockTtl));
            allocation.setUpdatedAt(Instant.now());
            allocations.put(nonce, allocation);
            return allocation;
        }
    }

    @Override
    public void markUsed(String submitter, long nonce, String txHash) {
        synchronized (mutex(submitter)) {
            Map<Long, NonceAllocation> allocations =
                    allocationTable.computeIfAbsent(submitter, key -> new ConcurrentHashMap<>());
            NonceAllocation allocation = allocations.get(nonce);
            if (allocation == null) {
                throw new IllegalStateException("未找到 allocation: " + submitter + "#" + nonce);
            }
            allocation.setStatus(NonceAllocationStatus.USED);
            allocation.setTxHash(txHash);
            allocation.setLockOwner(null);
            allocation.setLockedUntil(null);
            allocation.setUpdatedAt(Instant.now());
        }
    }

    @Override
    public void markRecyclable(String submitter, long nonce, String reason) {
        synchronized (mutex(submitter)) {
            Map<Long, NonceAllocation> allocations =
                    allocationTable.computeIfAbsent(submitter, key -> new ConcurrentHashMap<>());
            NonceAllocation allocation = allocations.get(nonce);
            if (allocation == null) {
                throw new IllegalStateException("未找到 allocation: " + submitter + "#" + nonce);
            }
            allocation.setStatus(NonceAllocationStatus.RECYCLABLE);
            allocation.setLockOwner(null);
            allocation.setLockedUntil(null);
            allocation.setUpdatedAt(Instant.now());
            allocation.setTxHash(null);
        }
    }
}

