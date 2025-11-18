package com.work.nonce.core.support;

import com.work.nonce.core.exception.NonceException;
import com.work.nonce.core.lock.RedisLockManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 事务同步管理器，确保Redis锁在事务提交后释放
 * 解决锁释放时机问题，避免在事务提交前释放锁导致的并发问题
 */
public final class TransactionLockSynchronizer {

    private TransactionLockSynchronizer() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 在事务中执行操作，并在事务提交后释放锁
     * 
     * @param lockManager Redis锁管理器
     * @param submitter submitter标识
     * @param lockOwner 锁持有者标识
     * @param lockTtl 锁超时时间
     * @param degradeOnFailure 是否在失败时降级（不抛异常，继续执行）
     * @param operation 需要执行的操作
     * @return 操作结果
     */
    public static <T> T executeWithLock(RedisLockManager lockManager,
                                        String submitter,
                                        String lockOwner,
                                        Duration lockTtl,
                                        boolean degradeOnFailure,
                                        Supplier<T> operation) {
        boolean locked = false;
        try {
            // 尝试获取锁
            locked = lockManager.tryLock(submitter, lockOwner, lockTtl);
            
            // 如果获取锁失败且不允许降级，抛出异常
            if (!locked && !degradeOnFailure) {
                throw new NonceException("Redis 加锁失败，且未开启降级");
            }
            
            // 如果获取到锁，注册事务同步回调
            if (locked && TransactionSynchronizationManager.isActualTransactionActive()) {
                final String finalSubmitter = submitter;
                final String finalLockOwner = lockOwner;
                
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // 事务提交后释放锁
                            releaseLockSafely(lockManager, finalSubmitter, finalLockOwner);
                        }
                        
                        @Override
                        public void afterCompletion(int status) {
                            // 如果事务回滚，也需要释放锁
                            if (status != STATUS_COMMITTED) {
                                releaseLockSafely(lockManager, finalSubmitter, finalLockOwner);
                            }
                        }
                    }
                );
            }
            
            // 执行操作
            return operation.get();
            
        } catch (NonceException e) {
            // 重新抛出NonceException
            throw e;
        } catch (Exception e) {
            // 如果是Redis异常且允许降级，继续执行
            if (degradeOnFailure && !locked) {
                return operation.get();
            }
            // 如果操作失败且锁已获取，立即释放锁（不在事务中）
            if (locked && !TransactionSynchronizationManager.isActualTransactionActive()) {
                releaseLockSafely(lockManager, submitter, lockOwner);
            }
            throw new NonceException("Redis 加锁异常", e);
        }
    }
    
    /**
     * 安全释放锁，捕获所有异常避免影响主流程
     */
    private static void releaseLockSafely(RedisLockManager lockManager, String submitter, String lockOwner) {
        try {
            lockManager.unlock(submitter, lockOwner);
        } catch (Exception e) {
            // 记录日志但不抛异常，避免影响主流程
            // 生产环境应该使用日志框架记录
        }
    }
}

