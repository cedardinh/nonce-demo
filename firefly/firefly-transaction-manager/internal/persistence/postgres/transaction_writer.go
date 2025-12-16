// Copyright © 2025 Kaleido, Inc.
//
// SPDX-License-Identifier: Apache-2.0
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"context"
	"fmt"
	"hash/fnv"
	"time"

	lru "github.com/hashicorp/golang-lru/v2"

	"github.com/hyperledger/firefly-common/pkg/config"
	"github.com/hyperledger/firefly-common/pkg/dbsql"
	"github.com/hyperledger/firefly-common/pkg/fftypes"
	"github.com/hyperledger/firefly-common/pkg/i18n"
	"github.com/hyperledger/firefly-common/pkg/log"
	"github.com/hyperledger/firefly-transaction-manager/internal/persistence"
	"github.com/hyperledger/firefly-transaction-manager/internal/tmmsgs"
	"github.com/hyperledger/firefly-transaction-manager/pkg/apitypes"
	"github.com/hyperledger/firefly-transaction-manager/pkg/txhandler"
)

type transactionOperation struct {
	txID         string
	sentConflict bool
	done         chan error

	opID               string
	isShutdown         bool
	txInsert           *apitypes.ManagedTX
	noncePreAssigned   bool
	nextNonceCB        txhandler.NextNonceCallback
	txUpdate           *apitypes.TXUpdates
	txDelete           *string
	clearConfirmations bool
	confirmation       *apitypes.ConfirmationRecord
	receipt            *apitypes.ReceiptRecord
	historyRecord      *apitypes.TXHistoryRecord
}

type txCacheEntry struct {
	lastCompacted *fftypes.FFTime
}

type nonceCacheEntry struct {
	cachedTime *fftypes.FFTime
	nextNonce  uint64
}

type transactionWriter struct {
	p                   *sqlPersistence
	txMetaCache         *lru.Cache[string, *txCacheEntry]
	nextNonceCache      *lru.Cache[string, *nonceCacheEntry]
	compressionInterval time.Duration
	bgCtx               context.Context
	cancelCtx           context.CancelFunc
	batchTimeout        time.Duration
	batchMaxSize        int
	workerCount         uint32
	workQueues          []chan *transactionOperation
	workersDone         []chan struct{}
}

type transactionWriterBatch struct {
	id             string
	opened         time.Time
	ops            []*transactionOperation
	timeoutContext context.Context
	timeoutCancel  func()

	txInsertsByFrom     map[string][]*transactionOperation
	txUpdates           []*transactionOperation
	txDeletes           []string
	receiptInserts      map[string]*apitypes.ReceiptRecord
	historyInserts      []*apitypes.TXHistoryRecord
	compressionChecks   map[string]bool
	confirmationInserts []*apitypes.ConfirmationRecord
	confirmationResets  map[string]bool
}

func newTransactionWriter(bgCtx context.Context, p *sqlPersistence, conf config.Section) (tw *transactionWriter, err error) {
	workerCount := conf.GetInt(ConfigTXWriterCount)
	batchMaxSize := conf.GetInt(ConfigTXWriterBatchSize)
	cacheSlots := conf.GetInt(ConfigTXWriterCacheSlots)
	tw = &transactionWriter{
		p: p,
		//nolint:gosec // Safe conversion as workerCount is always positive
		workerCount:         uint32(workerCount),
		batchTimeout:        conf.GetDuration(ConfigTXWriterBatchTimeout),
		batchMaxSize:        batchMaxSize,
		workersDone:         make([]chan struct{}, workerCount),
		workQueues:          make([]chan *transactionOperation, workerCount),
		compressionInterval: conf.GetDuration(ConfigTXWriterHistoryCompactionInterval),
	}
	tw.txMetaCache, err = lru.New[string, *txCacheEntry](cacheSlots)
	if err == nil {
		tw.nextNonceCache, err = lru.New[string, *nonceCacheEntry](cacheSlots)
	}
	if err != nil {
		return nil, err
	}
	tw.bgCtx, tw.cancelCtx = context.WithCancel(bgCtx)
	for i := 0; i < workerCount; i++ {
		tw.workersDone[i] = make(chan struct{})
		tw.workQueues[i] = make(chan *transactionOperation, batchMaxSize)
		go tw.worker(i)
	}
	return tw, nil
}

