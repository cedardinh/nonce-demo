package com.work.nonce.core.chain;

/**
 * 默认的空实现，返回负数表示未启用链上查询。
 */
public class NoopChainNonceClient implements ChainNonceClient {

    @Override
    public long getNextNonce(String submitter, ChainBlockTag tag) {
        return -1L;
    }
}
