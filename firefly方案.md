# FireFly Transaction Manager 方案研究报告

> 目标：提炼 FireFly Transaction Manager（FFTM）在 **nonce 分配** 与 **链上状态跟踪到终局** 方面的可复用方法论，覆盖“nonce 正常消费/未正常消费”的主要场景与对应处理策略，供其他项目落地参考。
>
> 说明：本报告聚焦 **与 nonce、交易提交、receipt、确认数、重组、重提、幂等与持久化一致性** 直接相关的代码路径；FFTM 内与此无关的模块（如 eventstream webhook/websocket 分发细节、指标/HTTP server 等）不作为展开重点。

---

## 1. FFTM 的总体架构取向

### 1.1 “源头管理 at source”
- FFTM 的核心假设：**同一签名密钥的交易应由同一个 nonce 管理系统统一治理**。
- 代价与边界：
  - 如果密钥被多系统共享，FFTM 仍可运行，但会依赖更保守的策略（例如把 `transactions.nonceStateTimeout` 调到接近 0），牺牲性能换正确性窗口缩小。

### 1.2 交易即 nonce 索引（关键差异点）
- FFTM 并未像本项目那样用独立的 `allocation(status=RESERVED/USED/RECYCLABLE)` 表描述 nonce 生命周期。
- 它把 **“交易记录（ManagedTX）”** 当作 nonce 的事实载体：nonce 一旦分配，就写入交易记录并进入统一的交易状态机（Pending/Tracking/Confirmed 等）。
- 好处：nonce 与交易状态天然绑定，便于“重提同 nonce”“对账”“确认数推进”“重组回滚时重算确认列表”等闭环。

---

## 2. Nonce 分配：并发控制与“链上 vs 本地”决策

### 2.1 Postgres：确定性路由 worker 串行化（强建议借鉴）
- 核心：把 **同一 signer 的插入/nonce 分配请求** 路由到同一个 writer worker，从源头避免并发冲突。
- `internal/persistence/postgres/transaction_writer.go`：
  - `queue()`：插入按 `From(signer)` 路由；更新按 `txID` 路由。
  - `worker()`：批处理（batchSize/batchTimeout），提升吞吐。
  - `runBatch()`：在一个 DB group/事务中执行 nonce 分配、插入、更新、receipt upsert、confirmations 写入、history 写入、completions 写入与删除。

### 2.2 Postgres：三级决策 max(chain, cache, db)
- `assignNonces()` 关键规则：
  - 若缓存有效：直接用 `cacheEntry.nextNonce` 递增分配。
  - 若缓存缺失/过期：调用 `NextNonceForSigner` 得到链上 next nonce；再取内部 next nonce（来自过期缓存或 DB 中最高 nonce+1）；取两者最大值。
  - 分配后 `nextNonce++` 写回 LRU 缓存。
- 关键设计点：
  - “过期缓存仍用于比较”：防止同一批次未提交时 DB 落后于缓存导致回退。
  - 批次失败清理缓存 `clearCachedNonces()`：防止“缓存递增了但 DB 没提交成功”导致跳号。

### 2.3 幂等：避免“重复请求”消耗 nonce
- `pkg/txhandler/simple/simple_transaction_handler.go`：
  - `requestIDPreCheck()`：对外部请求 ID 做 DB 预查，尽量早返回 409，避免进入后续链上查询与资源消耗。
- `internal/persistence/postgres/transaction_writer.go`：
  - `preInsertIdempotencyCheck()`：批处理插入前做二次检查（针对并发 API 调用的小窗口），发现重复则 `sentConflict=true`，该 op 不参与 nonce 分配。

---

## 3. 交易提交与“nonce 是否真正被消费”

### 3.1 提交前拒绝：nonce 不会被“花掉”
- `pkg/ffcapi/submission_error.go`：
  - `MapSubmissionRejected()`：把 **输入非法、revert、余额不足** 等定义为 `SubmissionRejected=true`。
  - 语义：交易在 prepare 阶段已被判定必失败，因此 **不会进入 FFTM 持久化**，也就不会占用 nonce。
