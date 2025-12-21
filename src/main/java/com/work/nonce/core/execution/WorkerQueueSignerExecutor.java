package com.work.nonce.core.execution;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * worker-queue 模式：
 * hash(signer) -> 固定 worker（单线程）队列，保证同一 signer 的执行串行化。
 *
 * 对齐 @firefly 的思路：
 * - 稳定 hash 路由（FNV-1a）
 * - 每个 worker 单线程，形成“队列化串行执行”
 * - 有界队列避免 OOM；队列满/超时作为显式信号，供 auto 模式降级
 */
public class WorkerQueueSignerExecutor implements SignerExecutor, AutoCloseable {

    public static final class DispatchRejectedException extends RuntimeException {
        public DispatchRejectedException(String message, Throwable cause) { super(message, cause); }
        public DispatchRejectedException(String message) { super(message); }
    }

    public static final class DispatchTimeoutException extends RuntimeException {
        public DispatchTimeoutException(String message, Throwable cause) { super(message, cause); }
        public DispatchTimeoutException(String message) { super(message); }
    }

    private final ThreadPoolExecutor[] workers;
    private final int workerCount;
    private final Duration dispatchTimeout;

    public WorkerQueueSignerExecutor(int workerCount,
                                     int queueCapacity,
                                     Duration dispatchTimeout,
                                     String threadNamePrefix) {
        if (workerCount <= 0) throw new IllegalArgumentException("workerCount must be > 0");
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be > 0");
        this.workerCount = workerCount;
        this.dispatchTimeout = requireNonNull(dispatchTimeout, "dispatchTimeout");
        if (dispatchTimeout.isNegative() || dispatchTimeout.isZero()) {
            throw new IllegalArgumentException("dispatchTimeout must be > 0");
        }
        final String prefix = (threadNamePrefix == null || threadNamePrefix.trim().isEmpty())
                ? "nonce-worker-"
                : threadNamePrefix.trim();

        this.workers = new ThreadPoolExecutor[this.workerCount];
        for (int i = 0; i < this.workerCount; i++) {
            final int idx = i;
            ThreadFactory tf = r -> {
                Thread t = new Thread(r);
                t.setName(prefix + idx);
                t.setDaemon(true);
                return t;
            };
            // 单线程 + 有界队列：形成串行执行队列
            ThreadPoolExecutor exec = new ThreadPoolExecutor(
                    1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    tf,
                    (r, e) -> { throw new RejectedExecutionException("worker queue is full"); }
            );
            exec.prestartAllCoreThreads();
            this.workers[i] = exec;
        }
    }

    @Override
    public <T> T execute(String signer, Callable<T> work) {
        requireNonEmpty(signer, "signer");
        requireNonNull(work, "work");
        int idx = positiveHash(signer) % workerCount;
        Future<T> f;
        try {
            f = workers[idx].submit(work);
        } catch (RejectedExecutionException e) {
            throw new DispatchRejectedException("worker dispatch rejected for signer=" + signer, e);
        }

        try {
            return f.get(dispatchTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new DispatchTimeoutException("worker dispatch timeout for signer=" + signer + ", timeout=" + dispatchTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("worker dispatch interrupted for signer=" + signer, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            String msg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "worker execution failed";
            throw new RuntimeException(msg, cause);
        }
    }

    // FNV-1a 32-bit（稳定、快速；对齐 @firefly worker 路由）
    private static int positiveHash(String signer) {
        byte[] data = signer.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }

    @Override
    public void close() {
        for (ThreadPoolExecutor e : workers) {
            if (e == null) continue;
            e.shutdown();
        }
    }
}


