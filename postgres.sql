-- postgres.sql
-- Schema for nonce-management demo (PostgreSQL)
-- 约定：
-- - submitter: 业务侧的“发号维度”（可理解为 signer / submitter / 账户等），同一 submitter 的 nonce 序列独立
-- - 本实现采用：乐观锁（CAS）+ UNIQUE(submitter, nonce) + 重试退避 + 短事务 + 条件更新（CAS）

BEGIN;

-- ============================================================
-- 1) submitter_nonce_state：每个 submitter 一行，维护“发新号游标”
-- ============================================================

CREATE TABLE IF NOT EXISTS submitter_nonce_state (
    -- submitter 唯一标识（业务侧定义）
    submitter        TEXT PRIMARY KEY,

    -- 链上已确认连续到的最大 nonce（示例项目暂未在分配中使用，供对账/恢复扩展）
    -- 允许 -1 表示“尚未对齐/未知”
    last_chain_nonce BIGINT NOT NULL DEFAULT -1,

    -- 下一个将要分配的新 nonce（只在发新号时 +1）
    next_local_nonce BIGINT NOT NULL DEFAULT 0,

    -- 行更新时间（由应用维护；也可用触发器统一维护）
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 行创建时间
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_submitter_nonce_state_last_chain_nonce
        CHECK (last_chain_nonce >= -1),
    CONSTRAINT ck_submitter_nonce_state_next_local_nonce
        CHECK (next_local_nonce >= 0)
);

COMMENT ON TABLE submitter_nonce_state IS
    '每个 submitter 一行：维护发新号游标 next_local_nonce，以及链上对齐进度 last_chain_nonce（用于对账/恢复扩展）。';

COMMENT ON COLUMN submitter_nonce_state.submitter IS 'submitter 唯一标识（业务侧定义，同 submitter 的 nonce 序列独立）。';
COMMENT ON COLUMN submitter_nonce_state.last_chain_nonce IS '链上已确认连续到的最大 nonce；-1 表示未知/未对齐。';
COMMENT ON COLUMN submitter_nonce_state.next_local_nonce IS '下一个将要分配的新 nonce（CAS 乐观更新）。';
COMMENT ON COLUMN submitter_nonce_state.updated_at IS '更新时间。';
COMMENT ON COLUMN submitter_nonce_state.created_at IS '创建时间。';

-- ============================================================
-- 2) submitter_nonce_allocation：每个 submitter+nonce 一条生命周期记录
-- ============================================================

CREATE TABLE IF NOT EXISTS submitter_nonce_allocation (
    id          BIGSERIAL PRIMARY KEY,

    -- 归属的 submitter
    submitter   TEXT NOT NULL,

    -- 具体 nonce 值（>=0）
    nonce       BIGINT NOT NULL,

    -- 生命周期状态：RESERVED / USED / RECYCLABLE
    status      TEXT NOT NULL,

    -- reservation 持有者标识（本次占号的 owner，用于回写时 CAS 校验：防止“旧请求迟到回写覆盖新占用”）
    lock_owner  TEXT,

    -- reservation 过期时间点（语义：reserved_until）
    -- 注意：为了兼容现有代码/列名历史，列名仍使用 locked_until
    locked_until TIMESTAMPTZ,

    -- 交易 hash（status=USED 时要求非空）
    tx_hash     TEXT,

    -- 失败/回收原因（可空）
    reason      TEXT,

    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 同一个 submitter 的同一个 nonce 只能出现一次：正确性兜底（防重号）
    CONSTRAINT uq_submitter_nonce_allocation_submitter_nonce
        UNIQUE (submitter, nonce),

    -- 外键：allocation 必须属于一个已存在的 submitter 状态
    CONSTRAINT fk_submitter_nonce_allocation_submitter
        FOREIGN KEY (submitter)
        REFERENCES submitter_nonce_state (submitter)
        ON UPDATE CASCADE
        ON DELETE RESTRICT,

    CONSTRAINT ck_submitter_nonce_allocation_nonce_non_negative
        CHECK (nonce >= 0),

    CONSTRAINT ck_submitter_nonce_allocation_status_enum
        CHECK (status IN ('RESERVED', 'USED', 'RECYCLABLE')),

    -- RESERVED 必须持有 owner 与过期时间点（否则无法做安全回写/过期回收）
    CONSTRAINT ck_submitter_nonce_allocation_reserved_requires_lease
        CHECK (
            status <> 'RESERVED'
            OR (lock_owner IS NOT NULL AND locked_until IS NOT NULL)
        ),

    -- USED 必须有 tx_hash（示例项目语义：业务已成功发送/确认）
    CONSTRAINT ck_submitter_nonce_allocation_used_requires_tx_hash
        CHECK (
            status <> 'USED'
            OR (tx_hash IS NOT NULL AND length(trim(tx_hash)) > 0)
        )
);

