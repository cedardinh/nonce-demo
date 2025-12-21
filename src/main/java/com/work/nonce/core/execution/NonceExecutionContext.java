package com.work.nonce.core.execution;

import java.util.HashMap;
import java.util.Map;

/**
 * 传递给业务 handler 的上下文，仅包含 signer、nonce 以及自定义元数据。
 * 业务若需额外依赖，可自行注入并在 handler 闭包中使用，避免组件与具体实现耦合。
 */
public class NonceExecutionContext {

    private final String signer;
    private final long nonce;
    private final Map<String, Object> attributes = new HashMap<>();

    public NonceExecutionContext(String signer, long nonce) {
        this.signer = signer;
        this.nonce = nonce;
    }

    public String getSigner() {
        return signer;
    }

    public long getNonce() {
        return nonce;
    }

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("属性类型不匹配: " + key);
        }
        return type.cast(value);
    }

    @Override
    public String toString() {
        return "NonceExecutionContext{" +
                "signer='" + signer + '\'' +
                ", nonce=" + nonce +
                '}';
    }
}

