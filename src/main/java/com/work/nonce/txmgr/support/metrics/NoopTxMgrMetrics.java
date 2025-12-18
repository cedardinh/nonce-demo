package com.work.nonce.txmgr.support.metrics;

/**
 * 默认 no-op 实现：保证工程在不引入任何 metrics 依赖时仍可运行。
 *
 * 若业务侧提供了自定义 TxMgrMetrics Bean，可用 @Primary 或 @ConditionalOnMissingBean 覆盖。
 */
public class NoopTxMgrMetrics implements TxMgrMetrics {
}


