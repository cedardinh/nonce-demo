package com.work.nonce.demo.worker;

import com.work.nonce.demo.config.NonceProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 节点内的 Worker-Queue：hash(signer) -> 固定 worker 单线程队列。
 * <p>
 * 注意：不做跨节点转发/重定向；跨节点竞争由 lease+fencing + 有限重试解决。
 */
@Component
public class WorkerQueueDispatcher {

    private final ExecutorService[] workers;
    private final int workerCount;

    public WorkerQueueDispatcher(NonceProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.workerCount = Math.max(1, properties.getWorkerCount());
        this.workers = new ExecutorService[this.workerCount];
        for (int i = 0; i < this.workerCount; i++) {
            final int workerIndex = i;
            this.workers[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("nonce-worker-" + workerIndex);
                t.setDaemon(true);
                return t;
            });
        }
    }

    public <T> T dispatch(String signer, Callable<T> task) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(task, "task");
        int idx = positiveHash(signer) % workerCount;
        Future<T> f = workers[idx].submit(task);
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("worker dispatch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    private static int positiveHash(String signer) {
        // FNV-1a 32-bit (对齐 Firefly 的思路：快且稳定)
        byte[] data = signer.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }
}


