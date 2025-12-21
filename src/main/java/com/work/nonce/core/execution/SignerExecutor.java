package com.work.nonce.core.execution;

import java.util.concurrent.Callable;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * 统一的“按 signer 执行”入口。
 *
 * 设计目标（对齐 @firefly / @分析报告.md 的 worker 路由思路）：
 * - 同一 signer 的工作可以被串行化（worker-queue）
 * - 不同 signer 的工作可并行（多 worker）
 * - basic / worker-queue / auto 三种模式对业务侧调用保持一致
 *
 * 注意：
 * - 该接口不限定 work 的类型：可以是 allocate / markUsed / markRecyclable / 任意业务自定义逻辑
 * - 为兼容“任意位置申请 nonce、任意位置修改状态/回收”的使用方式，NonceComponent 的所有对外方法都会通过此接口包装执行
 */
public interface SignerExecutor {

    <T> T execute(String signer, Callable<T> work);

    default void execute(String signer, Runnable work) {
        requireNonEmpty(signer, "signer");
        requireNonNull(work, "work");
        execute(signer, () -> {
            work.run();
            return null;
        });
    }
}


