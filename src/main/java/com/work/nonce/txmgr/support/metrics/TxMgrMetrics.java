package com.work.nonce.txmgr.support.metrics;

/**
 * 可观测性端口（不强依赖 Micrometer/Prometheus）。
 *
 * 设计目标：
 * - 核心路径只调用接口，不绑定具体 metrics 实现
 * - 业务/平台可通过自定义 Bean 接入 Micrometer 等实现
 */
public interface TxMgrMetrics {

    default void leaseAcquire(String result) {
    }

    default void fencedWriteRejected(String op) {
    }

    default void receiptCheck(String result) {
    }

    default void resubmit(String result) {
    }

    default void finality(String result) {
    }

    default void stuckDecision(String action) {
    }

    default void writerQueueDepth(String name, int depth) {
    }
}