- `simple_transaction_handler.go`：
  - `TransactionPrepare`/`DeployContractPrepare` 失败直接返回；只在 prepare 成功后才创建 ManagedTX 并进入 nonce 分配持久化。

### 3.2 提交成功与“已知交易”错误：把“链上/节点已接收”与“本地成功”解耦
- `submitTX()` 的关键分支：
  - 成功：写入 `tx_hash`、`last_submit`，子状态进入 Tracking。
  - 失败但 reason 为 `ErrorKnownTransaction` 或 `ErrorReasonNonceTooLow`：
    - 如果本地已有 `TransactionHash`：认为是可接受的幂等/重复提交现象，返回成功。
    - 如果本地没有 `TransactionHash`：返回错误，并明确指出需要 connector 支持“重算预期 tx hash”的能力来补齐这个灰区。

### 3.3 长期 pending：定期 resubmit 同 nonce（强建议借鉴）
- `processTransaction()`：
  - 若无 receipt 且超过 `resubmitInterval`：记录 Timeout 动作，切到 Stale 子状态，再次 `submitTX()`。
  - 目的：处理“交易池驱逐、节点重启丢失 mempool、网络抖动”等导致 txHash 长期无 receipt 的情况。
- 注意：simple handler 没有 gas bump 策略；代码也明确提示更高级策略可以逐步提高 gas。

---

## 4. 终局确认：receipt 异步拉取 + 确认数 + 重组检测

### 4.1 receiptChecker：异步、无界队列、错误不阻塞主路径
- `internal/confirmations/receipt_checker.go`：
  - 用 `list.List` 存储待检查条目，避免固定长度队列导致“关键路径阻塞”。
  - worker 取队头检查 receipt：
    - NotFound：视为未就绪，更新 `lastReceiptCheck`，不报错。
    - 非 NotFound 错误：把条目放回队尾并触发 retry backoff，避免单条卡死。

### 4.2 blockConfirmationManager：把“链视图一致性”放在首位
- `internal/confirmations/confirmations.go`：
  - `pendingItem` 同时覆盖“事件日志”与“交易”两类对象。
  - `scheduleReceiptChecks()`：按 `staleReceiptTimeout` 重新调度 receipt 检查。
  - `dispatchReceipt()`：收到 receipt 后记录 `blockNumber/blockHash`，再 `walkChainForItem()` 去计算确认数。
  - `blockState.getByNumber()`：保证一次循环里对链的视图一致（不引入更高块、同一高度不变更 hash），防止乱序确认。

### 4.3 确认数与重组：NewFork 语义（强建议借鉴）
- `dispatchConfirmations()`：
  - 维护 `notifiedConfirmations`，比较新 confirmations 的 `BlockHash` 序列：
    - 一旦发现不一致，标记 `newFork=true`，并把完整 confirmations 集合重新通知下游（替换而不是追加）。
  - 这提供了一个通用协议：下游存储 confirmations 时，若 `newFork=true` 则“全量覆盖”，否则“增量追加”。

### 4.4 confirmedBlockListener：只输出达到确认阈值的稳定区块流
- `internal/confirmations/confirmed_block_listener.go`：
  - 用 `requiredConfirmations` 构建一个“滚动窗口”，只有窗口外的区块才 dispatch。
  - 对 reorg：发现同高度冲突，会裁剪并重新拼接，必要时丢弃并重建视图，保证输出的区块序列单调可靠。

### 4.5 blocklistener.BufferChannel：避免下游阻塞导致“全局停摆”
- `internal/blocklistener/blocklistener.go`：
  - 当下游确认队列满导致阻塞，会丢弃新块事件但设置 `GapPotential=true`，提示存在缺口。
  - 目的：保证“一个阻塞的 stream 不会拖死其它 stream”。

### 4.6 confirmations 的关键可调参数（建议补齐到你们系统配置）
- `confirmations.required`：确认阈值，决定何时认为终局（默认 20）。
- `confirmations.staleReceiptTimeout`：pending 交易强制重查 receipt 的周期（默认 1m），用于避免 receipt 检查“只靠新块触发”导致的滞后。
- `confirmations.fetchReceiptUponEntry`：新交易入队时是否立刻拉 receipt；否则要等新块或 stale 超时才查（默认 false）。
- `confirmations.receiptWorkers`：并行拉 receipt 的 worker 数（默认 10）。
- `confirmations.blockQueueLength / notificationQueueLength`：内部队列长度，用于削峰，避免上游阻塞把系统拖死。

