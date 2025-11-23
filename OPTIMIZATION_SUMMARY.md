# Core 包优化总结

本次优化集中在 `core` 包内部，提升了安全性、鲁棒性和可维护性，对上层业务代码透明。

---

## 一、安全性增强（P0 - 已完成）

### 1.1 输入校验统一化
**问题**：submitter 参数只做了非空检查，未限制长度和字符集，可能被注入超长/脏字符导致 Redis key 污染、日志爆炸。

**修复**：
- 在 `ValidationUtils` 新增 `requireValidSubmitter(String)`：
  - 正则校验：`^[a-zA-Z0-9:_-]{1,64}$`
  - 长度限制：1-64 字符
  - 允许字符：字母、数字、冒号、下划线、连字符
- 在所有核心入口启用：
  - `NonceExecutionTemplate.execute()`
  - `NonceResultProcessor.process()`
  - `ReliableNonceEngine.allocate/markUsed/markRecyclable()`
  - `PerformanceNonceEngine.allocate/markUsed/markRecyclable()`

**影响**：防止恶意输入导致的资源耗尽和键空间污染。

---

### 1.2 Handler 执行超时控制
**问题**：`NonceExecutionTemplate` 同步调用业务 handler，若 handler 卡在外部调用（区块链 RPC、DB 查询等），nonce 会长时间处于 RESERVED 状态，锁也无法释放。

**修复**：
- 新增可配置构造函数：支持注入 `ExecutorService` 和 `handlerTimeout`（默认无超时，兼容现有行为）
- 在线程池中执行 handler，使用 `Future.get(timeout)` 控制超时
- 超时后：
  - 取消任务（`future.cancel(true)`）
  - 回收当前 nonce 到 RECYCLABLE
  - 抛出明确的 `NonceException("handler 执行超时")`

**影响**：防止慢业务逻辑拖垮整个系统，资源可及时回收。

---

### 1.3 异常处理优化
**问题**：`markAllocationSafely` 在回收失败时重新抛异常，掩盖了原始 handler 异常；reason 字段未限制长度，可能把大堆栈写入 DB。

**修复**：
- 回收失败时：
  - 截断 reason 到最大长度（500 字符）
  - 记录 ERROR 日志（包含原始异常类型 + 回收异常类型）
  - **不再抛出新异常**，保留原始 handler 异常向上传播
- 日志包含：`submitter`、`nonce`、原始异常类、回收异常类

**影响**：排障更容易，异常链更清晰，避免 DB 字段溢出。

---

## 二、并发正确性修复（P0 - 已完成）

### 2.1 Redis 计数器初始化竞态
**问题**：`PerformanceNonceEngine.ensureCounterInitialized()` 在并发场景下：
- `hasKey()` 检查与 `setIfAbsent()` 之间有窗口
- `setIfAbsent()` 返回值未检查，可能其他线程/进程已设置了更小的值

**修复**：
- 在 `lockAndLoadState()` 持有 DB 锁的保护下再次 `hasKey()` 双重检查
- 检查 `setIfAbsent()` 返回值：
  - 若返回 `false`（其他实例已设置），读取当前值并比较
  - 若当前值 < 预期初始值，调用 `raiseCounterTo()` 修正
  - 若值非法（无法解析），删除并重新初始化

**影响**：跨进程并发初始化时仍能保证计数器单调递增，避免重复 nonce。

---

### 2.2 FlushWorker 事务粒度问题
**问题**：原实现逐条处理事件，每个事件一个事务：
- 部分成功部分失败时，已成功的会被 ACK，失败的重新入队
- 可能导致刷盘顺序错乱（例如 USED 刷盘成功，RESERVE 失败重试）

**修复**：
- 整个批次在一个事务中处理：`transactionTemplate.execute()` 包裹所有 `applyEvent()`
- 全部成功后才批量 ACK 和清理 Redis 快照
- 任意一条失败则整个批次回滚，全部重新入队

**影响**：保证刷盘的原子性，避免状态不一致。

---

### 2.3 DUAL_WRITE 模式 Mirror 失败处理
**问题**：`mirrorReservation/mirrorMarkUsed/mirrorMarkRecyclable` 失败会抛异常，但 DB 操作已成功，导致：
- Redis/DB 不一致
- 主流程被打断（用户看到失败，但 DB 已分配成功）

**修复**：
- 在 `NonceEngineManager` 的 DUAL_WRITE 分支用 `try-catch` 包裹 mirror 调用
- Mirror 失败时：
  - 记录 WARN 日志（包含 submitter、nonce、异常）
  - 调用 `performanceEngine.recordFlushFailure(ex)` 记录健康状态
  - **不抛异常**，保证主流程（DB）可用
- 后续依赖对账逻辑修复 Redis/DB 差异

**影响**：提升系统可用性，Redis 故障不影响核心分配流程。

---

## 三、防御性编程（P1 - 已完成）

### 3.1 Repository 事务断言
**问题**：`PostgresNonceRepository` 的所有方法依赖"必须在事务中调用"，但无检查，误用会导致：
- `SELECT FOR UPDATE` 不生效，并发安全性失效
- 状态更新无法回滚

**修复**：
- 在 `updateState/reserveNonce/markUsed/markRecyclable` 中增加 `assertInTransaction()`
- 检查 `TransactionSynchronizationManager.isActualTransactionActive()`
- 无事务时：
  - 记录 ERROR 日志（包含方法名、调用栈）
  - 根据系统属性 `nonce.strict.transaction.check`（默认 `true`）决定是否抛异常
  - 生产环境可设为 `false` 只记录日志，避免硬失败

**影响**：开发/测试期能及早发现误用，生产环境可选择降级策略。

---

