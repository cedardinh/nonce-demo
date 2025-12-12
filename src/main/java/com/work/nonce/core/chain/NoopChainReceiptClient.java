package com.work.nonce.core.chain;

import java.util.Optional;

/**
 * 默认空实现：返回 empty 表示不可用。
 */
public class NoopChainReceiptClient implements ChainReceiptClient {
    @Override
    public Optional<Boolean> hasReceipt(String txHash) {
        return Optional.empty();
    }
}

