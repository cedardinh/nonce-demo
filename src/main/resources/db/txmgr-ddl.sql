-- 111最终方案.md - 里程碑1 DDL（手工执行/本地验证用）
-- 注意：本项目当前未集成 Flyway，本文件仅作为对齐方案的数据模型参考。

CREATE TABLE IF NOT EXISTS submitter_lease (
  submitter      TEXT PRIMARY KEY,
  owner_node     TEXT NOT NULL,
  fencing_token  BIGINT NOT NULL,
  expires_at     TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_submitter_lease_owner_node ON submitter_lease(owner_node);

CREATE TABLE IF NOT EXISTS submitter_nonce_cursor (
  submitter      TEXT PRIMARY KEY,
  next_nonce     BIGINT NOT NULL,
  fencing_token  BIGINT NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS managed_tx (
  tx_id            UUID PRIMARY KEY,
  submitter        TEXT NOT NULL,
  request_id       TEXT NULL,
  nonce            BIGINT NULL,
  payload          JSONB NOT NULL,
  tx_hash          TEXT NULL,
  state            TEXT NOT NULL,
  sub_state        TEXT NULL,
  last_submit_at   TIMESTAMPTZ NULL,
  last_receipt_check_at TIMESTAMPTZ NULL,
  next_resubmit_at TIMESTAMPTZ NULL,
  submit_attempts  INT NOT NULL DEFAULT 0,
  last_error       TEXT NULL,
  receipt          JSONB NULL,
  confirmed_at     TIMESTAMPTZ NULL,
  fencing_token    BIGINT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_tx_submitter_request_id
  ON managed_tx(submitter, request_id)
  WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_managed_tx_tx_hash ON managed_tx(tx_hash);
CREATE INDEX IF NOT EXISTS idx_managed_tx_submitter_state_resubmit ON managed_tx(submitter, state, next_resubmit_at);

CREATE TABLE IF NOT EXISTS tx_completions (
  seq    BIGSERIAL PRIMARY KEY,
  tx_id  UUID NOT NULL,
  time   TIMESTAMPTZ NOT NULL DEFAULT now(),
  status TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tx_completions_time ON tx_completions(time);


