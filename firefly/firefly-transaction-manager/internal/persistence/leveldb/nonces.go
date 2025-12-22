// Copyright © 2023 Kaleido, Inc.
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

package leveldb

import (
	"context"
	"time"

	"github.com/hyperledger/firefly-common/pkg/log"
	"github.com/hyperledger/firefly-transaction-manager/pkg/apitypes"
	"github.com/hyperledger/firefly-transaction-manager/pkg/txhandler"
)

type lockedNonce struct {
	th       *leveldbPersistence
	nsOpID   string
	signer   string
	unlocked chan struct{}
	nonce    uint64
	spent    bool
}

// complete 释放nonce锁，允许其他goroutine为该签名者分配nonce
//
// 重要：对于任何从assignAndLockNonce成功返回的lockedNonce，都必须调用此方法
// 否则会导致死锁：该签名者的后续nonce分配请求会永久阻塞
//
// 使用模式：
//
//	locked, err := p.assignAndLockNonce(ctx, opID, signer, callback)
//	if err != nil {
//	    return err
//	}
//	defer locked.complete(ctx)  // 确保无论如何都会释放锁
//	// ... 使用locked.nonce ...
//
// 参数：
// - ctx: 上下文对象，用于日志记录
func (ln *lockedNonce) complete(ctx context.Context) {
	// ========== 第一步：记录日志 ==========

	// 检查nonce是否被实际使用
	// spent标志由调用者在使用nonce后设置
	if ln.spent {
		// nonce已被使用（分配给了交易）
		// 记录调试日志：nonce已消费
		// 格式：nonce值 / 签名者地址
		log.L(ctx).Debugf("Next nonce %d for signer %s spent", ln.nonce, ln.signer)
	} else {
		// nonce未被使用（可能由于错误）
		// 记录调试日志：nonce未消费
		//
		// 这种情况可能发生在：
		// 1. nonce分配后但交易插入失败
		// 2. 其他错误导致交易未创建
		//
		// 未消费的nonce会在下次查询时重新分配
		log.L(ctx).Debugf("Returning next nonce %d for signer %s unspent", ln.nonce, ln.signer)
	}

	// ========== 第二步：释放锁 ==========

	// 获取全局nonce互斥锁
	// 需要保护lockedNonces映射的并发访问
	ln.th.nonceMux.Lock()

	// 从映射中删除该签名者的锁条目
	// 删除后，其他goroutine就可以为该签名者分配新的nonce了
	//
	// 关键操作：
	// - 这使得assignAndLockNonce中的isLocked检查会返回false
	// - 新的goroutine可以创建新的锁对象并获取nonce
	delete(ln.th.lockedNonces, ln.signer)

	// 关闭unlocked通道
	// 这会唤醒所有阻塞在此通道上的goroutine
	//
	// 机制说明：
	// 1. 在assignAndLockNonce中，如果发现签名者已被锁定
	// 2. 会执行 <-locked.unlocked 阻塞等待
	// 3. 当我们关闭这个通道时，所有等待的goroutine都会被唤醒
	// 4. 它们会重新循环，尝试获取锁
	//
	// Go语言特性：
	// - 关闭的通道可以被无限次读取，每次返回零值
	// - 多个goroutine可以同时从关闭的通道读取
	// - 因此所有等待者都能被唤醒
	close(ln.unlocked)

	// 释放全局nonce互斥锁
	ln.th.nonceMux.Unlock()

	// ========== 释放完成 ==========
	// 此时：
	// 1. 该签名者的锁已从映射中移除
	// 2. 所有等待的goroutine已被唤醒
	// 3. 新的nonce分配请求可以正常处理
}

