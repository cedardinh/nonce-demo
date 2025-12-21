package com.work.nonce.core.repository.impl;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.exception.LeaseNotOwnedException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.NonceAllocationStatus;
import com.work.nonce.core.model.SignerNonceState;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import com.work.nonce.core.repository.entity.SignerNonceStateEntity;
import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import com.work.nonce.core.repository.mapper.SignerLeaseMapper;
import com.work.nonce.core.repository.mapper.SignerNonceStateMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;
import static com.work.nonce.core.support.ValidationUtils.requirePositive;

/**
 * 基于 PostgreSQL + MyBatis-Plus 的生产级 NonceRepository 实现
 * 
 * 注意：
 * 1. 所有方法都必须在事务中调用，事务边界由Service层统一管理
 * 2. 移除了@Transactional注解，避免事务嵌套问题
 * 3. 增强了参数校验和异常处理
 */
@Repository
public class PostgresNonceRepository implements NonceRepository {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_NEXT_LOCAL_NONCE = 0L;
    
    private final SignerNonceStateMapper stateMapper;
    private final NonceAllocationMapper allocationMapper;
    private final SignerLeaseMapper leaseMapper;

    public PostgresNonceRepository(SignerNonceStateMapper stateMapper,
                                   NonceAllocationMapper allocationMapper,
                                   SignerLeaseMapper leaseMapper) {
        this.stateMapper = stateMapper;
        this.allocationMapper = allocationMapper;
        this.leaseMapper = leaseMapper;
    }

    @Override
    public Long acquireOrRenewLease(String signer, String ownerId, Duration leaseTtl) {
        requireNonEmpty(signer, "signer");
        requireNonEmpty(ownerId, "ownerId");
        requirePositive(leaseTtl, "leaseTtl");

        Instant now = Instant.now();
        Instant expiresAt = now.plus(leaseTtl);
        return leaseMapper.acquireOrRenewLease(signer, ownerId, now, expiresAt);
    }

    @Override
    public SignerNonceState lockAndLoadState(String signer) {
        requireNonEmpty(signer, "signer");

        // 使用 SELECT FOR UPDATE 锁定行
        SignerNonceStateEntity entity = stateMapper.lockAndLoadBySigner(signer);
        
        if (entity == null) {
            // 不存在则初始化（处理并发初始化场景）
            entity = initializeState(signer);
        }
        
        return convertToState(entity);
    }

    @Override
    public void ensureStateExists(String signer) {
        requireNonEmpty(signer, "signer");
        Instant now = Instant.now();
        stateMapper.insertIfNotExists(signer, INITIAL_NEXT_LOCAL_NONCE, now, now);
    }

    @Override
    public Long loadNextLocalNonce(String signer) {
        requireNonEmpty(signer, "signer");
        return stateMapper.selectNextLocalNonce(signer);
    }

    @Override
    public int casAdvanceNextLocalNonce(String signer, long expected, long newValue, long fencingToken, Instant now) {
        requireNonEmpty(signer, "signer");
        return stateMapper.casAdvanceNextLocalNonce(signer, expected, newValue, fencingToken, now);
    }

    @Override
    public Long claimOldestRecyclable(String signer, Instant lockedUntil, Instant now, long fencingToken) {
        requireNonEmpty(signer, "signer");
        requireNonNull(lockedUntil, "lockedUntil");
        requireNonNull(now, "now");
        return allocationMapper.claimOldestRecyclable(signer, lockedUntil, now, fencingToken);
    }
    
    /**
     * 初始化submitter状态，处理并发场景
     */
    private SignerNonceStateEntity initializeState(String signer) {
        Instant now = Instant.now();
        
        // 尝试插入，如果已存在则忽略（并发场景）
        stateMapper.insertIfNotExists(signer, INITIAL_NEXT_LOCAL_NONCE, now, now);
        
        // 重新查询（此时应该存在），最多重试3次
        SignerNonceStateEntity entity = null;
        for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
            entity = stateMapper.lockAndLoadBySigner(signer);
            if (entity != null) {
                break;
            }
            // 短暂等待后重试（处理极端并发场景）
            try {
                Thread.sleep(10 * (i + 1)); // 10ms, 20ms, 30ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NonceException("初始化 signer 状态被中断: " + signer, e);
            }
        }
        
        if (entity == null) {
            throw new NonceException("初始化 signer 状态失败，重试后仍不存在: " + signer);
        }
        
