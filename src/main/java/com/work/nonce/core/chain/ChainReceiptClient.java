package com.work.nonce.core.chain;

import java.util.Optional;

/**
 * 链交易回执查询（强证据）。
 *
 * <p>用于对账/定案：txHash -> receipt 是最可靠的“是否已被链接受/落块”的证据来源。</p>
 *
 * <p>组件 core 不依赖具体链 SDK（如 web3j），由宿主应用注入实现。</p>
 */
public interface ChainReceiptClient {

    /**
     * 查询交易回执是否存在（是否已被矿工/区块接受）。
     *
     * @return Optional.empty 表示“查询不可用/不支持/失败”；Optional.of(true/false) 表示明确结果
     */
    Optional<Boolean> hasReceipt(String txHash);
}