// assignAndLockNonce 为指定签名者分配并锁定一个nonce值
// 这是LevelDB实现中nonce分配的核心方法
//
// 参数：
// - ctx: 上下文对象
// - nsOpID: 命名空间操作ID，用于追踪和调试
// - signer: 签名者地址（如"0x1234..."）
// - nextNonceCB: 查询区块链下一个nonce的回调函数
//
// 返回：
// - *lockedNonce: 锁定的nonce对象，使用完后必须调用complete()释放
// - error: 分配失败时的错误
//
// 并发控制策略：
// - 使用互斥锁（nonceMux）保护lockedNonces映射的访问
// - 使用通道（unlocked）实现等待/唤醒机制
// - 同一签名者的并发请求会串行化处理
func (p *leveldbPersistence) assignAndLockNonce(ctx context.Context, nsOpID, signer string, nextNonceCB txhandler.NextNonceCallback) (*lockedNonce, error) {

	// 无限循环，用于处理并发竞争
	// 正常情况下，循环会在获取锁后立即返回
	// 如果有并发竞争，会在等待后重新循环
	for {
		// ========== 第一步：获取全局互斥锁，检查签名者是否已被锁定 ==========

		// 锁定全局nonce互斥锁
		// 这个锁保护lockedNonces映射，确保对映射的访问是线程安全的
		p.nonceMux.Lock()

		// 标记是否需要查询nonce
		// 只有在成功获取签名者锁时才需要查询
		doLookup := false

		// 尝试从映射中获取该签名者的锁对象
		// lockedNonces是一个map[string]*lockedNonce
		// key是签名者地址，value是锁定的nonce对象
		locked, isLocked := p.lockedNonces[signer]

		// 检查该签名者是否已被其他goroutine锁定
		if !isLocked {
			// ========== 情况A：签名者未被锁定（首次请求或之前的锁已释放） ==========

			// 创建新的锁定nonce对象
			locked = &lockedNonce{
				th:       p,                   // 持久化层引用
				nsOpID:   nsOpID,              // 操作ID（用于日志追踪）
				signer:   signer,              // 签名者地址
				unlocked: make(chan struct{}), // 创建未缓冲通道，用于通知等待者
			}

			// 将新创建的锁对象放入映射
			// 这样其他goroutine就知道这个签名者正在被处理
			p.lockedNonces[signer] = locked

			// 标记需要查询nonce
			// 因为我们成功获取了这个签名者的锁
			doLookup = true
		}
		// 如果isLocked为true，说明其他goroutine正在处理这个签名者
		// locked变量会指向那个goroutine创建的锁对象

		// 释放全局互斥锁
		// 锁的作用域尽可能小，避免长时间持有
		p.nonceMux.Unlock()

		// ========== 第二步：根据锁的状态决定下一步操作 ==========

		// 情况B：签名者已被其他goroutine锁定
		if isLocked {
			// 记录调试日志：发生了nonce分配竞争
			// 这是正常的并发场景，不是错误
			log.L(ctx).Debugf("Contention for next nonce for signer %s", signer)

			// 等待其他goroutine完成并释放锁
			// 阻塞在unlocked通道上
			//
			// 当持有锁的goroutine调用complete()时：
			// 1. complete()会关闭unlocked通道
			// 2. 所有阻塞在此通道上的goroutine会被唤醒
			// 3. 唤醒后，重新进入循环顶部，再次尝试获取锁
			<-locked.unlocked

			// 循环继续，回到开头再次检查锁状态
			// 这次可能就能成功获取锁了

		} else if doLookup {
			// 情况C：成功获取签名者锁，可以查询nonce

			// ========== 第三步：计算下一个nonce值 ==========

			// 我们必须确保以下两种情况之一：
			// 1. 成功返回一个nonce
			// 2. 发生错误时，释放锁（调用complete）
			//
			// 这个约定非常重要，否则会造成死锁：
			// - 如果不释放锁就返回错误，锁会永久占用
			// - 其他goroutine会永远等待这个签名者的锁
			nextNonce, err := p.calcNextNonce(ctx, signer, nextNonceCB)
			if err != nil {
				// 计算nonce失败

				// 必须释放锁！
				// complete()会：
				// 1. 从lockedNonces映射中删除此签名者
				// 2. 关闭unlocked通道，唤醒等待者
				locked.complete(ctx)

				// 返回错误
				return nil, err
			}

			// 计算成功，将nonce值保存到锁对象中
			locked.nonce = nextNonce

			// 返回锁定的nonce对象
			// 调用者负责：
			// 1. 使用这个nonce值
			// 2. 使用完后调用locked.complete(ctx)释放锁
			return locked, nil
		}
		// 如果既不是isLocked也不是doLookup，说明出现了异常情况
		// 理论上不应该到达这里，但循环会继续
	}
	// 无限循环，不会到达这里

}

