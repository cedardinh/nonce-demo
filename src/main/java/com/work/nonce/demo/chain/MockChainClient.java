package com.work.nonce.demo.chain;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版链客户端，仅用于 demo，真实项目请替换为业务自己的实现。
 */
public class MockChainClient implements ChainClient {

    private final Map<String, Long> latestNonce = new ConcurrentHashMap<>();
    private Web3j web3j;
    private final String PREFIX = "nonce_";

    @Override
    public String sendTransaction(String signer, long nonce, String payload) {
        latestNonce.merge(signer, nonce, (oldValue, newValue) -> {
            if (oldValue == null) {
                return newValue;
            }
            if (newValue == null) {
                return oldValue;
            }
            return Math.max(oldValue, newValue);
        });
        return "tx_" + signer + "_" + nonce;
    }

    @Override
    public BigInteger queryLatestNonce(String signer) throws Exception {
        signer = PREFIX + signer;
        EthGetTransactionCount count = web3j.ethGetTransactionCount(signer, DefaultBlockParameterName.PENDING).sendAsync().get();
        return count.getTransactionCount();
    }
}