        return entity;
    }

    @Override
    public void updateState(SignerNonceState state) {
        requireNonNull(state, "state");
        requireNonEmpty(state.getSigner(), "state.signer");
        
        SignerNonceStateEntity entity = new SignerNonceStateEntity();
        entity.setSigner(state.getSigner());
        entity.setNextLocalNonce(state.getNextLocalNonce());
        entity.setUpdatedAt(state.getUpdatedAt());
        
        int updated = stateMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("更新 signer 状态失败，记录不存在: " + state.getSigner());
        }
    }

    @Override
    public List<NonceAllocation> recycleExpiredReservations(String signer, Duration reservedTimeout) {
        requireNonEmpty(signer, "signer");
        requirePositive(reservedTimeout, "reservedTimeout");

        Instant now = Instant.now();
        Instant expireBefore = now.minus(reservedTimeout);
        
        // 先查询要回收的记录（用于返回）
        List<NonceAllocationEntity> expiredEntities = allocationMapper.findExpiredReservations(signer, expireBefore);
        
        // 执行回收操作
        allocationMapper.recycleExpiredReservations(signer, expireBefore, now);
        
        // 转换为领域模型
        List<NonceAllocation> result = new ArrayList<>(expiredEntities.size());
        for (NonceAllocationEntity entity : expiredEntities) {
            result.add(convertToAllocation(entity));
        }
        
        return result;
    }

    @Override
    public List<NonceAllocation> recycleExpiredReservationsFenced(String signer, Duration reservedTimeout, long fencingToken) {
        requireNonEmpty(signer, "signer");
        requirePositive(reservedTimeout, "reservedTimeout");

        Instant now = Instant.now();
        Instant expireBefore = now.minus(reservedTimeout);

        List<NonceAllocationEntity> expiredEntities = allocationMapper.findExpiredReservations(signer, expireBefore);
        allocationMapper.recycleExpiredReservationsFenced(signer, expireBefore, now, fencingToken);

        List<NonceAllocation> result = new ArrayList<>(expiredEntities.size());
        for (NonceAllocationEntity entity : expiredEntities) {
            result.add(convertToAllocation(entity));
        }
        return result;
    }

    @Override
    public Optional<NonceAllocation> findOldestRecyclable(String signer) {
        requireNonEmpty(signer, "signer");

        NonceAllocationEntity entity = allocationMapper.findOldestRecyclable(signer);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(convertToAllocation(entity));
    }

    @Override
    public NonceAllocation reserveNonce(String signer, long nonce, Duration lockTtl) {
        requireNonEmpty(signer, "signer");
        requirePositive(lockTtl, "lockTtl");

        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lockTtl);
        
        // 检查是否已存在且为 CONSUMED 状态（提前检查，避免不必要的数据库操作）
        NonceAllocationEntity existing = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (existing != null && isConsumedStatus(existing.getStatus())) {
            throw new NonceException("nonce 已使用，不能重新分配: " + signer + "#" + nonce);
        }
        
        // 使用 INSERT ... ON CONFLICT 插入或更新（原子操作）
        int updated = allocationMapper.reserveNonce(signer, nonce, lockedUntil, now, now, 0L);
        
        if (updated == 0) {
            // 如果更新失败，再次检查状态（可能是并发导致状态变为 CONSUMED）
            existing = allocationMapper.findBySignerAndNonce(signer, nonce);
            if (existing != null && isConsumedStatus(existing.getStatus())) {
                throw new NonceException("nonce 已使用，不能重新分配: " + signer + "#" + nonce);
            }
            throw new NonceException("reserve nonce 失败: " + signer + "#" + nonce);
        }
        
        // 查询并返回最新记录
        NonceAllocationEntity resultEntity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (resultEntity == null) {
            throw new NonceException("reserve nonce 后查询失败: " + signer + "#" + nonce);
        }
        
        return convertToAllocation(resultEntity);
    }

    @Override
    public NonceAllocation reserveNonceFenced(String signer, long nonce, Duration lockTtl, long fencingToken) {
        requireNonEmpty(signer, "signer");
        requirePositive(lockTtl, "lockTtl");

        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lockTtl);

        int updated = allocationMapper.reserveNonce(signer, nonce, lockedUntil, now, now, fencingToken);
        if (updated == 0) {
            throw new LeaseNotOwnedException("reserve nonce fenced 被 fencing 拒绝（可能 lease 丢失或 nonce 已终局）: " + signer + "#" + nonce);
        }
        NonceAllocationEntity resultEntity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (resultEntity == null) {
            throw new NonceException("reserve nonce fenced 后查询失败: " + signer + "#" + nonce);
        }
        return convertToAllocation(resultEntity);
    }

    @Override
    public void markUsed(String signer, long nonce, String txHash) {
        requireNonEmpty(signer, "signer");
        requireNonEmpty(txHash, "txHash");

        NonceAllocationEntity entity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + signer + "#" + nonce);
        }
        
        // 状态检查
        String currentStatus = entity.getStatus();
        if (isConsumedStatus(currentStatus)) {
            // 幂等性：如果已经是 CONSUMED 且 txHash 相同，允许（避免重复提交）
            if (txHash.equals(entity.getTxHash())) {
                return;
            }
            throw new NonceException("nonce 已使用，不能重复标记: " + signer + "#" + nonce);
        }
        if (isReleasedStatus(currentStatus)) {
            throw new NonceException("nonce 已释放，不能标记为 CONSUMED: " + signer + "#" + nonce);
        }
        
        // 更新状态
        entity.setStatus(NonceAllocationStatus.CONSUMED.name());
        entity.setTxHash(txHash);
        entity.setLockedUntil(null);
        entity.setUpdatedAt(Instant.now());
        
        int updated = allocationMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 CONSUMED 失败: " + signer + "#" + nonce);
        }
    }

    @Override
    public void markUsedFenced(String signer, long nonce, String txHash, long fencingToken) {
        requireNonEmpty(signer, "signer");
        requireNonEmpty(txHash, "txHash");

        NonceAllocationEntity entity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + signer + "#" + nonce);
        }

        // 幂等：已消费且 txHash 相同允许
        if (isConsumedStatus(entity.getStatus())) {
            if (txHash.equals(entity.getTxHash())) {
                return;
            }
            throw new NonceException("nonce 已使用，不能重复标记: " + signer + "#" + nonce);
        }
        if (isReleasedStatus(entity.getStatus())) {
            throw new NonceException("nonce 已释放，不能标记为 CONSUMED: " + signer + "#" + nonce);
        }

        int updated = allocationMapper.markUsedFenced(entity.getId(), txHash, Instant.now(), fencingToken);
        if (updated == 0) {
            throw new LeaseNotOwnedException("标记 nonce 为 CONSUMED 被 fencing 拒绝（可能 lease 丢失）: " + signer + "#" + nonce);
        }
    }

    @Override
    public void markRecyclable(String signer, long nonce, String reason) {
        requireNonEmpty(signer, "signer");

        NonceAllocationEntity entity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + signer + "#" + nonce);
        }
        
        // 状态检查：CONSUMED 状态不能释放（保证数据一致性）
        if (isConsumedStatus(entity.getStatus())) {
            throw new NonceException("nonce 已终局消费，不能释放: " + signer + "#" + nonce);
        }
        
        // 如果已经是 RELEASED 状态，幂等处理
        if (isReleasedStatus(entity.getStatus())) {
            return;
        }
        
        // 更新状态
        entity.setStatus(NonceAllocationStatus.RELEASED.name());
        entity.setLockedUntil(null);
        entity.setReason(reason != null ? reason : "");
        entity.setTxHash(null);
        entity.setUpdatedAt(Instant.now());
        
        int updated = allocationMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 RELEASED 失败: " + signer + "#" + nonce);
        }
    }

    @Override
    public void markRecyclableFenced(String signer, long nonce, String reason, long fencingToken) {
        requireNonEmpty(signer, "signer");

        NonceAllocationEntity entity = allocationMapper.findBySignerAndNonce(signer, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + signer + "#" + nonce);
        }

        if (isConsumedStatus(entity.getStatus())) {
            throw new NonceException("nonce 已终局消费，不能释放: " + signer + "#" + nonce);
        }
        if (isReleasedStatus(entity.getStatus())) {
            return;
        }

        int updated = allocationMapper.markRecyclableFenced(entity.getId(), reason != null ? reason : "", Instant.now(), fencingToken);
        if (updated == 0) {
            throw new LeaseNotOwnedException("标记 nonce 为 RELEASED 被 fencing 拒绝（可能 lease 丢失）: " + signer + "#" + nonce);
        }
    }

    /**
     * 转换为领域模型
     */
    private SignerNonceState convertToState(SignerNonceStateEntity entity) {
        return new SignerNonceState(
                entity.getSigner(),
                entity.getNextLocalNonce(),
                entity.getUpdatedAt()
        );
    }

    /**
     * 转换为领域模型，处理状态枚举转换
     */
    private NonceAllocation convertToAllocation(NonceAllocationEntity entity) {
        try {
            NonceAllocationStatus status = NonceAllocationStatus.valueOf(entity.getStatus());
            return new NonceAllocation(
                    entity.getId(),
                    entity.getSigner(),
                    entity.getNonce(),
                    status,
                    entity.getLockedUntil(),
                    entity.getTxHash(),
                    entity.getUpdatedAt()
            );
        } catch (IllegalArgumentException e) {
            throw new NonceException("无效的 allocation 状态: " + entity.getStatus() + 
                                    " for " + entity.getSigner() + "#" + entity.getNonce(), e);
        }
    }

    private static boolean isConsumedStatus(String dbStatus) {
        return NonceAllocationStatus.CONSUMED.name().equals(dbStatus);
    }

    private static boolean isReleasedStatus(String dbStatus) {
        return NonceAllocationStatus.RELEASED.name().equals(dbStatus);
    }
}

