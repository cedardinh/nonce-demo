package com.work.nonce.txmgr.service.nonce;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.repository.mapper.SubmitterNonceCursorMapper;

import java.time.Duration;
import java.time.Instant;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;

/**
 * 111最终方案.md：max(chain, cache, db) 分配器（参考 FFTM 工程实践）。
 *
 * 注意：dbNext 以 submitter_nonce_cursor.next_nonce 为准（权威）。
 */
public class MaxNonceAllocator {

    private final ChainConnector chain;
    private final SubmitterNonceCursorMapper cursorMapper;
    private final TxMgrProperties props;
    private final NonceCache cache;

    public MaxNonceAllocator(ChainConnector chain,
                             SubmitterNonceCursorMapper cursorMapper,
                             TxMgrProperties props,
                             NonceCache cache) {
        this.chain = chain;
        this.cursorMapper = cursorMapper;
        this.props = props;
        this.cache = cache;
    }

    /**
     * 在“已通过幂等二次校验”的前提下，为 submitter 分配下一个 nonce。
     *
     * 约束：调用方负责在 batch/事务失败时调用 clearCache(submitter)。
     */
    public long nextNonce(String submitter, Instant now) {
        requireNonEmpty(submitter, "submitter");
        if (now == null) {
            now = Instant.now();
        }

        NonceCache.Entry entry = cache.get(submitter);
        boolean expired = isExpired(entry, now, props.getNonceStateTimeout());

        // 缓存存在且未过期：直接用缓存值递增（FFTM 的 fast path）
        if (entry != null && !expired) {
            long n = entry.nextNonce;
            entry.nextNonce++;
            cache.put(submitter, entry);
            return n;
        }

        // 缓存不存在或过期：查询 chainNext，并与 internalNext 比较取最大
        long chainNext = chain.getPendingNonce(submitter);

        long internalNext;
        if (entry != null) {
            // 过期缓存仍用于比较（对齐 FFTM：避免 DB 落后于未提交批次的 cache）
            internalNext = entry.nextNonce;
        } else {
            // DB 端游标（权威/近权威）
            // 如果 cursor 尚未初始化，会在调用方事务内先 insertIfNotExists，这里默认存在或返回 null 时用 0。
            Long dbNext = null;
            com.work.nonce.txmgr.repository.entity.SubmitterNonceCursorEntity c = cursorMapper.selectBySubmitter(submitter);
            if (c != null) {
                dbNext = c.getNextNonce();
            }
            internalNext = dbNext == null ? 0L : dbNext;
        }

        long next = Math.max(chainNext, internalNext);

        NonceCache.Entry newEntry = new NonceCache.Entry(next + 1, now);
        cache.put(submitter, newEntry);
        return next;
    }

    public void clearCache(String submitter) {
        if (submitter == null) {
            return;
        }
        cache.remove(submitter);
    }

    private boolean isExpired(NonceCache.Entry entry, Instant now, Duration timeout) {
        if (entry == null || entry.cachedAt == null || timeout == null) {
            return true;
        }
        return entry.cachedAt.plus(timeout).isBefore(now);
    }
}


