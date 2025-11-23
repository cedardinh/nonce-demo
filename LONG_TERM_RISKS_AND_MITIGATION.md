# 长期运行风险与缓解措施

本文档总结系统长期运行时可能出现的数据增长、资源泄漏和性能退化风险，并给出对应的缓解措施。

---

## 一、数据增长风险

### 1.1 PostgreSQL 表无限增长

**风险描述**：
- `nonce_allocation` 表会为每个成功/失败的业务操作保留 USED/RECYCLABLE 记录
- 随着时间推移，USED 记录会累积到百万/千万级，导致：
  - 表膨胀，查询性能下降（即使有索引）
  - 备份/恢复时间延长
  - 存储成本上升

**缓解措施**：
1. **已实现**：在 `NonceConfig` 中增加 `usedRecordRetentionDays` 配置（默认 90 天）
2. **已实现**：增加 `NonceDataCleanupTask` 定时任务：
   - 每天凌晨 3 点清理超过保留期的 USED 记录
   - 需要在 `NonceAllocationMapper` 中实现 `deleteUsedRecordsBefore(Instant cutoffTime)` 方法
3. **建议**：对历史数据归档而非直接删除：
   - 将超过 N 天的 USED 记录迁移到归档表（如 `nonce_allocation_archive`）
   - 归档表可使用列式存储或压缩，节省空间
   - 归档后原表执行 `VACUUM FULL` 回收空间

**SQL 示例**（需在 Mapper XML 中实现）：
```sql
-- 删除超过 N 天的 USED 记录
DELETE FROM nonce_allocation
WHERE status = 'USED'
  AND updated_at < :cutoffTime
LIMIT 10000; -- 分批删除，避免锁表
```

---

### 1.2 孤儿 RESERVED 记录累积

**风险描述**：
- 若进程崩溃/网络故障导致 handler 执行失败但未回收，nonce 会长期停留在 RESERVED 状态
- 这些"孤儿记录"会：
  - 占用 nonce 号段，导致跳号
  - 浪费数据库空间
  - 降低 `findOldestRecyclable` 的查询效率

**缓解措施**：
1. **已实现**：在 `NonceConfig` 中增加 `staleReservedTimeout` 配置（默认 1 小时）
2. **已实现**：在 `NonceDataCleanupTask` 中增加定时回收任务：
   - 每小时执行一次
   - 将超过超时时间的 RESERVED 记录标记为 RECYCLABLE
   - 需要在 `NonceAllocationMapper` 中实现 `markStaleReservedAsRecyclable(Instant staleThreshold, Instant now)` 方法
3. **建议**：配合监控告警：
   - 统计长时间处于 RESERVED 状态的记录数量
   - 若数量持续增长，说明业务 handler 有问题或超时配置不合理

**SQL 示例**（需在 Mapper XML 中实现）：
```sql
-- 回收超过 N 小时的 RESERVED 记录
UPDATE nonce_allocation
SET status = 'RECYCLABLE',
    lock_owner = NULL,
    reason = 'auto-reclaimed: stale RESERVED',
    updated_at = :now
WHERE status = 'RESERVED'
  AND updated_at < :staleThreshold
LIMIT 1000; -- 分批处理
```

---

## 二、Redis 内存泄漏风险

### 2.1 无 TTL 导致键永不过期

**风险描述**：
- `PerformanceNonceEngine` 为每个 submitter 创建 3 类 Redis 键：
  1. `nonce:counter:{submitter}` - 计数器
  2. `nonce:recycle:{submitter}` - 回收池（ZSet）
  3. `nonce:alloc:{submitter}` - 快照哈希
- 原实现未设置 TTL，若 submitter 数量持续增长（如每个用户一个 submitter），Redis 内存会无限增长

**缓解措施**：
1. **已实现**：在 `NonceConfig` 中增加 `redisKeyTtl` 配置（默认 30 天）
2. **已实现**：在关键路径设置 TTL：
   - `setIfAbsent(key, value, ttl)` - 初始化计数器时设置
   - `expire(key, ttl)` - 每次写入回收池/快照时更新
3. **效果**：
   - 不活跃的 submitter 键会在 30 天后自动过期
   - 活跃 submitter 的键会在每次操作时延长过期时间（滑动窗口）

**注意事项**：
- TTL 不宜过短（建议 >= 7 天），避免活跃业务的键被误删
- 若 Redis 内存紧张，可配合 `maxmemory-policy` 使用 LRU 淘汰策略

---

### 2.2 刷盘队列无限积压

**风险描述**：
- `NonceFlushQueue` 使用 Redis 列表存储待刷盘事件
- 若刷盘速度 < 分配速度（如 DB 故障、网络抖动），队列会无限积压：
  - Redis 内存耗尽
  - 刷盘延迟越来越大
  - 最终导致系统不可用

**缓解措施**：
1. **监控告警**：
   - 暴露 `flushQueue.pendingSize()` 为 Metrics 指标
   - 队列长度超过阈值（如 10000）时触发告警
2. **限流与降级**：
   - 在 `NonceEngineManager` 中检查队列长度
   - 超过阈值时自动降级到可靠模式，拒绝新的性能模式分配
3. **自动清理**（谨慎使用）：
   - 可在 `NonceDataCleanupTask` 中增加"清理超龄事件"逻辑
   - 仅清理超过 N 小时且无法刷盘的事件（需记录日志供后续对账）

