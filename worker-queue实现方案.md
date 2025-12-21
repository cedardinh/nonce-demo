# Worker-Queue 实现方案（可落地）

## 1. 背景与现状（基于 `src/main`）

- **现状**：当前 Worker-Queue 逻辑只在 `NonceController` 中按 `nonce.mode=worker-queue` 分支调用 `WorkerQueueDispatcher`。
- **核心一致性来源**：`NonceService` 已通过 **lease + fencing token + 事务** 保证跨节点正确性；Worker-Queue 的价值主要在**单节点内按 signer 串行化、削峰、降低热点竞争**。

## 2. 目标与约束

- **目标**
  - **使用方式完全不变**：业务仍注入/调用同一个 `NonceComponent`（不要求业务改代码、不要求改调用链），尤其覆盖现有最常用的 `allocate/markUsed/markRecyclable` 三段式用法。
  - **仅通过配置切换模式**：`nonce.mode=basic|worker-queue`。
  - **覆盖同一“维度”的入口**：`withNonce / allocate / markUsed / markRecyclable` 都受 Worker-Queue 影响（而不是只覆盖 Web Controller）。
- **约束**
  - Worker-Queue 只保证**单节点内**串行化；跨节点竞争仍依赖现有 lease+fencing。
  - 方案需规避：线程泄漏、无界队列内存风险、可重入导致死锁。

## 3. 总体方案（推荐）

### 3.1 方案一句话

在 **Spring 装配层**根据 `nonce.mode` 决定是否对 `NonceComponent` 做**代理增强**：启用时，代理在进入 `NonceComponent` 的 4 个入口前执行 `dispatch(signer, ...)`，将同 signer 的请求固定路由到同一个单线程 worker 队列；禁用时则保持原始行为。

### 3.2 为什么是“代理增强”而不是改 core API

- **业务使用 0 变更**：仍注入 `com.work.nonce.core.NonceComponent`（类），不需要把它接口化或引入新的门面类型。
- **改动面小**：只改 demo 配置装配逻辑 + 强化 dispatcher（生命周期/背压/死锁保护）。
- **更贴近“模式由配置决定”**：装配层可做到启用/关闭完全透明。

## 4. 关键设计点

### 4.1 Worker 路由策略

- **路由规则**：`idx = positiveHash(signer) % workerCount`
- **worker 模型**：每个 worker = **单线程执行器**，保证同 worker 上任务串行。
- **效果**：同一个 signer 的所有入口调用，在本节点内天然串行。

### 4.2 背压与队列（必须改造 `WorkerQueueDispatcher`）

当前实现用 `Executors.newSingleThreadExecutor`（无界队列）且没有关闭逻辑，建议改为：

- **有界队列**：例如 `ArrayBlockingQueue(capacity)`
- **拒绝策略**：
  - 推荐：**快速失败**（抛出可重试异常），上游做退避/重试
  - 或：CallerRuns（谨慎使用，会把背压传导到调用线程，可能拖慢服务线程池）
- **配置项建议**
  - `nonce.worker-count`：worker 数
  - `nonce.worker-queue-capacity`：单 worker 队列长度
  - `nonce.worker-queue-reject-policy`：reject 策略（FAIL_FAST / CALLER_RUNS）

### 4.3 可重入保护（避免“自己等自己”的死锁）

**问题**：如果在 worker 线程里再次调用 `NonceComponent`（例如 handler 内部又触发 `allocate/mark*`），`dispatch().get()` 会把任务再次投递到同一个单线程队列并等待结果，可能出现死锁。

**解决**：在 dispatcher 内加入 ThreadLocal 标记：

- 进入 `dispatch` 前判断：
  - 若当前线程已处于某个 worker 执行上下文（ThreadLocal 命中），则 **直接执行 task.call()**（不再 submit+get）
  - 否则按路由 submit 到对应 worker，并等待结果

这条规则能保证“队列串行化”不被破坏，同时消除可重入死锁风险。

### 4.4 线程池生命周期（必须补）

- `WorkerQueueDispatcher` 必须实现优雅关闭：
  - `@PreDestroy`：遍历 `ExecutorService` 执行 `shutdown()` + `awaitTermination()`；必要时 `shutdownNow()`
