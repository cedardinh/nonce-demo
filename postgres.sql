-- ===================================================================
-- PostgreSQL 建表脚本：nonce 组件核心表结构
-- ===================================================================

-- 0. 枚举：若不想创建 ENUM，可改为 VARCHAR + CHECK 约束
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'nonce_allocation_status') THEN
        CREATE TYPE nonce_allocation_status AS ENUM ('RESERVED', 'USED', 'RECYCLABLE');
    END IF;
END $$;

-- 1. submitter_nonce_state：每个 submitter 一行，记录链上对齐与本地发号游标
CREATE TABLE IF NOT EXISTS submitter_nonce_state (
    submitter        VARCHAR(128) PRIMARY KEY,
    last_chain_nonce BIGINT        NOT NULL DEFAULT -1,
    next_local_nonce BIGINT        NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 2. submitter_nonce_allocation：nonce 生命周期记录表
CREATE TABLE IF NOT EXISTS submitter_nonce_allocation (
    id         BIGSERIAL PRIMARY KEY,
    submitter  VARCHAR(128) NOT NULL,
    nonce      BIGINT       NOT NULL,
    status     nonce_allocation_status NOT NULL DEFAULT 'RESERVED',
    lock_owner VARCHAR(255),
    tx_hash    VARCHAR(255),
    reason     TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_submitter_nonce UNIQUE (submitter, nonce)
);

-- 辅助索引：帮助快速定位特定状态的记录或观察滞留
CREATE INDEX IF NOT EXISTS idx_allocation_status_updated
    ON submitter_nonce_allocation (submitter, status, updated_at DESC);

-- 视需要可额外建立 tx_hash、lock_owner 等索引

