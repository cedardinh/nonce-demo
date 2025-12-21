package com.work.nonce.core.execution;

import java.util.Objects;
import java.util.concurrent.Callable;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * basic 模式：不做节点内的 signer 串行化，直接在当前线程执行。
 */
public class DirectSignerExecutor implements SignerExecutor {

    @Override
    public <T> T execute(String signer, Callable<T> work) {
        requireNonEmpty(signer, "signer");
        requireNonNull(work, "work");
        try {
            return work.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(Objects.requireNonNullElse(e.getMessage(), "direct execute failed"), e);
        }
    }
}


