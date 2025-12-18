package com.work.nonce.txmgr.domain;

/**
 * 111最终方案.md 中对外统一的状态机枚举（字符串化存储）。
 */
public enum TxState {
    CREATED,
    ALLOCATED,
    TRACKING,
    CONFIRMED,
    FAILED_FINAL,
    STUCK
}