---

## 5. 场景全集：nonce 正常消费与异常消费（以及 FFTM 的处理）

> 下列场景按“链上/节点状态”归类，同时给出 FFTM 的行为与可借鉴点。

### 5.1 正常路径：nonce 正常消费并终局
- 过程：
  - 分配 nonce → 写入交易记录 → submit 成功拿到 txHash → receipt 到达 → confirmations 达阈值 → 终局。
- 借鉴点：
  - 把 nonce 与交易记录绑定，状态推进由后台系统（receipt/confirmations）驱动，而不是“提交即终局”。

### 5.2 交易准备阶段被拒绝：nonce 未消费
- 触发：InvalidInputs / Reverted / InsufficientFunds 等在 prepare 阶段可判定。
- 行为：`SubmissionRejected=true`，不写库，不分配 nonce，不进入后续跟踪。
- 借鉴点：把“确定性失败”前置，避免占号与状态污染。

### 5.3 提交超时或网络错误但节点已接收：nonce 可能已被消费
- 触发：`TransactionSend` 返回错误，但实际上 tx 已进节点 txpool。
- 行为：
  - 若本地已有 txHash：`known_transaction/nonce_too_low` 视为成功（幂等）。
  - 若本地无 txHash：返回错误，并指出需要“重算 txHash”的 connector 能力来消除不确定性。
- 借鉴点：
  - 需要为“已接收但本地没记住 hash”的灰区设计补偿手段，否则会出现“误以为失败导致重复提交/nonce 冲突”。

### 5.4 长期无 receipt：nonce 未终局，可能未消费或被 txpool 丢弃
- 触发：一直查不到 receipt。
- 行为：到 `resubmitInterval` 周期触发 resubmit（同 nonce）。
- 借鉴点：
  - “未终局一段时间后失败”在 FFTM simple handler 里没有直接判死，而是倾向“重提直到成功/失败”；
  - 若你们要支持“超时判死并回收 nonce”，需要额外的业务策略与更强的链上诊断（替换检测、txpool 观察等）。

### 5.5 链重组：已看到 receipt/确认但随后回滚
- 触发：区块 hash 链断裂或 fork。
- 行为：
  - confirmations 用 `newFork` 通知下游替换确认列表；
  - confirmed block stream 会裁剪并重建后续视图，保证输出稳定块序列。
- 借鉴点：
  - 用“确认列表可回退”的协议替代“一次确认永远有效”的假设；
  - 把终局阈值配置化（requiredConfirmations）。

### 5.6 多系统共用同一 signer：nonce 可能被外部消费导致冲突
- 触发：外部系统也在用同一私钥发送交易。
- 行为：nonce 分配时取 `max(chain, db/cache)`，避免重用；但可能带来 gap 或本地 pending 与链上不一致。
- 配置建议：把 `transactions.nonceStateTimeout` 调小，让分配更频繁参考链上。
- 借鉴点：明确“单一管理源”是根本，否则只能在性能与冲突窗口之间折中。

### 5.7 进程崩溃/重启：nonce 分配过但未提交
- 触发：nonce 已写入交易记录，但未成功 submit（或 submit 前崩溃）。
- 行为（核心取决于 nonceStateTimeout）：
  - 若本地记录仍“新鲜”：倾向继续用本地 nonce + 1（保护 pending）。
  - 若已“过期”：会查询链上，并取最大值，避免重用。
- 借鉴点：`nonceStateTimeout` 是“可恢复性 vs 安全性 vs 性能”的总闸。

### 5.8 重组导致确认回退但不丢事件：确认列表可替换是必须能力
- FFTM 不把“收到若干确认”当作不可逆，而是通过 `newFork=true` 通知下游做覆盖更新。
- 这点对你们特别关键：如果你们要支持 SAFE/FINALIZED 与“长时间未终局后判定失败”，就必须允许状态从“接近终局”回退到“未终局继续等待/重提/最终失败”。

