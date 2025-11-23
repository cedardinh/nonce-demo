package com.work.nonce.core.engine.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.work.nonce.core.exception.NonceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Redis 刷盘队列，使用 main/pending 双列表保证“取出 -> 处理 -> ACK”流程具备幂等性。
 */
@Component
public class NonceFlushQueue {

    /** 主队列 key，保存待处理事件。 */
    private static final String MAIN_KEY = "nonce:flush:queue";
    /** pending 队列 key，临时保存正在处理的事件，实现“至少一次”语义。 */
    private static final String PENDING_KEY = "nonce:flush:pending";

    /** Redis 客户端，用于操作双队列。 */
    private final StringRedisTemplate redisTemplate;
    /** JSON 序列化器，负责事件的序列化/反序列化。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数，注入 RedisTemplate 与 ObjectMapper。
     */
    public NonceFlushQueue(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 发布一个刷盘事件到主队列。
     *
     * @param event 待刷盘事件
     */
    public void publish(PerformanceFlushEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(MAIN_KEY, payload);
        } catch (JsonProcessingException e) {
            throw new NonceException("序列化刷盘事件失败", e);
        }
    }

    /**
     * 从主队列批量拉取事件，并移动到 pending 列表。
     *
     * @param batchSize 期望批次大小
     * @return 待处理事件条目列表
     */
    public List<PerformanceFlushQueueEntry> pullBatch(int batchSize) {
        List<PerformanceFlushQueueEntry> entries = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            String payload = redisTemplate.opsForList().rightPopAndLeftPush(MAIN_KEY, PENDING_KEY);
            if (payload == null) {
                break;
            }
            try {
                PerformanceFlushEvent event = objectMapper.readValue(payload, PerformanceFlushEvent.class);
                entries.add(new PerformanceFlushQueueEntry(event, payload));
            } catch (JsonProcessingException e) {
                // 丢弃无法解析的事件，ACK 掉，避免阻塞
                redisTemplate.opsForList().remove(PENDING_KEY, 1, payload);
            }
        }
        return entries;
    }

    /**
     * 处理成功后，显式从 pending 列表删除对应事件。
     *
     * @param entry 已成功刷盘的事件
     */
    public void ack(PerformanceFlushQueueEntry entry) {
        redisTemplate.opsForList().remove(PENDING_KEY, 1, entry.getRawPayload());
    }

    /**
     * 批量 NACK，将 pending 中尚未成功的事件重新放回主队列，保持顺序。
     *
     * @param entries 本次处理失败的事件集合
     */
    public void nack(List<PerformanceFlushQueueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<PerformanceFlushQueueEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        for (PerformanceFlushQueueEntry entry : reversed) {
            redisTemplate.opsForList().remove(PENDING_KEY, 1, entry.getRawPayload());
            redisTemplate.opsForList().rightPush(MAIN_KEY, entry.getRawPayload());
        }
    }

    /**
     * 查询主/副队列包含的事件总数，用于判断积压程度。
     *
     * @return pending + main 的总和
     */
    public long pendingSize() {
        Long queueSize = redisTemplate.opsForList().size(MAIN_KEY);
        Long pendingSize = redisTemplate.opsForList().size(PENDING_KEY);
        long main = queueSize == null ? 0L : queueSize;
        long pending = pendingSize == null ? 0L : pendingSize;
        return main + pending;
    }
}

