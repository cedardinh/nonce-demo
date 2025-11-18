package com.work.nonce.core.execution;

/**
 * 业务方实现的 handler，模板只注入 submitter / nonce 等上下文。
 * 若需要其他依赖，请通过外部注入或闭包方式引用，避免组件感知业务细节。
 */
@FunctionalInterface
public interface NonceExecutionHandler {

    /**
     * @param ctx 组件提供的上下文
     * @return 执行结果，驱动模板在 finally 中更新 allocation 状态
     */
    NonceExecutionResult handle(NonceExecutionContext ctx) throws Exception;
}

