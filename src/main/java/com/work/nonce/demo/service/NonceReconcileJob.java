package com.work.nonce.demo.service;

import com.work.nonce.core.chain.ChainBlockTag;
import com.work.nonce.core.chain.ChainNonceClient;
import com.work.nonce.core.service.NonceService;
import com.work.nonce.core.repository.entity.NonceAllocationEntity;
import com.work.nonce.core.repository.mapper.NonceAllocationMapper;
import com.work.nonce.demo.config.NonceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 轻量对账任务：
 * 1) 超时 RESERVED -> PENDING（隔离，避免误回收）
 * 2) PENDING 定案：ACCEPTED / RECYCLABLE
 *
 * <p>只在异常/回收路径触发链查询；正常分配路径不引入额外链开销。</p>
 */
@Component
@ConditionalOnProperty(prefix = "nonce", name = "reconcile-enabled", havingValue = "true", matchIfMissing = true)
public class NonceReconcileJob {

    private final NonceProperties properties;
    private final NonceAllocationMapper allocationMapper;
    private final NonceService nonceService;
    private final ChainNonceClient chainNonceClient;

    public NonceReconcileJob(NonceProperties properties,
                             NonceAllocationMapper allocationMapper,
                             NonceService nonceService,
                             ChainNonceClient chainNonceClient) {
        this.properties = properties;
        this.allocationMapper = allocationMapper;
        this.nonceService = nonceService;
        this.chainNonceClient = chainNonceClient;
    }

    @Scheduled(fixedDelayString = "${nonce.reconcile-interval-ms:5000}")
    public void runOnce() {
        int limit = Math.max(1, properties.getReconcileBatchSize());
        Instant now = Instant.now();

        // 1) 超时 RESERVED -> PENDING（隔离）
        expireReservedToPending(now, limit);

        // 2) PENDING 定案（只处理“足够老”的 PENDING）
        Duration pendingMaxAge = properties.getPendingMaxAge() == null ? Duration.ofMinutes(5) : properties.getPendingMaxAge();
        Instant before = now.minus(pendingMaxAge);
        finalizePending(before, now, limit);
    }

    private void expireReservedToPending(Instant now, int limit) {
        List<NonceAllocationEntity> expired = allocationMapper.findExpiredReservedGlobal(now, limit);
        for (NonceAllocationEntity e : expired) {
            try {
                nonceService.markPending(e.getSubmitter(), e.getNonce(), "expire->PENDING(reconcile)");
            } catch (Exception ignored) {
                // 对账任务不应影响主流程：单条失败直接跳过
            }
        }
    }

    private void finalizePending(Instant before, Instant now, int limit) {
        List<NonceAllocationEntity> pendings = allocationMapper.findStalePendingGlobal(before, limit);
        for (NonceAllocationEntity e : pendings) {
            String submitter = e.getSubmitter();
            long nonce = e.getNonce();
            try {
                long latestNext = chainNonceClient.getNextNonce(submitter, ChainBlockTag.LATEST);
                if (latestNext >= 0 && latestNext > nonce) {
                    nonceService.markUsed(submitter, nonce, e.getTxHash(), "reconcile ACCEPTED by latestNext=" + latestNext);
                    continue;
                }

                long pendingNext = chainNonceClient.getNextNonce(submitter, ChainBlockTag.PENDING);
                if (pendingNext >= 0 && pendingNext > nonce) {
                    // 仍疑似被占用：刷新更新时间，避免每次都扫描到
                    allocationMapper.touchPending(submitter, nonce, "still pending: pendingNext=" + pendingNext, now);
                    continue;
                }

                // 链查询不可用时不做定案（避免误回收导致 nonce 复用事故）
                if (latestNext < 0 && pendingNext < 0) {
                    allocationMapper.touchPending(submitter, nonce, "chain unavailable, keep pending", now);
                    continue;
                }

                // pendingNext <= nonce && latestNext <= nonce：视作未占用（或已被丢弃），允许回收
                nonceService.markRecyclable(submitter, nonce, "reconcile RECYCLABLE (pending expired, next<=nonce)");
            } catch (Exception ignored) {
                // 单条失败跳过，下次再试
            }
        }
    }
}

