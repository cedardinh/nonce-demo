package com.work.nonce.demo.chain.web3j;

import com.work.nonce.demo.chain.ChainClient;
import com.work.nonce.demo.chain.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

/**
 * 基于 Web3j 的链客户端实现：
 * - 查询交易回执 eth_getTransactionReceipt
 * - 查询最新区块号 eth_blockNumber
 * - 查询地址链上 nonce eth_getTransactionCount
 *
 * 说明：
 * sendTransaction 在不同业务里差异很大（签名策略、gas 策略、私钥管理等），这里不做通用实现。
 */
public class Web3jChainClient implements ChainClient {

    private static final Logger log = LoggerFactory.getLogger(Web3jChainClient.class);

    private final Web3j web3j;

    public Web3jChainClient(Web3j web3j) {
        this.web3j = web3j;
    }

    @Override
    public String sendTransaction(String submitter, long nonce, String payload) {
        throw new UnsupportedOperationException("Web3jChainClient 不提供通用 sendTransaction 实现，请在业务侧实现签名与发送逻辑");
    }

    @Override
    public Optional<TransactionReceipt> getTransactionReceipt(String txHash) {
        try {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
            Optional<org.web3j.protocol.core.methods.response.TransactionReceipt> receiptOpt = resp.getTransactionReceipt();
            if (!receiptOpt.isPresent()) {
                return Optional.empty();
            }
            org.web3j.protocol.core.methods.response.TransactionReceipt r = receiptOpt.get();
            BigInteger bn = r.getBlockNumber();
            String blockHash = r.getBlockHash();
            boolean success = isReceiptSuccess(r);
            return Optional.of(new TransactionReceipt(txHash, bn.longValue(), blockHash, success));
        } catch (IOException e) {
            log.warn("Web3j getTransactionReceipt failed. txHash={} err={}", txHash, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public long queryLatestBlockNumber() {
        try {
            EthBlockNumber resp = web3j.ethBlockNumber().send();
            return resp.getBlockNumber().longValue();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long queryLatestNonce(String submitter) {
        try {
            EthGetTransactionCount resp = web3j.ethGetTransactionCount(submitter, DefaultBlockParameterName.LATEST).send();
            return resp.getTransactionCount().longValue();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isReceiptSuccess(org.web3j.protocol.core.methods.response.TransactionReceipt receipt) {
        // EVM receipt status: 0x1 success, 0x0 failure
        // 有些链可能没有 status 字段，这里按可用性做容错
        try {
            String status = receipt.getStatus();
            if (status == null) {
                return true;
            }
            return !"0x0".equalsIgnoreCase(status);
        } catch (Exception e) {
            return true;
        }
    }
}