- 同时建议 **只在 worker-queue 模式**才创建 dispatcher（避免 basic 模式也启动 N 个线程）。

## 5. Spring 装配方式（只靠配置切换）

### 5.1 条件创建 dispatcher

- 使用 `@ConditionalOnProperty(prefix="nonce", name="mode", havingValue="worker-queue")`
- basic 模式不创建 dispatcher，也不创建代理，保持最小资源占用

### 5.2 代理增强 `NonceComponent`（核心落地点）

在 `NonceComponentConfiguration` 中：

- 先创建**原始** `NonceComponent`（你现在已有：`new NonceComponent(template, nonceService)`）
- 当 `nonce.mode=worker-queue` 时，返回一个代理对象：
  - 拦截 `withNonce(signer, handler)`、`allocate(signer)`、`markUsed(signer, nonce, txHash)`、`markRecyclable(signer, nonce, reason)`
  - 统一调用：`dispatcher.dispatch(signer, () -> proceed())`
- 当 `nonce.mode=basic` 时，直接返回原始对象

> 实现方式可选：
> - **Spring AOP（@Aspect）**：对 `NonceComponent` 做切面拦截（更 Spring-native，易维护）
> - **ProxyFactory/CGLIB**：在 @Bean 构造时手工包一层代理（依赖更少，改动更集中）

## 6. 业务侧使用方式（保持完全一致）

- 业务继续注入 `NonceComponent`（保持现有写法不变）：
  - 常见三段式：
    - `allocate(signer)`：领取 nonce
    - `markUsed(signer, nonce, txHash)`：成功后消费
    - `markRecyclable(signer, nonce, reason)`：失败/放弃后回收
  - `nonce.mode=basic`：行为与现状一致
  - `nonce.mode=worker-queue`：上述三段式与 `withNonce` 一样，都会先按 signer 进入 Worker-Queue 再执行，无需改调用点

### 6.1 重要前提（确保“改动最少”真的成立）

- 本方案默认：业务侧主要依赖 `NonceComponent.allocate/mark*` 这一层（而不是直接注入 `NonceService`）。
- 若现有项目里**大量代码直接注入 `NonceService`**：
  - 仍可做到“配置切换 + 使用过程不变”，但增强点要改为：对 `NonceService.allocate/mark*` 做同样的 Worker-Queue 代理（或提供一个 `@Primary` 的 `NonceService` 代理 Bean）。
  - 建议优先统一依赖入口到 `NonceComponent`，可减少需要拦截的面，整体更稳定。

## 7. 风险与应对（落地检查清单）

- **无界队列导致 OOM**
  - 应对：有界队列 + reject 策略 + 指标监控（队列长度、拒绝次数）
- **线程泄漏/无法优雅停机**
  - 应对：`@PreDestroy` 关闭线程池；basic 模式不创建 dispatcher
- **可重入导致死锁**
  - 应对：ThreadLocal 可重入直通执行
- **长耗时任务导致积压**
  - 应对：拆分慢链路（提交/回执异步）、调小队列容量并 fail-fast，把压力显式暴露给上游退避

## 8. 验证方式（建议）

- **功能验证**
  - 并发压测同 signer：确保请求在日志/指标上呈现串行（同一 signer 不交错）
  - 并发压测多 signer：确认能利用多 worker 并行
- **稳定性验证**
  - 队列打满：确认触发 reject 策略，且错误可被上游识别为可重试
  - 应用重启：确认线程池能被关闭（无残留线程）
- **可重入验证**
  - 在 `withNonce` handler 内部再次调用 `allocate/mark*`：不死锁

## 9. 落地步骤（最小改动路径）

1. 改造 `WorkerQueueDispatcher`：增加有界队列、reject 策略、ThreadLocal 可重入保护、`@PreDestroy`。
2. 调整装配：在 `NonceComponentConfiguration` 中按 `nonce.mode` 返回“原始/代理版” `NonceComponent`。
3. 删除 `NonceController` 中对 worker-queue 的分支（让模式切换仅通过配置决定）。
4. 增加基础测试：并发同 signer 串行、可重入不死锁、队列满触发拒绝。


