-- Minimal DDL for tx_receipts (receipt persistence)
-- Apply to your PostgreSQL database manually if you want receipt audit/query support.

CREATE TABLE IF NOT EXISTS tx_receipts (
  tx_hash      TEXT PRIMARY KEY,
  submitter    TEXT NOT NULL,
  nonce        BIGINT NOT NULL,
  block_number BIGINT NOT NULL,
  block_hash   TEXT NOT NULL,
  success      BOOLEAN NOT NULL,
  updated_at   TIMESTAMP NOT NULL,
  created_at   TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tx_receipts_submitter_nonce ON tx_receipts(submitter, nonce);