func newTransactionOperation(txID string) *transactionOperation {
	return &transactionOperation{
		opID: fftypes.ShortID(),
		txID: txID,
		done: make(chan error, 1), // 1 slot to ensure we don't block the writer
	}
}

func (op *transactionOperation) flush(ctx context.Context) error {
	select {
	case err := <-op.done:
		log.L(ctx).Debugf("Flushed write operation %s (err=%v)", op.opID, err)
		return err
	case <-ctx.Done():
		return i18n.NewError(ctx, i18n.MsgContextCanceled)
	}
}

// queue 将交易操作加入工作队列
// 这是PostgreSQL并发控制的关键：通过确定性路由实现nonce分配的串行化
//
// 核心设计原则：
// 1. 同一签名者的所有插入/nonce分配请求路由到同一个worker
//   - 这确保nonce分配是确定性的（串行化）
//   - 同时允许在单个数据库事务中批量插入多个交易对象
//
// 2. 插入后的更新操作按交易ID路由到同一个worker
//   - 确保所有顺序敏感的项目（如历史记录）按正确顺序写入
//
// 重要限制：
// - 所有交易插入操作必须等待完成后才能执行更新
// - 这保证了nonce分配和后续操作的一致性
//
// 跨签名者的顺序：
// - 不同签名密钥的交易插入顺序不重要
// - 因为调用者会等待每个插入完成后才返回给上层（FireFly Core）
// - 如果多个交易并发排队但使用不同ID，无法保证确定性顺序
//
// 参数：
// - ctx: 调用者的上下文（用于超时控制）
// - op: 要排队的交易操作
func (tw *transactionWriter) queue(ctx context.Context, op *transactionOperation) {
	// ========== 第一步：确定路由键 ==========

	// 根据操作类型确定路由键（hashKey）
	// 路由键决定了操作被分配到哪个worker
	var hashKey string

	if op.txInsert != nil {
		// 情况A：交易插入操作
		// 使用签名者地址作为路由键
		//
		// 这是nonce管理的关键：
		// - 同一签名者的所有交易会路由到同一个worker
		// - 该worker会串行处理这些交易的nonce分配
		// - 确保nonce按正确顺序递增（100, 101, 102...）
		hashKey = op.txInsert.From
	} else {
		// 情况B：交易更新/删除操作
		// 使用交易ID作为路由键
		//
		// 原因：
		// - 交易的更新操作应该在同一个worker中按顺序执行
		// - 例如：状态更新、历史记录、receipt等
		// - 确保历史记录的时间顺序正确
		hashKey = op.txID
	}

	// 验证路由键有效性
	if hashKey == "" {
		// 路由键为空，这是无效的操作
		// 直接发送错误到done通道
		// 注意：done通道有1个缓冲槽，所以这里不会阻塞
		op.done <- i18n.NewError(ctx, tmmsgs.MsgTransactionOpInvalid)
		return
	}

	// ========== 第二步：计算目标worker ==========

	// 使用FNV-1a哈希算法计算worker索引
	// FNV是一个简单、快速的非加密哈希算法
	h := fnv.New32a()

	// 将路由键（字符串）写入哈希函数
	// 返回值被忽略，因为Write方法总是成功
	_, _ = h.Write([]byte(hashKey))

	// 计算哈希值并对worker数量取模
	// 结果是 0 到 (workerCount-1) 之间的整数
	//
	// 关键特性：
	// - 相同的hashKey总是得到相同的routine索引
	// - 这保证了确定性路由：同一签名者总是到同一worker
	routine := h.Sum32() % tw.workerCount

	// 记录调试日志：操作被路由到哪个worker
	// 格式：操作ID / worker编号（如 tx_writer_0003）
	log.L(ctx).Debugf("Queuing write operation %s to worker tx_writer_%.4d", op.opID, routine)

	// ========== 第三步：发送到工作队列 ==========

	// 使用select语句处理三种可能的情况
	select {
	case tw.workQueues[routine] <- op:
		// 情况1：成功入队
		// 操作已发送到目标worker的队列
		// 函数返回，调用者可以通过op.done等待完成

	case <-ctx.Done():
		// 情况2：调用者上下文被取消（超时或取消）
		//
		// 处理策略：
		// - 直接返回，不发送错误到done通道
		// - 因为调用者已经放弃了这个请求
		// - 如果调用者仍然调用flush()，会得到context.Canceled错误
		//
		// 注意：这避免了向可能无人监听的通道发送数据

	case <-tw.bgCtx.Done():
		// 情况3：系统正在关闭
		//
		// 处理策略：
		// - 发送关闭错误到done通道
		// - 注意：done通道有1个缓冲槽，所以这里安全（不会阻塞）
		// - 调用者会在flush()时收到关闭错误
		op.done <- i18n.NewError(ctx, tmmsgs.MsgShuttingDown)
	}

	// ========== 队列操作完成 ==========
	// 此时操作已：
	// 1. 成功入队等待处理，或
	// 2. 因调用者取消而被忽略，或
	// 3. 因系统关闭而返回错误
}

