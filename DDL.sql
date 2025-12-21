-- PostgreSQL DDL for nonce-demo
-- ------------------------------------------------------------
-- 目标：
-- 1) 支撑当前代码（PostgresNonceRepository + MyBatis Mappers）
-- 2) 对齐阶段 1 设计（signer_lease + fencing_token）
-- 3) nonce allocation 状态使用：HELD / RELEASED / CONSUMED
--
-- 说明：
-- - 数据库层面已统一为 signer 命名（signer_nonce_*，列 signer）
-- - 如你是从旧库升级（submitter_nonce_* / 列 submitter），请自行准备迁移 SQL（本文件不再包含迁移段）
-- ------------------------------------------------------------
-- 建议：如果你要重复执行本文件，请先手动 DROP 或在独立 schema 中执行。
-- ============================================================
-- 1) CREATE TABLE（建表语句）
-- ============================================================
-- 1.1 signer nonce 状态表：每个 signer 一行（本地分配游标）
CREATE TABLE signer_nonce_state (
    signer VARCHAR(128) PRIMARY KEY,
    next_local_nonce BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 1.2 nonce 分配记录表：nonce 生命周期（HELD / RELEASED / CONSUMED）
CREATE TABLE signer_nonce_allocation (
    id BIGSERIAL PRIMARY KEY,
    signer VARCHAR(128) NOT NULL,
    nonce BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    locked_until TIMESTAMPTZ,
    tx_hash VARCHAR(128),
    reason TEXT,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 1.3 分布式租约表（阶段 1 设计）：signer 级排他执行权（fencing + expiry）
CREATE TABLE signer_lease (
    signer VARCHAR(128) PRIMARY KEY,
    owner_node VARCHAR(256) NOT NULL,
    fencing_token BIGINT NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
-- ============================================================
-- 2) COMMENT（字段含义注释）
-- ============================================================
COMMENT ON TABLE signer_nonce_state IS '每个 signer 的本地 nonce 分配状态（以 DB 为真相）。';
COMMENT ON COLUMN signer_nonce_state.signer IS '签名者标识（业务主键）。';
COMMENT ON COLUMN signer_nonce_state.next_local_nonce IS '下一次要分配的 nonce 起点（本地分配游标/水位）。';
COMMENT ON COLUMN signer_nonce_state.updated_at IS '更新时间（由应用写入或默认 NOW()）。';
COMMENT ON COLUMN signer_nonce_state.created_at IS '创建时间（默认 NOW()）。';
COMMENT ON TABLE signer_nonce_allocation IS 'nonce 分配与生命周期记录（HELD/RELEASED/CONSUMED），用于复用、回收与幂等。';
COMMENT ON COLUMN signer_nonce_allocation.id IS '自增主键。';
COMMENT ON COLUMN signer_nonce_allocation.signer IS '签名者标识（与 signer_nonce_state.signer 对应）。';
COMMENT ON COLUMN signer_nonce_allocation.nonce IS '被分配的 nonce。';
COMMENT ON COLUMN signer_nonce_allocation.status IS '状态：HELD(占用中) / RELEASED(释放可复用) / CONSUMED(终局消费)。';
COMMENT ON COLUMN signer_nonce_allocation.locked_until IS '占用过期时间（仅 HELD 时有意义，过期可回收为 RELEASED）。';
COMMENT ON COLUMN signer_nonce_allocation.tx_hash IS '关联交易哈希（仅 CONSUMED 必须存在；RELEASED 通常为空）。';
COMMENT ON COLUMN signer_nonce_allocation.reason IS '状态变更原因/补充说明（审计/排障字段）。';
COMMENT ON COLUMN signer_nonce_allocation.fencing_token IS '防旧主写入 token（用于租约 fencing；当前可预留）。';
COMMENT ON COLUMN signer_nonce_allocation.updated_at IS '更新时间（状态流转/回收时更新）。';
COMMENT ON COLUMN signer_nonce_allocation.created_at IS '创建时间（首次插入时）。';
COMMENT ON TABLE signer_lease IS 'signer 级分布式租约（排他执行权 + fencing token + 过期时间）。';
COMMENT ON COLUMN signer_lease.signer IS '签名者标识（业务主键）。';
COMMENT ON COLUMN signer_lease.owner_node IS '当前租约持有节点标识（例如 instanceId/hostname）。';
COMMENT ON COLUMN signer_lease.fencing_token IS '单调递增 fencing token（用于拒绝旧主写入）。';
COMMENT ON COLUMN signer_lease.acquired_at IS '租约获取时间。';
COMMENT ON COLUMN signer_lease.expires_at IS '租约过期时间（必须大于 acquired_at）。';
COMMENT ON COLUMN signer_lease.updated_at IS '租约更新时间（续约/抢占时更新）。';
-- ============================================================
-- 3) CONSTRAINTS（约束：UNIQUE/CHECK 等）
-- ============================================================
ALTER TABLE signer_nonce_state
ADD CONSTRAINT ck_signer_nonce_state_next_local_nonnegative CHECK (next_local_nonce >= 0);
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_signer_nonce_allocation_nonce_nonnegative CHECK (nonce >= 0);
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_signer_nonce_allocation_fencing_nonnegative CHECK (fencing_token >= 0);
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_signer_nonce_allocation_status CHECK (status IN ('HELD', 'RELEASED', 'CONSUMED'));
-- 状态机一致性约束：
-- - HELD 必须有 locked_until
-- - RELEASED/CONSUMED 必须无 locked_until
-- - CONSUMED 必须有 tx_hash
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_sna_held_requires_lock_fields CHECK (
        status <> 'HELD'
        OR (
            locked_until IS NOT NULL
        )
    );
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_sna_released_has_no_lock_fields CHECK (
        status <> 'RELEASED'
        OR (
            locked_until IS NULL
        )
    );
ALTER TABLE signer_nonce_allocation
ADD CONSTRAINT ck_sna_consumed_requires_tx_hash_and_no_lock_fields CHECK (
        status <> 'CONSUMED'
        OR (
            tx_hash IS NOT NULL
            AND tx_hash <> ''
            AND locked_until IS NULL
        )
    );
ALTER TABLE signer_lease
ADD CONSTRAINT ck_signer_lease_fencing_nonnegative CHECK (fencing_token >= 0);
ALTER TABLE signer_lease
ADD CONSTRAINT ck_signer_lease_expires_after_acquired CHECK (expires_at > acquired_at);
-- ============================================================
-- 4) INDEXES（索引）
-- ============================================================
-- signer_nonce_state 读写基本按 signer(PK) + FOR UPDATE；如需巡检/对账扫描可加 updated_at 索引
CREATE INDEX idx_signer_nonce_state_updated_at ON signer_nonce_state(updated_at);

