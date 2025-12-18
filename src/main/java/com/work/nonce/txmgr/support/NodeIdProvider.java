package com.work.nonce.txmgr.support;

/**
 * 提供节点稳定标识，用于 submitter_lease.owner_node 与日志/指标标记。
 */
public interface NodeIdProvider {
    String getNodeId();
}