COMMENT ON TABLE submitter_nonce_allocation IS
    'nonce 生命周期记录表：UNIQUE(submitter, nonce) 防重号；status=RESERVED/USED/RECYCLABLE；locked_until 表示 reservation 过期时间点。';

COMMENT ON COLUMN submitter_nonce_allocation.id IS '主键，自增。';
COMMENT ON COLUMN submitter_nonce_allocation.submitter IS '归属的 submitter（FK 到 submitter_nonce_state）。';
COMMENT ON COLUMN submitter_nonce_allocation.nonce IS 'nonce 值（>=0）。';
COMMENT ON COLUMN submitter_nonce_allocation.status IS '状态：RESERVED（占用中）、USED（已使用）、RECYCLABLE（可复用洞）。';
COMMENT ON COLUMN submitter_nonce_allocation.lock_owner IS 'reservation 持有者标识（用于回写时 CAS 校验）。';
COMMENT ON COLUMN submitter_nonce_allocation.locked_until IS 'reservation 过期时间点（语义 reserved_until；列名历史原因保留 locked_until）。';
COMMENT ON COLUMN submitter_nonce_allocation.tx_hash IS '交易哈希（status=USED 时必填）。';
COMMENT ON COLUMN submitter_nonce_allocation.reason IS '失败/回收原因（可选）。';
COMMENT ON COLUMN submitter_nonce_allocation.updated_at IS '更新时间。';
COMMENT ON COLUMN submitter_nonce_allocation.created_at IS '创建时间。';

-- ============================================================
-- 3) 索引：匹配当前代码路径（找洞/回收/回写 CAS）
-- ============================================================

-- 3.1 查找最小 RECYCLABLE：WHERE submitter=? AND status='RECYCLABLE' ORDER BY nonce ASC LIMIT 1
CREATE INDEX IF NOT EXISTS idx_sna_recyclable_submitter_nonce
    ON submitter_nonce_allocation (submitter, nonce)
    WHERE status = 'RECYCLABLE';

-- 3.2 回收过期 RESERVED：WHERE submitter=? AND status='RESERVED' AND locked_until < now()
CREATE INDEX IF NOT EXISTS idx_sna_reserved_expiry
    ON submitter_nonce_allocation (submitter, locked_until)
    WHERE status = 'RESERVED';

-- 3.3 回写 CAS：WHERE submitter=? AND nonce=? AND status='RESERVED' AND lock_owner=?
-- UNIQUE(submitter, nonce) 已可定位一行，但加 partial index 可降低过滤成本并稳定执行计划
CREATE INDEX IF NOT EXISTS idx_sna_reserved_owner
    ON submitter_nonce_allocation (submitter, nonce, lock_owner)
    WHERE status = 'RESERVED';

-- 3.4（可选）按 submitter+status 统计/监控（例如统计滞留 RESERVED 数）
CREATE INDEX IF NOT EXISTS idx_sna_submitter_status
    ON submitter_nonce_allocation (submitter, status);

COMMIT;


