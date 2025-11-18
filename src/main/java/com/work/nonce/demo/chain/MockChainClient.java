package com.work.nonce.demo.chain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版链客户端，仅用于 demo，真实项目请替换为业务自己的实现。
 */
public class MockChainClient implements ChainClient {

    private final Map<String, Long> latestNonce = new ConcurrentHashMap<>();

    @Override
    public String sendTransaction(String submitter, long nonce, String payload) {
        latestNonce.merge(submitter, nonce, Math::max);
        return "tx_" + submitter + "_" + nonce;
    }

    @Override
    public long queryLatestNonce(String submitter) {
        return latestNonce.getOrDefault(submitter, -1L);
    }
}