func (tw *transactionWriter) worker(i int) {
	defer close(tw.workersDone[i])
	workerID := fmt.Sprintf("tx_writer_%.4d", i)
	ctx := log.WithLogField(tw.bgCtx, "job", workerID)
	l := log.L(ctx)
	var batch *transactionWriterBatch
	batchCount := 0
	workQueue := tw.workQueues[i]
	var shutdownRequest *transactionOperation
	for shutdownRequest == nil {
		var timeoutContext context.Context
		var timedOut bool
		if batch != nil {
			timeoutContext = batch.timeoutContext
		} else {
			timeoutContext = ctx
		}
		select {
		case op := <-workQueue:
			if op.isShutdown {
				// flush out the queue
				shutdownRequest = op
				timedOut = true
				break
			}
			if batch == nil {
				batch = &transactionWriterBatch{
					id:     fmt.Sprintf("%.4d_%.9d", i, batchCount),
					opened: time.Now(),
				}
				batch.timeoutContext, batch.timeoutCancel = context.WithTimeout(ctx, tw.batchTimeout)
				batchCount++
			}
			batch.ops = append(batch.ops, op)
			l.Debugf("Added write operation %s to batch %s (len=%d)", op.opID, batch.id, len(batch.ops))
		case <-timeoutContext.Done():
			timedOut = true
			select {
			case <-ctx.Done():
				l.Debugf("Transaction writer ending")
				return
			default:
			}
		}

		if batch != nil && (timedOut || (len(batch.ops) >= tw.batchMaxSize)) {
			batch.timeoutCancel()
			l.Debugf("Running batch %s (len=%d,timeout=%t,age=%dms)", batch.id, len(batch.ops), timedOut, time.Since(batch.opened).Milliseconds())
			tw.runBatch(ctx, batch)
			batch = nil
		}

		if shutdownRequest != nil {
			close(shutdownRequest.done)
		}
	}
}

func (tw *transactionWriter) runBatch(ctx context.Context, b *transactionWriterBatch) {
	err := tw.p.db.RunAsGroup(ctx, func(ctx context.Context) error {
		// Build all the batch insert operations
		b.txInsertsByFrom = make(map[string][]*transactionOperation)
		b.confirmationResets = make(map[string]bool)
		b.receiptInserts = make(map[string]*apitypes.ReceiptRecord)
		b.compressionChecks = make(map[string]bool)
		for _, op := range b.ops {
			switch {
			case op.txInsert != nil:
				b.txInsertsByFrom[op.txInsert.From] = append(b.txInsertsByFrom[op.txInsert.From], op)
			case op.txUpdate != nil:
				b.txUpdates = append(b.txUpdates, op)
			case op.txDelete != nil:
				b.txDeletes = append(b.txDeletes, *op.txDelete)
				delete(b.compressionChecks, op.txID)
			case op.receipt != nil:
				// Last one wins in the receipts (can't insert the same TXID twice in one InsertMany)
				b.receiptInserts[op.txID] = op.receipt
			case op.historyRecord != nil:
				b.historyInserts = append(b.historyInserts, op.historyRecord)
				b.compressionChecks[op.txID] = true
			case op.confirmation != nil:
				if op.clearConfirmations {
					// We need to purge any previous confirmation inserts for the same TX,
					// as we will only do one clear operation for this batch (before the insert-many).
					newConfirmationInserts := make([]*apitypes.ConfirmationRecord, 0, len(b.confirmationInserts))
					for _, c := range b.confirmationInserts {
						if c.TransactionID != op.confirmation.TransactionID {
							newConfirmationInserts = append(newConfirmationInserts, c)
						}
					}
					b.confirmationInserts = newConfirmationInserts
					// Add the reset
					b.confirmationResets[op.confirmation.TransactionID] = true
				}
				b.confirmationInserts = append(b.confirmationInserts, op.confirmation)
			}
		}
		return tw.executeBatchOps(ctx, b)
	})
	if err != nil {
		log.L(ctx).Errorf("Transaction persistence batch failed: %s", err)

		// Clear any cached nonces
		tw.clearCachedNonces(ctx, b.txInsertsByFrom)

		// All ops in the batch get a single generic error
		err = i18n.NewError(ctx, tmmsgs.MsgTransactionPersistenceError)
	}
	for _, op := range b.ops {
		if !op.sentConflict {
			op.done <- err
		}
	}

}