-- signer + nonce 必须全局唯一（同一 signer 下一个 nonce 只能绑定一条 allocation）
CREATE UNIQUE INDEX uq_sna_signer_nonce ON signer_nonce_allocation(signer, nonce);

-- 查询“可复用最小 nonce”：findOldestRecyclable()
-- WHERE signer=? AND status='RELEASED' ORDER BY nonce ASC LIMIT 1
CREATE INDEX idx_sna_signer_released_nonce ON signer_nonce_allocation(signer, nonce)
WHERE status = 'RELEASED';
-- 超时回收：recycleExpiredReservations()
-- WHERE signer=? AND status='HELD' AND locked_until < ?
CREATE INDEX idx_sna_signer_held_locked_until ON signer_nonce_allocation(signer, locked_until)
WHERE status = 'HELD'
    AND locked_until IS NOT NULL;
-- fencing 观测/过滤（阶段 1）
CREATE INDEX idx_sna_signer_fencing_token ON signer_nonce_allocation(signer, fencing_token);
-- 统计/监控（可选）
CREATE INDEX idx_sna_signer_status ON signer_nonce_allocation(signer, status);
-- 租约过期扫描/按节点查看持有情况
CREATE INDEX idx_signer_lease_expires_at ON signer_lease(expires_at);
CREATE INDEX idx_signer_lease_owner_node ON signer_lease(owner_node);