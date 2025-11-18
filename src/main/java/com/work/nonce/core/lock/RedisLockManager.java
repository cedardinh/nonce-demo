package com.work.nonce.core.lock;

import java.time.Duration;

/**
 * 提供 per-submitters 的分布式锁能力。默认实现使用内存 ConcurrentHashMap，
 * 真实环境中可替换为 Redisson / Lettuce 等客户端。
 */
public interface RedisLockManager {

    /**
     * 尝试获取 submitter 维度的锁。
     *
     * @param submitter submitter 唯一标识
     * @param lockOwner 当前线程/节点的标识
     * @param ttl       锁超时时间
     * @return true 表示加锁成功
     */
    boolean tryLock(String submitter, String lockOwner, Duration ttl);

    /**
     * 释放锁（若锁已超时/转移，实际实现需要自行判断）。
     */
    void unlock(String submitter, String lockOwner);
}