// assignNonces 为一批交易操作分配nonce值
// 这是PostgreSQL实现中nonce分配的核心算法
//
// 参数：
//   - ctx: 上下文对象
//   - txInsertsByFrom: 按签名者地址分组的交易操作映射
//     map的key是签名者地址（如"0x1234..."），value是该签名者的所有待插入交易
//
// 算法特点：
// 1. 使用LRU缓存减少区块链查询次数
// 2. 批量分配优化：同一批次中的多个交易只需查询一次
// 3. 三级决策：缓存 vs 数据库 vs 区块链，取最大值
// 4. 支持并发：通过worker路由保证同一签名者的操作串行化
func (tw *transactionWriter) assignNonces(ctx context.Context, txInsertsByFrom map[string][]*transactionOperation) error {
	// 外层循环：遍历每个签名者及其对应的交易列表
	// 例如：{"0xAAA": [tx1, tx2, tx3], "0xBBB": [tx4, tx5]}
	for signer, txs := range txInsertsByFrom {

		// ========== 第一步：检查缓存状态 ==========
		// 尝试从LRU缓存中获取该签名者的nonce缓存条目
		cacheEntry, isCached := tw.nextNonceCache.Get(signer)

		// 标记缓存是否已过期
		cacheExpired := false

		// 如果缓存存在，检查是否过期
		if isCached {
			// 计算缓存已存在的时间
			timeSinceCached := time.Since(*cacheEntry.cachedTime.Time())

			// 如果缓存时间超过nonceStateTimeout配置（默认1小时）
			// 则认为缓存已过期，需要重新查询
			if timeSinceCached > tw.p.nonceStateTimeout {
				// 记录信息日志：缓存已过期
				log.L(ctx).Infof("Nonce cache expired for signer '%s' after %s", signer, timeSinceCached.String())
				cacheExpired = true
			}
		}
		// 缓存检查完成，接下来处理该签名者的每个交易

		// ========== 第二步：遍历该签名者的所有交易 ==========
		// 内层循环：为每个交易分配nonce
		for _, op := range txs {

			// 跳过条件1：已经预分配了nonce的交易
			// 某些场景下，交易可能已经有了外部指定的nonce
			if op.noncePreAssigned {
				continue
			}

			// 跳过条件2：在幂等性检查中发现冲突的交易
			// 如果交易ID已存在（重复提交），不应该分配新nonce
			if op.sentConflict {
				// 记录调试日志：跳过重复交易的nonce分配
				log.L(ctx).Debugf("Skipped nonce assignment to duplicate TX %s", op.txInsert.ID)
				continue
			}

			// ========== 第三步：决定是否需要查询新的nonce ==========
			// 条件：缓存不存在 或 缓存已过期
			if cacheEntry == nil || cacheExpired {

				// --- 3.1 调用回调函数查询区块链 ---
				// 这是最权威的nonce来源：区块链节点的当前状态
				// 回调函数由Transaction Handler层提供，通常会调用NextNonceForSigner API
				nextNonce, err := op.nextNonceCB(ctx, signer)
				if err != nil {
					// 查询失败，返回错误
					// 整个批次会失败，所有涉及的签名者的缓存会被清理
					return err
				}

				// --- 3.2 获取内部记录的nonce值 ---
				// 我们需要比较三个来源的nonce：区块链、缓存、数据库
				var internalNextNonce uint64

				// 情况A：缓存过期但仍存在
				// 保留过期缓存的记录，因为可能有价值
				if cacheEntry != nil {
					// 使用过期缓存中的nonce值
					//
					// 原因：在同一批次中可能有多个交易等待插入数据库
					// 数据库中的nonce记录可能低于缓存值（因为批次还未提交）
					//
					// 例如：
					// - DB最高nonce: 100
					// - 缓存nonce: 103（因为之前的批次分配了101, 102, 103）
					// - 如果批次还未提交，DB查询会返回100，但实际应该用103
					internalNextNonce = cacheEntry.nextNonce

					// 记录追踪日志：使用缓存值与链上查询值比较
					log.L(ctx).Tracef("Using the cached existing nonce %s / %d to compare with the queried next %d for transaction %s",
						signer, internalNextNonce, nextNonce, op.txInsert.ID)
				} else {
					// 情况B：缓存不存在
					// 需要查询数据库获取最高的nonce值

					// 构造查询过滤器：
					// - 限制1条结果（只需要最高的）
					// - 条件：from字段等于当前签名者
					// - 排序：按nonce降序（-nonce）
					filter := persistence.TransactionFilters.NewFilterLimit(ctx, 1).
						Eq("from", signer).
						Sort("-nonce")

					// 执行数据库查询
					existingTXs, _, err := tw.p.transactions.GetMany(ctx, filter)
					if err != nil {
						// 查询失败，记录错误并返回
						log.L(ctx).Errorf("Failed to query highest persisted nonce for '%s': %s", signer, err)
						return err
					}

					// 如果找到了历史交易
					if len(existingTXs) > 0 {
						// 计算下一个nonce：最高nonce + 1
						internalNextNonce = existingTXs[0].Nonce.Uint64() + 1

						// 记录追踪日志：使用DB计算的值与链上查询值比较
						log.L(ctx).Tracef("Using the next nonce calculated from DB %s / %d to compare with the queried next %d for transaction %s",
							signer, internalNextNonce, nextNonce, op.txInsert.ID)
					}
					// 如果没有找到历史交易，internalNextNonce保持为0
					// 这意味着这是该签名者的第一个交易
				}

				// --- 3.3 三方比较：选择最大值 ---
				// 这是关键的安全机制，防止nonce重用
				//
				// 比较规则：max(chainNonce, cachedNonce, dbNonce)
				//
				// 为什么取最大值？
				// - 区块链nonce可能落后（如果最近的交易还在pending）
				// - 缓存nonce可能失效（如果系统重启）
				// - DB nonce最可靠（但需要考虑未提交的批次）
				//
				// 场景示例：
				// 情况1：正常流程
				//   chainNonce=100, internalNextNonce=0 → 使用100（首次交易）
				// 情况2：有pending交易
				//   chainNonce=100, internalNextNonce=105 → 使用105（保护pending交易）
				// 情况3：节点重启后
				//   chainNonce=102, internalNextNonce=105 → 使用105（防止重用103-104）
				if internalNextNonce > nextNonce {
					// 内部记录的nonce更高，使用内部值
					// 记录信息日志：覆盖链上查询的值
					log.L(ctx).Infof("Using next nonce %s / %d instead of queried next %d for transaction %s",
						signer, internalNextNonce, nextNonce, op.txInsert.ID)
					nextNonce = internalNextNonce
				}
				// 如果链上nonce >= 内部nonce，则使用链上值（nextNonce保持不变）

				// --- 3.4 创建/更新缓存条目 ---
				// 现在我们有了正确的nextNonce值，创建新的缓存条目
				// 后续的交易可以直接使用此缓存（递增即可）
				cacheEntry = &nonceCacheEntry{
					cachedTime: fftypes.Now(), // 记录缓存创建时间
					nextNonce:  nextNonce,     // 记录下一个可用nonce
				}
			}
			// 如果缓存有效（!cacheExpired && cacheEntry != nil）
			// 则直接使用缓存中的nextNonce，无需查询

			// ========== 第四步：分配nonce并更新缓存 ==========

			// 记录信息日志：分配nonce
			// 格式：签名者地址 / nonce值 / 交易ID
			log.L(ctx).Infof("Assigned nonce %s / %d to %s", signer, cacheEntry.nextNonce, op.txInsert.ID)

			// 将nonce分配给交易对象
			// 转换：uint64 → int64 → FFBigInt
			//nolint:gosec // 忽略gosec的int转换警告，这里的转换是安全的
			op.txInsert.Nonce = fftypes.NewFFBigInt(int64(cacheEntry.nextNonce))

			// 为下一个交易准备：nonce递增
			// 这是批量优化的关键：
			// - 第一个交易：查询一次，使用nonce=100
			// - 第二个交易：使用缓存，nonce=101
			// - 第三个交易：使用缓存，nonce=102
			// - ...依此类推
			cacheEntry.nextNonce++

			// 更新LRU缓存
			// 如果缓存已满，最少使用的条目会被自动淘汰
			tw.nextNonceCache.Add(signer, cacheEntry)
		}
		// 该签名者的所有交易已分配完nonce
	}
	// 所有签名者的所有交易已分配完nonce

	// 返回成功
	return nil
}

