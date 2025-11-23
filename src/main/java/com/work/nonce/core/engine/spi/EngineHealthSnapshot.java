package com.work.nonce.core.engine.spi;

/**
 * 标记接口，用于暴露引擎健康信息，无论底层是可靠模式还是性能模式实现。
 * 作为通用契约，调用方无需依赖特定模式的类型。
 */
public interface EngineHealthSnapshot {
}

