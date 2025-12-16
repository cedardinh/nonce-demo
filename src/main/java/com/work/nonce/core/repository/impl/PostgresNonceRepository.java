package com.work.nonce.core.repository.impl;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.exception.NonceRetryableException;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.model.NonceAllocationStatus;
import com.work.nonce.core.model.SubmitterNonceState;
import com.work.nonce.core.repository.NonceRepository;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import com.work.nonce.core.repository.entity.SubmitterNonceStateEntity;
import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import com.work.nonce.core.repository.mapper.SubmitterNonceStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PostgresNonceRepository.class);
    
    private final SubmitterNonceStateMapper stateMapper;
    private final NonceAllocationMapper allocationMapper;

    public PostgresNonceRepository(SubmitterNonceStateMapper stateMapper,
                                   NonceAllocationMapper allocationMapper) {
        this.stateMapper = stateMapper;
        this.allocationMapper = allocationMapper;
    }

    @Override
    public SubmitterNonceState loadOrCreateState(String submitter) {
        requireNonEmpty(submitter, "submitter");

        // 不加行锁：采用乐观并发控制（CAS 更新 next_local_nonce + 唯一约束 + 重试）
        SubmitterNonceStateEntity entity = stateMapper.selectBySubmitter(submitter);
        
        if (entity == null) {
            // 不存在则初始化（处理并发初始化场景）
            entity = initializeState(submitter);
        }
        
        return convertToState(entity);
    }

    @Override
    public boolean casUpdateNextLocalNonce(String submitter, long expectedNextLocalNonce, long newNextLocalNonce) {
        requireNonEmpty(submitter, "submitter");
        if (expectedNextLocalNonce < 0 || newNextLocalNonce < 0) {
            throw new IllegalArgumentException("nextLocalNonce 不能为负数");
        }
        int updated = stateMapper.casUpdateNextLocalNonce(submitter, expectedNextLocalNonce, newNextLocalNonce, Instant.now());
        return updated == 1;
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
            entity = stateMapper.selectBySubmitter(submitter);
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

        // locked_until 语义：reservation 的过期时间点（而不是 Duration）
        // 因此过期判断应为 locked_until < now

        // 先查询要回收的记录（用于返回）
        List<NonceAllocationEntity> expiredEntities = allocationMapper.findExpiredReservations(submitter, now);

        // 执行回收操作
        allocationMapper.recycleExpiredReservations(submitter, now);
        
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
    public NonceAllocation reserveNonce(String submitter, long nonce, String lockOwner, Duration reservationTtl) {
        requireNonEmpty(submitter, "submitter");
        requireNonEmpty(lockOwner, "lockOwner");
        requirePositive(reservationTtl, "reservationTtl");

        Instant now = Instant.now();
        // locked_until 语义：reservation 过期时间点
        Instant reservedUntil = now.plus(reservationTtl);
        
        // 检查是否已存在且为 USED 状态（提前检查，避免不必要的数据库操作）
        NonceAllocationEntity existing = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (existing != null && NonceAllocationStatus.USED.name().equals(existing.getStatus())) {
            throw new NonceException("nonce 已使用，不能重新分配: " + submitter + "#" + nonce);
        }

        // 乐观并发控制：
        // - 如果是 RECYCLABLE，则用 CAS 抢占（避免覆盖别人的 RESERVED）
        // - 否则只允许 INSERT 成功（ON CONFLICT DO NOTHING），冲突则交由上层重试
        if (existing != null) {
            if (NonceAllocationStatus.RECYCLABLE.name().equals(existing.getStatus())) {
                int claimed = allocationMapper.claimRecyclable(submitter, nonce, lockOwner, reservedUntil, now);
                if (claimed != 1) {
                    throw new NonceRetryableException("抢占 RECYCLABLE 失败（并发冲突），请重试: " + submitter + "#" + nonce);
                }
            } else {
                // RESERVED/其他：不覆盖，交由上层重试
                throw new NonceRetryableException("nonce 当前不可分配（并发占用中），请重试: " + submitter + "#" + nonce);
            }
        } else {
            int inserted = allocationMapper.reserveNonce(submitter, nonce, lockOwner, reservedUntil, now, now);
            if (inserted != 1) {
                // 并发插入冲突：不覆盖，交由上层重试
                throw new NonceRetryableException("reserve nonce 并发冲突，请重试: " + submitter + "#" + nonce);
            }
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
            // 这通常意味着：reservation 已超时被回收/复用，旧请求迟到回写
            log.warn("Ignoring markUsed for recyclable allocation (stale lease). submitter={}, nonce={}, txHash={}", submitter, nonce, txHash);
            return;
        }

        // 安全回写：要求 lock_owner 匹配，避免超时回收/复用后误更新
        String lockOwner = entity.getLockOwner();
        if (lockOwner == null || lockOwner.trim().isEmpty()) {
            log.warn("Ignoring markUsed without lockOwner (cannot prove lease). submitter={}, nonce={}, txHash={}", submitter, nonce, txHash);
            return;
        }
        int updated = allocationMapper.markUsedIfReserved(submitter, nonce, lockOwner, txHash, Instant.now());
        if (updated == 1) {
            return;
        }
        // 失败则重读一次，做幂等判断
        NonceAllocationEntity latest = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (latest != null && NonceAllocationStatus.USED.name().equals(latest.getStatus()) && txHash.equals(latest.getTxHash())) {
            return;
        }
        log.warn("markUsed CAS update lost (likely stale lease). submitter={}, nonce={}, txHash={}", submitter, nonce, txHash);
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
            // 迟到回写/重复回调：不抛异常，避免影响主流程
            log.warn("Ignoring markRecyclable for used allocation. submitter={}, nonce={}, reason={}", submitter, nonce, reason);
            return;
        }
        
        // 如果已经是RECYCLABLE状态，幂等处理
        if (NonceAllocationStatus.RECYCLABLE.name().equals(entity.getStatus())) {
            return;
        }

        String lockOwner = entity.getLockOwner();
        if (lockOwner == null || lockOwner.trim().isEmpty()) {
            // 无法证明租约，best-effort 不抛异常
            log.warn("Ignoring markRecyclable without lockOwner (cannot prove lease). submitter={}, nonce={}, reason={}", submitter, nonce, reason);
            return;
        }
        String finalReason = (reason == null) ? "" : reason;
        int updated = allocationMapper.markRecyclableIfReserved(submitter, nonce, lockOwner, finalReason, Instant.now());
        if (updated == 1) {
            return;
        }
        // 失败则重读一次，做幂等判断
        NonceAllocationEntity latest = allocationMapper.findBySubmitterAndNonce(submitter, nonce);
        if (latest != null && NonceAllocationStatus.RECYCLABLE.name().equals(latest.getStatus())) {
            return;
        }
        log.warn("markRecyclable CAS update lost (likely stale lease). submitter={}, nonce={}, reason={}", submitter, nonce, finalReason);
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
                    entity.getReservedUntil(),
                    entity.getTxHash(),
                    entity.getUpdatedAt()
            );
        } catch (IllegalArgumentException e) {
            throw new NonceException("无效的 allocation 状态: " + entity.getStatus() + 
                                    " for " + entity.getSubmitter() + "#" + entity.getNonce(), e);
        }
    }
}