// clearCachedNonces 清理失败批次中涉及的所有签名者的nonce缓存
//
// 调用场景：
// - 当一个批次的数据库插入操作失败时调用
// - 确保下次请求时会重新查询区块链和数据库
//
// 参数：
// - ctx: 上下文对象
// - txInsertsByFrom: 失败批次中按签名者分组的交易操作
//
// 为什么要清理缓存？
// 1. 批次失败意味着nonce未实际使用（交易未插入数据库）
// 2. 缓存中的nonce值已递增，但实际数据库状态未变化
// 3. 如果不清理，下次会使用错误的（过高的）nonce值
// 4. 这可能导致nonce gap（跳号）
//
// 示例场景：
//
//	批次操作：
//	- 分配nonce 100, 101, 102 给三个交易
//	- 缓存更新为 nextNonce = 103
//	- 数据库插入失败（如违反唯一约束）
//	清理后：
//	- 缓存被清空
//	- 下次请求会重新查询，再次分配nonce 100, 101, 102
func (tw *transactionWriter) clearCachedNonces(ctx context.Context, txInsertsByFrom map[string][]*transactionOperation) {
	// 遍历批次中涉及的所有签名者
	for signer := range txInsertsByFrom {
		// 记录警告日志：清理缓存
		// 这是一个警告级别的日志，因为：
		// 1. 表示操作失败，需要重试
		// 2. 会导致下次查询区块链，影响性能
		log.L(ctx).Warnf("Clearing cache for '%s' after insert failure", signer)

		// 从LRU缓存中移除该签名者的条目
		// Remove方法返回被移除的值，但我们不需要
		// 使用 _ 忽略返回值
		_ = tw.nextNonceCache.Remove(signer)
	}
	// 清理完成后，这些签名者的下次nonce分配会：
	// 1. 缓存未命中（cacheEntry == nil）
	// 2. 触发区块链查询
	// 3. 查询数据库获取最高nonce
	// 4. 重新建立正确的缓存
}

