package com.work.nonce.core.chain;

/**
 * 链侧“视图/区块高度”选择，命名与常见节点/客户端保持一致。
 *
 * <p>用于表达：查询 next nonce 时，是否考虑 pending 交易、是否需要更强最终性等。</p>
 *
 * <p>注意：不同链/节点/SDK 支持的 tag 可能不同，具体由 {@link ChainNonceClient} 实现决定如何降级。</p>
 */
public enum ChainBlockTag {
    EARLIEST("earliest"),
    LATEST("latest"),
    PENDING("pending"),
    FINALIZED("finalized"),
    SAFE("safe"),
    ACCEPTED("accepted");

    private final String value;

    ChainBlockTag(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