**示例代码**（在 `NonceEngineManager.allocate` 中增加检查）：
```java
if (flushQueue.pendingSize() > config.getFlushQueueSizeLimit()) {
    LOGGER.warn("[nonce] Flush queue overloaded (size={}), degrading to reliable mode", 
            flushQueue.pendingSize());
    setMode(NonceEngineMode.DEGRADED);
}
```

---

## 三、性能退化风险

### 3.1 submitter 数量爆炸

**风险描述**：
- 若每个用户/设备对应一个 submitter，系统可能支撑数百万 submitter
- 影响：
  - Redis 键空间膨胀（即使有 TTL）
  - PostgreSQL `submitter_nonce_state` 表增长
  - 锁竞争（虽然是 per-submitter 锁，但高并发仍有开销）

**缓解措施**：
1. **业务层限制**：
   - 在 Web 层增加"每个账号最多 N 个 submitter"的校验
   - 拒绝恶意创建大量 submitter 的请求
2. **监控与告警**：
   - 统计活跃 submitter 数量（如过去 24 小时有分配操作的）
   - 超过阈值时告警，排查是否有异常流量
3. **分片策略**（可选）：
   - 若 submitter 数量确实需要支撑百万级，考虑按 submitter hash 分片到多个数据库/Redis 实例

---

### 3.2 索引失效与慢查询

**风险描述**：
- `findOldestRecyclable` 查询需要扫描 `status='RECYCLABLE'` 的记录并排序
- 若 RECYCLABLE 记录过多，查询会变慢

**缓解措施**：
1. **索引优化**：
   - 确保 `(submitter, status, nonce)` 上有复合索引
   - 定期执行 `ANALYZE` 更新统计信息
2. **分页查询**：
   - `findOldestRecyclable` 加上 `LIMIT 1`（当前已是，避免全表扫描）
3. **定期清理**：
   - 将长时间未被复用的 RECYCLABLE 记录转为 USED（假设业务已放弃）

---

## 四、运维建议

### 4.1 监控指标（推荐实现）
- **DB 指标**：
  - 各状态记录数：`COUNT(*) GROUP BY status`
  - 表大小与增长趋势：`pg_total_relation_size('nonce_allocation')`
  - 慢查询：`pg_stat_statements`
- **Redis 指标**：
  - 键数量：`INFO keyspace`
  - 内存使用：`INFO memory`
  - 刷盘队列长度：`flushQueue.pendingSize()`
- **业务指标**：
  - 分配/回收 QPS 与时延
  - handler 超时次数
  - 自动降级次数

### 4.2 告警规则
| 指标 | 阈值 | 级别 | 说明 |
|------|------|------|------|
| RESERVED 记录超过 N 小时 | > 100 条 | P2 | 可能有孤儿记录 |
| 刷盘队列长度 | > 10000 | P1 | 刷盘跟不上，风险高 |
| USED 记录数量 | > 10M | P3 | 需要执行清理任务 |
| Redis 内存使用率 | > 80% | P1 | 内存不足，可能驱逐键 |
| 自动降级次数 | > 10/h | P1 | 性能模式不稳定 |

### 4.3 定期维护任务
- **每周**：检查数据清理任务是否正常执行（查看日志）
- **每月**：评估保留策略是否合理（`usedRecordRetentionDays`）
- **每季度**：执行 `VACUUM FULL` 回收空间（需停机或主从切换）

---

## 五、配置参考

### 生产环境推荐配置
```yaml
nonce:
  redis-key-ttl: 30d          # Redis 键 TTL（不活跃 submitter 自动过期）
  stale-reserved-timeout: 1h  # RESERVED 超时回收时间
  used-record-retention-days: 90  # USED 记录保留天数
  flush-batch-size: 200       # 刷盘批量大小
  flush-interval: 1s          # 刷盘频率
```

### 高负载环境（百万级 submitter）
```yaml
nonce:
  redis-key-ttl: 7d           # 缩短 TTL，加速过期
  stale-reserved-timeout: 30m # 更激进的回收策略
  used-record-retention-days: 30 # 缩短保留期，加快清理
  flush-batch-size: 500       # 增大批量，提升吞吐
```

### 开发/测试环境
```yaml
nonce:
  redis-key-ttl: 1d           # 快速过期，方便测试
  stale-reserved-timeout: 5m  # 快速回收，暴露问题
  used-record-retention-days: 7  # 短期保留，节省空间
```

---

## 六、实施优先级

| 措施 | 优先级 | 预期收益 | 实施成本 |
|------|--------|---------|---------|
| Redis key TTL | **P0** | 防止内存泄漏 | 已完成 |
| 孤儿 RESERVED 回收 | **P0** | 防止跳号/空间浪费 | 已框架，需实现 SQL |
| USED 记录清理 | **P1** | 控制表增长 | 已框架，需实现 SQL |
| 刷盘队列监控 | **P1** | 及时发现积压 | 需接入 Metrics |
| submitter 数量限制 | **P2** | 防止恶意创建 | 需在 Web 层实现 |
| 索引优化 | **P2** | 提升查询性能 | 需 DBA 配合 |
| 分片策略 | **P3** | 支撑超大规模 | 架构改造较大 |

---

**总结**：已通过配置和定时任务框架建立了基本的数据治理机制，剩余工作主要在 SQL 实现和监控接入。建议按优先级逐步落地，并在压测中验证效果。