func (tw *transactionWriter) preInsertIdempotencyCheck(ctx context.Context, b *transactionWriterBatch) (validInserts []*apitypes.ManagedTX, err error) {
	// We want to return 409s (not 500s) for idempotency checks, and only fail the individual TX.
	// There should have been a pre-check when the transaction came in on the API, so we're in
	// a small window here where we had multiple API calls running concurrently.
	// So we choose to optimize the check using the txMetaCache we add new inserts to - meaning
	// a very edge case of a 500 in cache expiry, if we somehow expired it from that cache in this
	// small window.
	for _, txOps := range b.txInsertsByFrom {
		for _, txOp := range txOps {
			var existing *apitypes.ManagedTX
			_, inCache := tw.txMetaCache.Get(txOp.txID)
			if inCache {
				existing, err = tw.p.GetTransactionByID(ctx, txOp.txID)
				if err != nil {
					log.L(ctx).Errorf("Pre-insert idempotency check failed for transaction %s: %s", txOp.txID, err)
					return nil, err
				}
			}
			if existing != nil {
				// Send a conflict, and do not add it to the list
				txOp.sentConflict = true
				txOp.done <- i18n.NewError(ctx, tmmsgs.MsgDuplicateID, txOp.txID)
			} else {
				log.L(ctx).Debugf("Adding TX %s from write operation %s to insert idx=%d", txOp.txID, txOp.opID, len(validInserts))
				validInserts = append(validInserts, txOp.txInsert)
			}
		}
	}

	return validInserts, nil
}

