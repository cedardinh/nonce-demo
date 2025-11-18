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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 PostgreSQL + MyBatis-Plus 的生产级 NonceRepository 实现
 * 
 * 注意：所有方法都必须在事务中调用，特别是 allocate 相关的操作
 */
@Repository
public class PostgresNonceRepository implements NonceRepository {

    private final SubmitterNonceStateMapper stateMapper;
    private final NonceAllocationMapper allocationMapper;

    public PostgresNonceRepository(SubmitterNonceStateMapper stateMapper,
                                   NonceAllocationMapper allocationMapper) {
        this.stateMapper = stateMapper;
        this.allocationMapper = allocationMapper;
    }

    @Override
    @Transactional(readOnly = false)
    public SubmitterNonceState lockAndLoadState(String submitter) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }

        // 使用 SELECT FOR UPDATE 锁定行
        SubmitterNonceStateEntity entity = stateMapper.lockAndLoadBySubmitter(submitter);
        
        if (entity == null) {
            // 不存在则初始化
            Instant now = Instant.now();
            SubmitterNonceStateEntity newEntity = new SubmitterNonceStateEntity();
            newEntity.setSubmitter(submitter);
            newEntity.setLastChainNonce(-1L);
            newEntity.setNextLocalNonce(0L);
            newEntity.setUpdatedAt(now);
            newEntity.setCreatedAt(now);
            
            // 尝试插入，如果已存在则忽略（并发场景）
            stateMapper.insertIfNotExists(submitter, -1L, 0L, now, now);
            
            // 重新查询（此时应该存在）
            entity = stateMapper.lockAndLoadBySubmitter(submitter);
            if (entity == null) {
                throw new NonceException("初始化 submitter 状态失败: " + submitter);
            }
        }
        
        return convertToState(entity);
    }

    @Override
    @Transactional(readOnly = false)
    public void updateState(SubmitterNonceState state) {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为空");
        }
        
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
    @Transactional(readOnly = false)
    public List<NonceAllocation> recycleExpiredReservations(String submitter, Duration reservedTimeout) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (reservedTimeout == null || reservedTimeout.isNegative() || reservedTimeout.isZero()) {
            throw new IllegalArgumentException("reservedTimeout 必须大于0");
        }

        Instant now = Instant.now();
        Instant expireBefore = now.minus(reservedTimeout);
        
        // 先查询要回收的记录（用于返回）
        List<NonceAllocationEntity> expiredEntities = allocationMapper.findExpiredReservations(submitter, expireBefore);
        
        // 执行回收操作
        allocationMapper.recycleExpiredReservations(submitter, expireBefore, now);
        
        // 转换为领域模型
        List<NonceAllocation> result = new ArrayList<>();
        for (NonceAllocationEntity entity : expiredEntities) {
            result.add(convertToAllocation(entity));
        }
        
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NonceAllocation> findOldestRecyclable(String submitter) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }

        NonceAllocationEntity entity = allocationMapper.findOldestRecyclable(submitter);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(convertToAllocation(entity));
    }

    @Override
    @Transactional(readOnly = false)
    public NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration lockTtl) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (lockOwner == null || lockOwner.trim().isEmpty()) {
            throw new IllegalArgumentException("lockOwner 不能为空");
        }
        if (lockTtl == null || lockTtl.isNegative() || lockTtl.isZero()) {
            throw new IllegalArgumentException("lockTtl 必须大于0");
        }

        Instant now = Instant.now();
        Instant lockedUntil = now.plus(lockTtl);
        
        // 检查是否已存在且为 USED 状态
        NonceAllocationEntity existing = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (existing != null && "USED".equals(existing.getStatus())) {
            throw new NonceException("nonce 已使用，不能重新分配: " + submitter + "#" + nonce);
        }
        
        // 使用 INSERT ... ON CONFLICT 插入或更新
        int updated = allocationMapper.reserveNonce(submitter, nonce, lockOwner, lockedUntil, now, now);
        
        if (updated == 0) {
            // 如果更新失败（可能是状态为USED），抛出异常
            existing = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
            if (existing != null && "USED".equals(existing.getStatus())) {
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
    @Transactional(readOnly = false)
    public void markUsed(String submitter, long nonce, String txHash) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }
        if (txHash == null || txHash.trim().isEmpty()) {
            throw new IllegalArgumentException("txHash 不能为空");
        }

        NonceAllocationEntity entity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + submitter + "#" + nonce);
        }
        
        // 状态检查
        if ("USED".equals(entity.getStatus())) {
            // 幂等性：如果已经是 USED 且 txHash 相同，允许
            if (txHash.equals(entity.getTxHash())) {
                return;
            }
            throw new NonceException("nonce 已使用，不能重复标记: " + submitter + "#" + nonce);
        }
        if ("RECYCLABLE".equals(entity.getStatus())) {
            throw new NonceException("nonce 已回收，不能标记为 USED: " + submitter + "#" + nonce);
        }
        
        // 更新状态
        entity.setStatus("USED");
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
    @Transactional(readOnly = false)
    public void markRecyclable(String submitter, long nonce, String reason) {
        if (submitter == null || submitter.trim().isEmpty()) {
            throw new IllegalArgumentException("submitter 不能为空");
        }

        NonceAllocationEntity entity = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (entity == null) {
            throw new NonceException("未找到 allocation: " + submitter + "#" + nonce);
        }
        
        // 状态检查：USED 状态不能回收
        if ("USED".equals(entity.getStatus())) {
            throw new NonceException("nonce 已使用，不能回收: " + submitter + "#" + nonce);
        }
        
        // 更新状态
        entity.setStatus("RECYCLABLE");
        entity.setLockOwner(null);
        entity.setLockedUntil(null);
        entity.setReason(reason);
        entity.setTxHash(null);
        entity.setUpdatedAt(Instant.now());
        
        int updated = allocationMapper.updateById(entity);
        if (updated == 0) {
            throw new NonceException("标记 nonce 为 RECYCLABLE 失败: " + submitter + "#" + nonce);
        }
    }

    private SubmitterNonceState convertToState(SubmitterNonceStateEntity entity) {
        return new SubmitterNonceState(
                entity.getSubmitter(),
                entity.getLastChainNonce(),
                entity.getNextLocalNonce(),
                entity.getUpdatedAt()
        );
    }

    private NonceAllocation convertToAllocation(NonceAllocationEntity entity) {
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
    }
}

