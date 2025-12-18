package com.work.nonce.txmgr.support;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Demo 级默认 nodeId 生成方式：hostname + JVM 随机后缀。
 * 生产可替换为：env-region-podName-instanceId 等稳定标识。
 */
public class SimpleNodeIdProvider implements NodeIdProvider {

    private final String nodeId;

    public SimpleNodeIdProvider() {
        this.nodeId = buildNodeId();
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    private String buildNodeId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}