func (tw *transactionWriter) mergeTxUpdates(ctx context.Context, b *transactionWriterBatch) (txCompletions []*apitypes.TXCompletion, mergedUpdates map[string]*apitypes.TXUpdates) {
	// We build a list of transaction completions to write, in a lock at the very end of the transaction
	txCompletions = make([]*apitypes.TXCompletion, 0, len(b.txUpdates))
	// Do all the transaction updates
	mergedUpdates = make(map[string]*apitypes.TXUpdates)
	for _, op := range b.txUpdates {
		update, merge := mergedUpdates[op.txID]
		if merge {
			update.Merge(op.txUpdate)
		} else {
			mergedUpdates[op.txID] = op.txUpdate
		}
		if op.txUpdate.Status != nil && (*op.txUpdate.Status == apitypes.TxStatusSucceeded || *op.txUpdate.Status == apitypes.TxStatusFailed) {
			txCompletions = append(txCompletions, &apitypes.TXCompletion{
				ID:     op.txID,
				Time:   fftypes.Now(),
				Status: *op.txUpdate.Status,
			})
		}
		log.L(ctx).Debugf("Updating transaction %s in write operation %s (merged=%t)", op.txID, op.opID, merge)
	}
	return
}

func (tw *transactionWriter) executeBatchOps(ctx context.Context, b *transactionWriterBatch) error {
	txInserts, err := tw.preInsertIdempotencyCheck(ctx, b)
	if err != nil {
		return err
	}

	// Insert all the transactions
	if len(txInserts) > 0 {
		if err := tw.assignNonces(ctx, b.txInsertsByFrom); err != nil {
			log.L(ctx).Errorf("InsertMany transactions (%d) nonce assignment failed: %s", len(b.historyInserts), err)
			return err
		}
		if err := tw.p.transactions.InsertMany(ctx, txInserts, false); err != nil {
			log.L(ctx).Errorf("InsertMany transactions (%d) failed: %s", len(b.historyInserts), err)
			return err
		}
		// Add to our metadata cache, in the fresh new state
		for _, t := range txInserts {
			_ = tw.txMetaCache.Add(t.ID, &txCacheEntry{lastCompacted: fftypes.Now()})
		}
	}
	// We build a list of transaction completions to write, in a lock at the very end of the transaction
	txCompletions, mergedUpdates := tw.mergeTxUpdates(ctx, b)
	for txID, update := range mergedUpdates {
		if err := tw.p.updateTransaction(ctx, txID, update); err != nil {
			log.L(ctx).Errorf("Update transaction %s failed: %s", txID, err)
			return err
		}
	}
	// Then the receipts - which need to be an upsert
	receipts := make([]*apitypes.ReceiptRecord, 0, len(b.receiptInserts))
	for _, r := range b.receiptInserts {
		receipts = append(receipts, r)
	}
	if len(receipts) > 0 {
		// Try optimized insert first, allowing partial success so we can fall back
		err := tw.p.receipts.InsertMany(ctx, receipts, true /* fallback */)
		if err != nil {
			log.L(ctx).Debugf("Batch receipt insert optimization failed: %s", err)
			for _, receipt := range b.receiptInserts {
				// FAll back to individual upserts
				if _, err := tw.p.receipts.Upsert(ctx, receipt, dbsql.UpsertOptimizationExisting); err != nil {
					log.L(ctx).Errorf("Upsert receipt %s failed: %s", receipt.TransactionID, err)
					return err
				}
			}
		}
	}
	// Then do any confirmation clears
	for txID := range b.confirmationResets {
		if err := tw.p.confirmations.DeleteMany(ctx, persistence.ConfirmationFilters.NewFilter(ctx).Eq("transaction", txID)); err != nil {
			log.L(ctx).Errorf("DeleteMany confirmation records for transaction %s failed: %s", txID, err)
			return err
		}
	}
	// Then insert the new confirmation records
	if len(b.confirmationInserts) > 0 {
		if err := tw.p.confirmations.InsertMany(ctx, b.confirmationInserts, false); err != nil {
			log.L(ctx).Errorf("InsertMany confirmation records (%d) failed: %s", len(b.confirmationInserts), err)
			return err
		}
	}
	// Then the history entries
	if len(b.historyInserts) > 0 {
		if err := tw.p.txHistory.InsertMany(ctx, b.historyInserts, false); err != nil {
			log.L(ctx).Errorf("InsertMany history records (%d) failed: %s", len(b.historyInserts), err)
			return err
		}
	}
	// Do the compression checks
	if tw.compressionInterval > 0 {
		for txID := range b.compressionChecks {
			if err := tw.compressionCheck(ctx, txID); err != nil {
				log.L(ctx).Errorf("Compression check for %s failed: %s", txID, err)
				return err
			}
		}
	}
	// Lock the completions table and then insert all the completions (with conflict safety)
	// We do this last as we want to hold the sequencing lock on this table for the shorted amount of time.
	if err := tw.p.writeTransactionCompletions(ctx, txCompletions); err != nil {
		log.L(ctx).Errorf("Inserting %d transaction completions failed: %s", len(txCompletions), err)
		return err
	}
	// Do all the transaction deletes
	for _, txID := range b.txDeletes {
		// Delete any receipt
		if err := tw.p.receipts.Delete(ctx, txID); err != nil && err != fftypes.DeleteRecordNotFound {
			log.L(ctx).Errorf("Delete receipt for transaction %s failed: %s", txID, err)
			return err
		}
		// Clear confirmations
		if err := tw.p.confirmations.DeleteMany(ctx, persistence.ConfirmationFilters.NewFilter(ctx).Eq("transaction", txID)); err != nil {
			log.L(ctx).Errorf("DeleteMany confirmation records for transaction %s failed: %s", txID, err)
			return err
		}
		// Clear history
		if err := tw.p.txHistory.DeleteMany(ctx, persistence.TXHistoryFilters.NewFilter(ctx).Eq("transaction", txID)); err != nil {
			log.L(ctx).Errorf("DeleteMany history records for transaction %s failed: %s", txID, err)
			return err
		}
		// Delete the transaction
		if err := tw.p.transactions.Delete(ctx, txID); err != nil {
			log.L(ctx).Errorf("Delete transaction %s failed: %s", txID, err)
			return err
		}
	}
	return nil
}

