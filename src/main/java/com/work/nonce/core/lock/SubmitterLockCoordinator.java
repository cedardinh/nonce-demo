package com.work.nonce.core.lock;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.exception.NonceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetAddress;
import java.util.UUID;

import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * submitter 维度的互斥协调器，集中管理 Redis 锁的获取/释放与事务联动。
 */
@Component
public class SubmitterLockCoordinator {

    private final RedisLockManager redisLockManager;
    private final NonceConfig config;

    public SubmitterLockCoordinator(RedisLockManager redisLockManager, NonceConfig config) {
        this.redisLockManager = requireNonNull(redisLockManager, "redisLockManager");
        this.config = requireNonNull(config, "config");
    }

    @FunctionalInterface
    public interface LockCallback<T> {
        T doInLock(String lockOwner);
    }

    public <T> T executeWithLock(String submitter, LockCallback<T> action) {
        requireNonNull(action, "action");

        final String lockOwner = buildLockOwner();
        boolean locked = false;
        boolean releaseByTxCallback = false;
        try {
            locked = acquire(submitter, lockOwner);
            releaseByTxCallback = registerReleaseCallback(submitter, lockOwner);
            return action.doInLock(lockOwner);
        } catch (RuntimeException ex) {
            throw ex;
        } finally {
            if (locked && !releaseByTxCallback) {
                releaseSafely(submitter, lockOwner);
            }
        }
    }

    private boolean acquire(String submitter, String owner) {
        if (redisLockManager.tryLock(submitter, owner, config.getLockTtl())) {
            return true;
        }
        throw new NonceException("Redis lock contention: " + submitter);
    }

    /**
     * 向当前线程绑定的事务注册一个回调（钩子）。
     * <p>
     * 若处于事务中，则在 commit/rollback 后释放锁；否则交由调用方 finally 释放。
     */
    private boolean registerReleaseCallback(String submitter, String owner) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseSafely(submitter, owner);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    releaseSafely(submitter, owner);
                }
            }
        });
        return true;
    }

    /**
     * 释放锁时忽略异常，避免在 finally 或事务钩子中抛出新的错误。
     */
    private void releaseSafely(String submitter, String owner) {
        try {
            redisLockManager.unlock(submitter, owner);
        } catch (Exception ignored) {
            // 忽略异常，避免在释放锁时抛出新的错误
        }
    }

    /**
     * 生成“机器名 + 线程 ID + UUID”的锁持有者标识，便于排查日志。
     */
    private String buildLockOwner() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + Thread.currentThread().getId() + "-" + UUID.randomUUID();
        } catch (Exception ex) {
            return "unknown-" + Thread.currentThread().getId() + "-" + UUID.randomUUID();
        }
    }
}

