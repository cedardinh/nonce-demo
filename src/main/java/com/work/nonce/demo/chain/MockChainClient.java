package com.work.nonce.demo.chain;

import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 内存版链客户端，仅用于 demo，真实项目请替换为业务自己的实现。
 */
public class MockChainClient implements ChainClient {

    private final Map<String, Long> latestNonce = new ConcurrentHashMap<>();
    private Web3j web3j;
    private final String PREFIX = "nonce_";

    @Override
    public String sendTransaction(String submitter, long nonce, String payload) {
        latestNonce.merge(submitter, nonce, Math::max);
        return "tx_" + submitter + "_" + nonce;
    }

    @Override
    public BigInteger queryLatestNonce(String submitter) throws Exception {
        submitter = PREFIX + submitter;
        EthGetTransactionCount count = web3j.ethGetTransactionCount(submitter, DefaultBlockParameterName.PENDING).sendAsync().get();
        return count.getTransactionCount();
    }
}