func (tw *transactionWriter) compressionCheck(ctx context.Context, txID string) error {
	txMeta, ok := tw.txMetaCache.Get(txID)
	if ok {
		sinceCompaction := time.Since(*txMeta.lastCompacted.Time())
		if sinceCompaction < tw.compressionInterval {
			// Nothing to do
			return nil
		}
		log.L(ctx).Debugf("Compressing history for TX '%s' after %s", txID, sinceCompaction.String())
	} else {
		txMeta = &txCacheEntry{}
		log.L(ctx).Debugf("Compressing history for TX '%s' after cache miss", txID)
	}
	if err := tw.p.compressHistory(ctx, txID); err != nil {
		return err
	}
	txMeta.lastCompacted = fftypes.Now()
	_ = tw.txMetaCache.Add(txID, txMeta)
	return nil
}

func (tw *transactionWriter) stop() {
	for i, workerDone := range tw.workersDone {
		select {
		case <-workerDone:
		case <-tw.bgCtx.Done():
		default:
			// Quiesce the worker
			shutdownOp := &transactionOperation{
				isShutdown: true,
				done:       make(chan error),
			}
			tw.workQueues[i] <- shutdownOp
			<-shutdownOp.done
		}
		<-workerDone
	}
	tw.cancelCtx()
}
