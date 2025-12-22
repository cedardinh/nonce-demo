package com.work.nonce.core.repository.impl;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.NonceAllocationStatus;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import com.work.nonce.core.repository.entity.SubmitterNonceStateEntity;
import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import com.work.nonce.core.repository.mapper.SubmitterNonceStateMapper;
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
    private static final long INITIAL_LAST_CHAIN_NONCE = -1L;
    private static final long INITIAL_NEXT_LOCAL_NONCE = 0L;
    
    private final SubmitterNonceStateMapper stateMapper;
    private final NonceAllocationMapper allocationMapper;

    public PostgresNonceRepository(SubmitterNonceStateMapper stateMapper,
                                   NonceAllocationMapper allocationMapper) {
        this.stateMapper = stateMapper;
        this.allocationMapper = allocationMapper;
    }

    @Override
    public SubmitterNonceState lockAndLoadState(String submitter) {
        requireNonEmpty(submitter, "submitter");

        // 使用 SELECT FOR UPDATE 锁定行
        SubmitterNonceStateEntity entity = stateMapper.lockAndLoadBySubmitter(submitter);
        
        if (entity == null) {
            // 不存在则初始化（处理并发初始化场景）
            entity = initializeState(submitter);
        }
        
        return convertToState(entity);
    }
    
    /**
     * 初始化submitter状态，处理并发场景
     */
    private SubmitterNonceStateEntity initializeState(String submitter) {
        Instant now = Instant.now();
        
        // 尝试插入，如果已存在则忽略（并发场景）
        stateMapper.insertIfNotExists(submitter, INITIAL_LAST_CHAIN_NONCE, 
                                     INITIAL_NEXT_LOCAL_NONCE, now, now);
        
        // 重新查询（此时应该存在），最多重试3次
        SubmitterNonceStateEntity entity = null;
        for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
            entity = stateMapper.lockAndLoadBySubmitter(submitter);
            if (entity != null) {
                break;
            }
            // 短暂等待后重试（处理极端并发场景）
            try {
                Thread.sleep(10 * (i + 1)); // 10ms, 20ms, 30ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NonceException("初始化 submitter 状态被中断: " + submitter, e);
            }
        }
        
        if (entity == null) {
            throw new NonceException("初始化 submitter 状态失败，重试后仍不存在: " + submitter);
        }
        
        return entity;
    }

    @Override
    public void updateState(SubmitterNonceState state) {
        requireNonNull(state, "state");
        requireNonEmpty(state.getSubmitter(), "state.submitter");
        
        SubmitterNonceStateEntity entity = new SubmitterNonceStateEntity();
        entity.setSubmitter(state.getSubmitter());
        entity.setLastChainNonce(state.getLastChainNonce());
        entity.setNextLocalNonce(state.getNextLocalNonce());
        entity.setUpdatedAt(state.getUpdatedAt());
        
        int updated = stateMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("更新 submitter 状态失败，记录不存在: " + state.getSubmitter());
        }
    }

    @Override
    public List<NonceAllocation> recycleExpiredReservations(String submitter, Duration reservedTimeout) {
        requireNonEmpty(submitter, "submitter");
        requirePositive(reservedTimeout, "reservedTimeout");

        Instant now = Instant.now();
        Instant expireBefore = now.minus(reservedTimeout);
        
        // 先查询要回收的记录（用于返回）
        List<NonceAllocationEntity> expiredEntities = allocationMapper.findExpiredReservations(submitter, expireBefore);
        
        // 执行回收操作
        allocationMapper.recycleExpiredReservations(submitter, expireBefore, now);
        
        // 转换为领域模型
        List<NonceAllocation> result = new ArrayList<>(expiredEntities.size());
        for (NonceAllocationEntity entity : expiredEntities) {
            result.add(convertToAllocation(entity));
        }
        
        return result;
    }

    @Override
    public Optional<NonceAllocation> findOldestRecyclable(String submitter) {
        requireNonEmpty(submitter, "submitter");

        NonceAllocationEntity entity = allocationMapper.findOldestRecyclable(submitter);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(convertToAllocation(entity));
    }

    @Override
    public NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration lockTtl) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(lockOwner, "lockOwner");
        requirePositive(lockTtl, "lockTtl");

        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lockTtl);
        
        // 检查是否已存在且为 USED 状态（提前检查，避免不必要的数据库操作）
        NonceAllocationEntity existing = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (existing != null && NonceAllocationStatus.USED.name().equals(existing.getStatus())) {
            throw new NonceException("nonce 已使用，不能重新分配: " + submitter + "#" + nonce);
        }
        
        // 使用 INSERT ... ON CONFLICT 插入或更新（原子操作）
        int updated = allocationMapper.reserveNonce(submitter, nonce, lockOwner, lockedUntil, now, now);
        
        if (updated == 0) {
            // 如果更新失败，再次检查状态（可能是并发导致状态变为USED）
            existing = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
            if (existing != null && NonceAllocationStatus.USED.name().equals(existing.getStatus())) {
                throw new NonceException("nonce 已使用，不能重新分配: " + submitter + "#" + nonce);
            }
            throw new NonceException("reserve nonce 失败: " + submitter + "#" + nonce);
        }
        
        // 查询并返回最新记录
        NonceAllocationEntity resultEntity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (resultEntity == null) {
            throw new NonceException("reserve nonce 后查询失败: " + submitter + "#" + nonce);
        }
        
        return convertToAllocation(resultEntity);
    }

    @Override
    public void markUsed(String submitter, long nonce, String txHash) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(txHash, "txHash");

        NonceAllocationEntity entity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + submitter + "#" + nonce);
        }
        
        // 状态检查
        String currentStatus = entity.getStatus();
        if (NonceAllocationStatus.USED.name().equals(currentStatus)) {
            // 幂等性：如果已经是 USED 且 txHash 相同，允许（避免重复提交）
            if (txHash.equals(entity.getTxHash())) {
                return;
            }
            throw new NonceException("nonce 已使用，不能重复标记: " + submitter + "#" + nonce);
        }
        if (NonceAllocationStatus.RECYCLABLE.name().equals(currentStatus)) {
            throw new NonceException("nonce 已回收，不能标记为 USED: " + submitter + "#" + nonce);
        }
        
        // 更新状态
        entity.setStatus(NonceAllocationStatus.USED.name());
        entity.setTxHash(txHash);
        entity.setLockOwner(null);
        entity.setLockedUntil(null);
        entity.setUpdatedAt(Instant.now());
        
        int updated = allocationMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 USED 失败: " + submitter + "#" + nonce);
        }
    }

    @Override
    public void markSubmitted(String submitter, long nonce, String txHash) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(txHash, "txHash");

        NonceAllocationEntity entity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + submitter + "#" + nonce);
        }

        // 允许幂等：如果已绑定同样 txHash，直接返回
        if (txHash.equals(entity.getTxHash()) && NonceAllocationStatus.RESERVED.name().equals(entity.getStatus())) {
            return;
        }

        // 不能对 USED / RECYCLABLE 做 submitted（说明已经终态或已回收）
        if (NonceAllocationStatus.USED.name().equals(entity.getStatus())) {
            throw new NonceException("nonce 已使用，不能标记为 submitted: " + submitter + "#" + nonce);
        }
        if (NonceAllocationStatus.RECYCLABLE.name().equals(entity.getStatus())) {
            throw new NonceException("nonce 已回收，不能标记为 submitted: " + submitter + "#" + nonce);
        }

        int updated = allocationMapper.markSubmitted(submitter, nonce, txHash, Instant.now());
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 submitted 失败: " + submitter + "#" + nonce);
        }
    }

    @Override
    public void markRecyclable(String submitter, long nonce, String reason) {
        requireNonEmpty(submitter, "submitter");

        NonceAllocationEntity entity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + submitter + "#" + nonce);
        }
        
        // 状态检查：USED 状态不能回收（保证数据一致性）
        if (NonceAllocationStatus.USED.name().equals(entity.getStatus())) {
            throw new NonceException("nonce 已使用，不能回收: " + submitter + "#" + nonce);
        }
        
        // 如果已经是RECYCLABLE状态，幂等处理
        if (NonceAllocationStatus.RECYCLABLE.name().equals(entity.getStatus())) {
            return;
        }
        
        // 更新状态
        entity.setStatus(NonceAllocationStatus.RECYCLABLE.name());
        entity.setLockOwner(null);
        entity.setLockedUntil(null);
        entity.setReason(reason != null ? reason : "");
        entity.setTxHash(null);
        entity.setUpdatedAt(Instant.now());
        
        int updated = allocationMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 RECYCLABLE 失败: " + submitter + "#" + nonce);
        }
    }

    @Override
    public List<NonceAllocation> listSubmittedReservations(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        List<NonceAllocationEntity> entities = allocationMapper.listSubmittedReservations(limit);
        List<NonceAllocation> result = new ArrayList<>(entities.size());
        for (NonceAllocationEntity e : entities) {
            result.add(convertToAllocation(e));
        }
        return result;
    }

    /**
     * 转换为领域模型
     */
    private SubmitterNonceState convertToState(SubmitterNonceStateEntity entity) {
        return new SubmitterNonceState(
                entity.getSubmitter(),
                entity.getLastChainNonce(),
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
                    entity.getSubmitter(),
                    entity.getNonce(),
                    status,
                    entity.getLockOwner(),
                    entity.getLockedUntil(),
                    entity.getTxHash(),
                    entity.getUpdatedAt()
            );
        } catch (IllegalArgumentException e) {
            throw new NonceException("无效的 allocation 状态: " + entity.getStatus() + 
                                    " for " + entity.getSubmitter() + "#" + entity.getNonce(), e);
        }
    }
}