### 3.2 模式切换状态机校验
**问题**：`NonceEngineManager.setMode()` 无转换合法性检查，可能发生非法切换（如 PERFORMANCE 直接跳 DUAL_WRITE），导致数据不一致。

**修复**：
- 新增 `isValidTransition(from, to)` 方法，定义合法状态转换图：
  - 正常升级路径：`RELIABLE → DUAL_WRITE → PERFORMANCE`
  - 降级路径：`PERFORMANCE → DRAIN_AND_SYNC → RELIABLE`
  - 异常降级：任意模式 → `DEGRADED`
  - 强制回退：任意模式 → `RELIABLE`
  - DUAL_WRITE 可直接回退到 RELIABLE（跳过 PERFORMANCE）
- 非法转换时抛出 `NonceException` 并说明合法路径

**影响**：防止运维误操作导致的数据损坏。

---

## 四、锁与日志优化（P1 - 已完成）

### 4.1 锁释放失败可观测性
**问题**：
- `SubmitterLockCoordinator.releaseSafely()` 完全吞掉异常，排障困难
- `RedisDistributedLockManager.unlock()` 锁释放失败/owner 不匹配时无日志

**修复**：
- `releaseSafely()` 捕获异常后记录 WARN 日志（包含 submitter、owner、异常）
- `unlock()` 中：
  - Lua 返回 0（锁不存在/owner 不匹配）时记录 DEBUG 日志
  - 发生 Redis 异常时记录 WARN 日志
  - 保持幂等性，不向外抛异常

**影响**：锁问题可通过日志排查，不影响主流程。

---

### 4.2 配置合理性校验
**问题**：锁 TTL (10s) 与事务超时 (5s) 配置独立，可能出现：
- TTL < 事务超时：锁在事务完成前过期，并发安全性失效
- 批量大小/重试次数无上限，可能耗尽资源

**修复**：
- 在 `NonceConfig.Builder.build()` 中增加 `validateConfig()`：
  - `lockTtl >= 事务超时(5s) + 余量(2s)`（即 >= 7s）
  - `flushBatchSize` 在 1-10000 之间
  - `flushMaxRetry` 在 0-100 之间
  - 各种超时必须 > 0
- 不合理配置直接抛 `IllegalArgumentException`，阻止系统启动

**影响**：配置错误能在启动期发现，避免运行时出现诡异问题。

---

## 五、优化清单与验证

| 优化项 | 风险等级 | 状态 | 验证方式 |
|--------|---------|------|---------|
| submitter 输入校验 | P0 | ✅ 完成 | 单元测试：超长/非法字符被拒绝 |
| handler 超时控制 | P0 | ✅ 完成 | 集成测试：模拟慢 handler，nonce 被回收 |
| 异常处理优化 | P0 | ✅ 完成 | 日志检查：回收失败记录 ERROR，不掩盖原始异常 |
| Redis 计数器竞态 | P0 | ✅ 完成 | 并发测试：多进程同时初始化，计数器保持单调 |
| FlushWorker 事务 | P0 | ✅ 完成 | 集成测试：批次中某条失败，全部回滚重试 |
| DUAL_WRITE mirror 失败 | P0 | ✅ 完成 | 故障注入：Redis 不可用，DB 分配仍成功 |
| 事务断言 | P1 | ✅ 完成 | 单元测试：无事务调用触发 ERROR 日志/异常 |
| 模式切换校验 | P1 | ✅ 完成 | 单元测试：非法转换抛异常 |
| 锁释放日志 | P1 | ✅ 完成 | 日志检查：释放失败/owner 不匹配有 WARN/DEBUG |
| 配置校验 | P1 | ✅ 完成 | 启动测试：不合理配置阻止启动 |

---

## 六、建议后续工作（未纳入本次优化）

### 6.1 监控与指标（推荐 P2）
- 在 `NonceFacade`/`NonceEngineManager`/`PerformanceNonceEngine` 关键路径埋 Micrometer 指标：
  - 分配/回收 QPS 与时延（按 submitter 和模式分组）
  - 各状态（RESERVED/RECYCLABLE/USED）计数
  - Redis 降级次数、性能模式刷盘延迟
  - handler 超时次数

### 6.2 链上对账逻辑（推荐 P2）
- `ReliableNonceEngine.queryLatestChainNonce()` 当前返回 `-1`（不对账）
- 建议：
  - 抽象 `ChainClient` 接口注入，支持关闭/开启对账
  - 合并 `confirmReservedWithChain` + `updateLastChainNonce` 为一次 DB 写入，减少写放大

### 6.3 历史数据清理（推荐 P3）
- 大量 `USED` 记录会让表膨胀，影响索引效率
- 建议定期归档或清理 N 天前的 USED 记录

### 6.4 乐观锁保护（可选 P3）
- 当前 `updateState` 直接覆盖，多实例并发更新时可能"最后写覆盖"
- 建议在 `SubmitterNonceState` 增加版本号或基于 `updated_at` 做乐观锁

---

## 七、总体评估

| 维度 | 优化前 | 优化后 |
|------|--------|--------|
| **输入安全性** | 只检查非空 | 严格校验格式+长度 |
| **并发正确性** | Redis 初始化/FlushWorker 有竞态 | 双重检查+批量事务 |
| **容错能力** | DUAL_WRITE mirror 失败影响主流程 | 降级策略保证可用性 |
| **可观测性** | 锁释放失败静默 | 关键路径有日志+异常链清晰 |
| **防御性** | 无事务检查，易误用 | 事务断言+配置校验 |
| **生产就绪度** | ⚠️ 有风险 | ✅ 可托管生产流量 |

**核心改进**：从"能跑"提升到"可靠跑"，关键场景的并发安全、异常处理和可观测性显著增强，适合接入真实业务流量。