// calcNextNonce 计算指定签名者的下一个可用nonce值
// 这是LevelDB实现中nonce计算的核心算法
//
// 参数：
// - ctx: 上下文对象
// - signer: 签名者地址（如"0x1234..."）
// - nextNonceCB: 查询区块链下一个nonce的回调函数
//
// 返回：
// - uint64: 下一个可用的nonce值
// - error: 计算失败时的错误
//
// 算法说明：
// 1. 首先检查本地数据库中的最后一个交易
// 2. 如果本地记录"新鲜"（在nonceStateTimeout内），直接使用本地nonce+1
// 3. 如果本地记录"过期"或不存在，查询区块链节点
// 4. 比较本地记录和链上记录，使用较大值
//
// 并发安全：
// 此函数在assignAndLockNonce的nonce锁内调用
// 因此对于同一签名者，同一时刻只有一个goroutine在执行此函数
func (p *leveldbPersistence) calcNextNonce(ctx context.Context, signer string, nextNonceCB txhandler.NextNonceCallback) (uint64, error) {

	// ========== 第一步：查询本地数据库中的最后一个交易 ==========

	// 首先检查我们的数据库，找到该地址使用的最后一个nonce
	//
	// 重要说明：
	// 我们在assignAndLockNonce的nonce锁内执行此函数
	// 因此可以确保我们是唯一正在为该签名者处理nonce的goroutine
	// 不会有并发问题

	// 声明变量存储找到的最后一个交易
	var lastTxn *apitypes.ManagedTX

	// 查询该签名者的交易列表
	// 参数说明：
	// - signer: 签名者地址
	// - nil: 不指定起始nonce（查询所有）
	// - 1: 只返回1条记录（我们只需要最高的）
	// - 1: 排序方向（1=降序，从高到低）
	//
	// ListTransactionsByNonce内部会按nonce降序排序
	// 所以返回的第一条就是nonce最大的交易
	txns, err := p.ListTransactionsByNonce(ctx, signer, nil, 1, 1)
	if err != nil {
		// 数据库查询失败，返回错误
		return 0, err
	}

	// 检查是否找到了历史交易
	if len(txns) > 0 {
		// 找到了至少一个历史交易
		lastTxn = txns[0] // 取第一条（nonce最大的）

		// ========== 第二步：检查本地记录是否"新鲜" ==========

		// 计算最后一个交易的创建时间距今多久
		// 与nonceStateTimeout配置比较（默认1小时）
		//
		// 为什么要检查时间？
		// - 如果交易很新（< 1小时），说明我们的本地状态是最新的
		// - 可以直接使用本地nonce + 1，无需查询区块链
		// - 这大大减少了区块链查询次数，提高性能
		if time.Since(*lastTxn.Created.Time()) < p.nonceStateTimeout {
			// 本地记录很新鲜，直接使用

			// 计算下一个nonce：最后使用的nonce + 1
			nextNonce := lastTxn.Nonce.Uint64() + 1

			// 记录调试日志：使用本地计算的nonce
			// 格式：签名者 / nonce值 / 基于的交易ID / 交易状态
			// 例如："Allocating next nonce '0x1234...' / '101' after TX 'tx-123' (status=Pending)"
			log.L(ctx).Debugf("Allocating next nonce '%s' / '%d' after TX '%s' (status=%s)",
				signer, nextNonce, lastTxn.ID, lastTxn.Status)

			// 返回计算的nonce
			// 不需要查询区块链，性能最优
			return nextNonce, nil
		}
		// 如果到达这里，说明本地记录已过期（> nonceStateTimeout）
		// 需要查询区块链获取最新状态
	}
	// 如果到达这里，说明：
	// 1. 没有找到历史交易（这是该签名者的第一个交易），或
	// 2. 找到了历史交易但已过期
	// 两种情况都需要查询区块链

	// ========== 第三步：查询区块链节点 ==========

	// 我们的本地状态没有新鲜的答案，需要询问区块链节点
	//
	// 调用回调函数查询区块链
	// 这通常会调用区块链节点的RPC接口
	// 例如以太坊的 eth_getTransactionCount
	nextNonce, err := nextNonceCB(ctx, signer)
	if err != nil {
		// 查询失败，返回错误
		return 0, err
	}

	// ========== 第四步：比较本地和链上的nonce，使用较大值 ==========

	// 如果我们有一个过期的本地答案，需要确保不会重用已使用的nonce
	//
	// 这很重要，因为可能存在以下情况：
	// 1. 我们有一些交易已从节点的交易池中过期
	// 2. 但这些交易仍在我们的本地状态中
	// 3. 节点返回的nonce可能低于我们本地的最高nonce
	//
	// 解决方案：取两者中较大的值
	// max(本地最高nonce + 1, 链上nonce)
	//
	// 场景示例：
	// 场景1：正常情况
	//   lastTxn.Nonce = 100, 链上nextNonce = 101
	//   条件不满足（101 > 100），使用链上的101 ✓
	//
	// 场景2：有pending交易
	//   lastTxn.Nonce = 105, 链上nextNonce = 101
	//   条件满足（101 <= 105），使用本地的106 ✓
	//   这保护了nonce 101-105的pending交易
	//
	// 场景3：交易池已清理
	//   lastTxn.Nonce = 105, 链上nextNonce = 101
	//   条件满足，使用106，可能造成nonce gap
	//   但这比重用101-105更安全
	if lastTxn != nil && nextNonce <= lastTxn.Nonce.Uint64() {
		// 链上nonce不比本地高，使用本地nonce + 1

		// 记录调试日志：链上nonce被本地覆盖
		// 格式：签名者 / 链上nonce / 本地nonce / 交易ID / 交易状态
		log.L(ctx).Debugf("Node TX pool next nonce '%s' / '%d' is not ahead of '%d' in TX '%s' (status=%s)",
			signer, nextNonce, lastTxn.Nonce.Uint64(), lastTxn.ID, lastTxn.Status)

		// 使用本地nonce + 1
		nextNonce = lastTxn.Nonce.Uint64() + 1
	}
	// 如果链上nonce > 本地nonce，则使用链上值（nextNonce保持不变）

	// 返回最终确定的nonce值
	// 这个值是max(链上nonce, 本地nonce + 1)
	return nextNonce, nil

}