---

## 6. 最值得迁移到你们 Java nonce-management 的方法清单

### 6.1 并发模型：按 submitter 串行化关键写入
- 你们当前：CAS + UNIQUE + 退避重试，冲突高时会产生重试风暴。
- 借鉴 FFTM：引入“按 submitter 路由到同一 worker/队列”的串行化层，让 DB 更像“顺序日志落盘”。

### 6.2 终局系统：从“提交即 USED”改为“确认驱动 USED”
- 最小落地：
  - 新增后台确认器：拉取 receipt、计算确认数、处理 reorg。
  - `USED` 只在达到目标终局（SAFE/FINALIZED 或 N confirmations）后写入。
  - 允许“回退”：若链重组导致确认链不一致，回退到非终局状态继续等待。

### 6.3 长期 pending：引入 resubmit 同 nonce
- 为每个 RESERVED/INFLIGHT 记录维护 `firstSubmitAt/lastSubmitAt`；
- 超过阈值无 receipt：重提同 nonce（可选 gas bump）。

### 6.4 幂等与灰区补偿
- 交易提交接口应返回并持久化 txHash；
- 对“节点已接收但本地没拿到 txHash”的灰区，设计：
  - connector 能力：重算 txHash 或查询 txpool；
  - 或业务层强约束：提交必须拿到 txHash 才算成功。

---

## 7. 本报告阅读覆盖的关键文件清单（可复查入口）

### 7.1 Nonce 与持久化
- `internal/persistence/postgres/transaction_writer.go`
- `internal/persistence/postgres/transactions.go`
- `internal/persistence/postgres/receipts.go`
- `internal/persistence/postgres/confirmations.go`
- `internal/persistence/postgres/txhistory.go`
- `internal/persistence/postgres/transaction_completions.go`
- `pkg/apitypes/managed_tx.go`

### 7.2 提交与策略循环
- `pkg/txhandler/simple/simple_transaction_handler.go`
- `pkg/txhandler/simple/policyloop.go`
- `pkg/txhandler/simple/config.go`
- `pkg/ffcapi/submission_error.go`

### 7.3 receipt、确认数、重组
- `internal/confirmations/confirmations.go`
- `internal/confirmations/receipt_checker.go`
- `internal/confirmations/confirmed_block_listener.go`
- `internal/blocklistener/blocklistener.go`
- `pkg/ffcapi/transaction_receipt.go`
- `pkg/ffcapi/next_nonce_for_signer.go`

### 7.4 配置默认值
- `internal/tmconfig/tmconfig.go`
- `config.md`

---

## 9. 本次补充的遗漏点摘要

1. **Postgres receipts/confirmations/history/completions 的落库语义**：
   - receipt/confirmations/history 很多是 **异步入 writer 队列**（性能），通过 batch 在同一 DB group 内统一落库。
   - confirmations 支持 `clearExisting`（fork）并在 batch 里先 delete 再 insert，匹配 `newFork` 语义。
   - transaction_completions 用 DB 锁保证序列可单调消费（允许回滚缺口）。
   - txhistory 支持压缩合并，避免无限膨胀。
2. **关键配置项补齐**：confirmations 的 required/staleReceiptTimeout/fetchReceiptUponEntry/receiptWorkers 等是终局系统的“性能与正确性”杠杆；transactions.nonceStateTimeout 与 simple.resubmitInterval 决定 nonce 与 pending 自愈的行为边界。

---

## 8. 你们项目的下一步建议（MVP 路线）

1. **把 `markUsed` 延后**：handler 只标记“已广播拿到 txHash”，进入 INFLIGHT；不要立即 USED。
2. **加确认器**：后台轮询 receipt + 确认数，达到阈值再 USED；发现 fork 回退并重算。
3. **加 resubmit**：超过阈值无 receipt，重提同 nonce；必要时支持 gas bump。
4. **把 submitter 串行化**：用队列/worker 降低 DB 冲突与重试风暴。


