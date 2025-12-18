package com.work.nonce.txmgr.service;

import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.entity.SubmitterLeaseEntity;
import com.work.nonce.txmgr.repository.mapper.SubmitterLeaseMapper;
import com.work.nonce.txmgr.support.NodeIdProvider;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;

/**
 * 111最终方案.md：Postgres lease + fencing 的执行权管理。
 *
 * 约束：使用 DB 时间做过期判断（select now()），避免依赖本地时钟。
 */
@Service
public class LeaseManager {

    private final SubmitterLeaseMapper leaseMapper;
    private final NodeIdProvider nodeIdProvider;
    private final TxMgrProperties props;
    private final TxMgrMetrics metrics;

    public LeaseManager(SubmitterLeaseMapper leaseMapper, NodeIdProvider nodeIdProvider, TxMgrProperties props, TxMgrMetrics metrics) {
        this.leaseMapper = leaseMapper;
        this.nodeIdProvider = nodeIdProvider;
        this.props = props;
        this.metrics = metrics;
    }

    public String getNodeId() {
        return nodeIdProvider.getNodeId();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaseDecision acquireOrRenew(String submitter) {
        requireNonEmpty(submitter, "submitter");

        Instant dbNow = leaseMapper.selectDbNow();
        Duration skew = props.getClockSkewAllowance();
        Instant effectiveNow = dbNow.minus(skew);

        SubmitterLeaseEntity cur = leaseMapper.selectForUpdate(submitter);
        String self = nodeIdProvider.getNodeId();
        Instant newExpiresAt = dbNow.plus(props.getLeaseDuration());

        if (cur == null) {
            long token = 1L;
            leaseMapper.insertNew(submitter, self, token, newExpiresAt, dbNow);
            metrics.leaseAcquire("insert");
            return new LeaseDecision(true, token, newExpiresAt);
        }

        boolean expired = cur.getExpiresAt() == null || !cur.getExpiresAt().isAfter(effectiveNow);

        if (self.equals(cur.getOwnerNode()) && !expired) {
            long token = cur.getFencingToken() == null ? 0L : cur.getFencingToken();
            leaseMapper.renew(submitter, self, token, newExpiresAt, dbNow);
            metrics.leaseAcquire("renew");
            return new LeaseDecision(true, token, newExpiresAt);
        }

        if (expired) {
            long nextToken = (cur.getFencingToken() == null ? 0L : cur.getFencingToken()) + 1L;
            leaseMapper.takeover(submitter, self, nextToken, newExpiresAt, dbNow);
            metrics.leaseAcquire("takeover");
            return new LeaseDecision(true, nextToken, newExpiresAt);
        }

        // not leader
        long token = cur.getFencingToken() == null ? 0L : cur.getFencingToken();
        metrics.leaseAcquire("not_leader");
        return new LeaseDecision(false, token, cur.getExpiresAt());
    }
}


