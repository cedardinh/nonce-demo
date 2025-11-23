package com.work.nonce.core.execution;

import java.util.HashMap;
import java.util.Map;

/**
 * 传递给业务 handler 的上下文，仅包含 submitter、nonce 以及自定义元数据。
 * 业务若需额外依赖，可自行注入并在 handler 闭包中使用，避免组件与具体实现耦合。
 */
public class NonceExecutionContext {

    /** submitter 唯一标识。 */
    private final String submitter;
    /** 当前分配到的 nonce。 */
    private final long nonce;
    /** 供业务附加上下文数据的字典。 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * @param submitter submitter 唯一标识
     * @param nonce     已分配的 nonce 值
     */
    public NonceExecutionContext(String submitter, long nonce) {
        this.submitter = submitter;
        this.nonce = nonce;
    }

    /** @return submitter 唯一标识 */
    public String getSubmitter() {
        return submitter;
    }

    /** @return 当前领取到的 nonce */
    public long getNonce() {
        return nonce;
    }

    /**
     * 向上下文中写入自定义属性。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取属性值，如不存在返回 null。
     *
     * @param key 属性名
     */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * 获取属性并进行类型检查。
     *
     * @param key  属性名
     * @param type 期望的类型
     */
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
    /**
     * 便于日志输出的字符串表示。
     */
    public String toString() {
        return "NonceExecutionContext{" +
                "submitter='" + submitter + '\'' +
                ", nonce=" + nonce +
                '}';
    }
}

