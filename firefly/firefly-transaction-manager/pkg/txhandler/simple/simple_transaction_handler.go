// Copyright © 2024 - 2025 Kaleido, Inc.
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

package simple

import (
	"bytes"
	"context"
	"encoding/json"
	"html/template"
	"sync"
	"time"

	sprig "github.com/Masterminds/sprig/v3"
	resty "github.com/go-resty/resty/v2"
	"github.com/hyperledger/firefly-common/pkg/config"
	"github.com/hyperledger/firefly-common/pkg/ffresty"
	"github.com/hyperledger/firefly-common/pkg/fftypes"
	"github.com/hyperledger/firefly-common/pkg/i18n"
	"github.com/hyperledger/firefly-common/pkg/log"
	"github.com/hyperledger/firefly-common/pkg/retry"
	"github.com/hyperledger/firefly-transaction-manager/internal/tmconfig" // shouldn't need this if you are developing a customized transaction handler
	"github.com/hyperledger/firefly-transaction-manager/internal/tmmsgs"   // replace with your own messages if you are developing a customized transaction handler
	"github.com/hyperledger/firefly-transaction-manager/pkg/apitypes"
	"github.com/hyperledger/firefly-transaction-manager/pkg/ffcapi"
	"github.com/hyperledger/firefly-transaction-manager/pkg/txhandler"
)

const metricsCounterTransactionProcessOperationsTotal = "tx_process_operation_total"
const metricsCounterTransactionProcessOperationsTotalDescription = "Number of transaction process operations occurred grouped by operation name"

const metricsLabelNameOperation = "operation"

const metricsHistogramTransactionProcessOperationsDuration = "tx_process_duration_seconds"
const metricsHistogramTransactionProcessOperationsDurationDescription = "Duration of transaction process grouped by operation name"

// UpdateType informs FFTM whether the transaction needs an update to be persisted after this execution of the policy engine
type UpdateType int

const (
	None   UpdateType = iota // Instructs that no update is necessary
	Update                   // Instructs that the transaction should be updated in persistence
	Delete                   // Instructs that the transaction should be removed completely from persistence - generally only returned when TX status is TxStatusDeleteRequested
)

// RunContext is the context for an individual run of the simple policy loop, for an individual transaction.
// - Built from a snapshot of the mux-protected inflight state on input
// - Capturing the results from the run on output
type RunContext struct {
	// Input
	context.Context
	TX            *apitypes.ManagedTX
	Receipt       *ffcapi.TransactionReceiptResponse
	Confirmations *apitypes.ConfirmationsNotification
	Confirmed     bool
	SyncAction    policyEngineAPIRequestType
	ProcessTx     bool
	// Input/output
	SubStatus apitypes.TxSubStatus
	Info      *simplePolicyInfo // must be updated in-place and set UpdatedInfo to true as well as UpdateType = Update
	// Output
	UpdateType     UpdateType
	UpdatedInfo    bool
	TXUpdates      apitypes.TXUpdates
	HistoryUpdates []func(p txhandler.TransactionHistoryPersistence) error
}

func (ctx *RunContext) SetSubStatus(subStatus apitypes.TxSubStatus) {
	ctx.SubStatus = subStatus
}

func (ctx *RunContext) AddSubStatusAction(action apitypes.TxAction, info *fftypes.JSONAny, err *fftypes.JSONAny, actionOccurred *fftypes.FFTime) {
	subStatus := ctx.SubStatus // capture at time of action
	ctx.HistoryUpdates = append(ctx.HistoryUpdates, func(p txhandler.TransactionHistoryPersistence) error {
		return p.AddSubStatusAction(ctx, ctx.TX.ID, subStatus, action, info, err, actionOccurred)
	})
}

type TransactionHandlerFactory struct{}

func (f *TransactionHandlerFactory) Name() string {
	return "simple"
}

// simpleTransactionHandler is a base transaction handler forming an example for extension:
// - It offers three ways of calculating gas price: use a fixed number, use the built-in API of a ethereum connector, use a RESTful gas oracle
// - It resubmits the transaction based on a configured interval until it succeed or fail
func (f *TransactionHandlerFactory) NewTransactionHandler(ctx context.Context, conf config.Section) (txhandler.TransactionHandler, error) {
	gasOracleConfig := conf.SubSection(GasOracleConfig)
	sth := &simpleTransactionHandler{
		resubmitInterval: conf.GetDuration(ResubmitInterval),
		fixedGasPrice:    fftypes.JSONAnyPtr(conf.GetString(FixedGasPrice)),

		gasOracleMethod:        gasOracleConfig.GetString(GasOracleMethod),
		gasOracleQueryInterval: gasOracleConfig.GetDuration(GasOracleQueryInterval),
		gasOracleMode:          gasOracleConfig.GetString(GasOracleMode),

		inflightStale:  make(chan bool, 1),
		inflightUpdate: make(chan bool, 1),
	}

	// check whether we are using deprecated configuration
	if config.GetString(tmconfig.TransactionsHandlerName) == "" {
		log.L(ctx).Warnf("Initializing transaction handler with deprecated configurations. Please use 'transactions.handler' instead")
		sth.maxInFlight = config.GetInt(tmconfig.DeprecatedTransactionsMaxInFlight)
		sth.policyLoopInterval = config.GetDuration(tmconfig.DeprecatedPolicyLoopInterval)
		sth.retry = &retry.Retry{
			InitialDelay: config.GetDuration(tmconfig.DeprecatedPolicyLoopRetryInitDelay),
			MaximumDelay: config.GetDuration(tmconfig.DeprecatedPolicyLoopRetryMaxDelay),
			Factor:       config.GetFloat64(tmconfig.DeprecatedPolicyLoopRetryFactor),
		}
	} else {
		// if not, use the new transaction handler configurations
		sth.maxInFlight = conf.GetInt(MaxInFlight)
		sth.policyLoopInterval = conf.GetDuration(Interval)
		sth.retry = &retry.Retry{
			InitialDelay: conf.GetDuration(RetryInitDelay),
			MaximumDelay: conf.GetDuration(RetryMaxDelay),
			Factor:       conf.GetFloat64(RetryFactor),
		}
	}

	switch sth.gasOracleMode {
	case GasOracleModeConnector:
		// No initialization required
	case GasOracleModeRESTAPI:
		goc, err := ffresty.New(ctx, gasOracleConfig)
		if err != nil {
			return nil, err
		}
		sth.gasOracleClient = goc
		templateString := gasOracleConfig.GetString(GasOracleTemplate)
		if templateString == "" {
			return nil, i18n.NewError(ctx, tmmsgs.MsgMissingGOTemplate)
		}
		template, err := template.New("").Funcs(sprig.FuncMap()).Parse(templateString)
		if err != nil {
			return nil, i18n.NewError(ctx, tmmsgs.MsgBadGOTemplate, err)
		}
		sth.gasOracleTemplate = template
	default:
		if sth.fixedGasPrice.IsNil() {
			return nil, i18n.NewError(ctx, tmmsgs.MsgNoGasConfigSetForTransactionHandler)
		}
	}
	return sth, nil
}

type simpleTransactionHandler struct {
	ctx              context.Context
	toolkit          *txhandler.Toolkit
	fixedGasPrice    *fftypes.JSONAny
	resubmitInterval time.Duration

	gasOracleMode          string
	gasOracleClient        *resty.Client
	gasOracleMethod        string
	gasOracleTemplate      *template.Template
	gasOracleQueryInterval time.Duration
	gasOracleQueryValue    *fftypes.JSONAny
	gasOracleLastQueryTime *fftypes.FFTime

	policyLoopInterval      time.Duration
	policyLoopDone          chan struct{}
	inflightStale           chan bool
	inflightUpdate          chan bool
	mux                     sync.RWMutex
	inflightRWMux           sync.RWMutex
	inflight                []*pendingState
	policyEngineAPIRequests []*policyEngineAPIRequest
	maxInFlight             int
	retry                   *retry.Retry
}

type pendingState struct {
	mtx                     *apitypes.ManagedTX
	trackingTransactionHash string
	lastPolicyCycle         time.Time
	receipt                 *ffcapi.TransactionReceiptResponse
	info                    *simplePolicyInfo
	confirmed               bool
	confirmations           *apitypes.ConfirmationsNotification
	receiptNotify           *fftypes.FFTime
	confirmNotify           *fftypes.FFTime
	remove                  bool
	subStatus               apitypes.TxSubStatus
	// This mutex only works in a slice when the slice contains a pointer to this struct
	// appends to a slice copy memory but when storing pointers it does not
	mux sync.Mutex
}

type simplePolicyInfo struct {
	LastWarnTime *fftypes.FFTime `json:"lastWarnTime"`
}

func (sth *simpleTransactionHandler) Init(ctx context.Context, toolkit *txhandler.Toolkit) {
	sth.toolkit = toolkit

	// init metrics
	sth.initSimpleHandlerMetrics(ctx)
}

func (sth *simpleTransactionHandler) Start(ctx context.Context) (done <-chan struct{}, err error) {
	if sth.ctx == nil { // only start once
		sth.ctx = ctx // set the context for policy loop
		sth.policyLoopDone = make(chan struct{})
		sth.markInflightStale()
		go sth.policyLoop()
	}
	return sth.policyLoopDone, nil
}

func (sth *simpleTransactionHandler) requestIDPreCheck(ctx context.Context, reqHeaders *apitypes.RequestHeaders) (string, error) {
	// The request ID is the primary ID, and should be supplied by the user for idempotence.
	txID := reqHeaders.ID
	if txID == "" {
		// However, we will generate one for them if not supplied
		txID = fftypes.NewUUID().String()
		return txID, nil
	}

	// If it's supplied, we take the cost of a pre-check against the database for idempotency
	// before we do any processing.
	// The DB layer will protect us, but between now and then we might query the blockchain
	// to estimate gas and return unexpected 500 failures (rather than 409s)
	existing, err := sth.toolkit.TXPersistence.GetTransactionByID(ctx, txID)
	if err != nil {
		return "", err
	}
	if existing != nil {
		return "", i18n.NewError(ctx, tmmsgs.MsgDuplicateID, txID)
	}
	return txID, nil
}

func (sth *simpleTransactionHandler) HandleNewTransaction(ctx context.Context, txReq *apitypes.TransactionRequest) (mtx *apitypes.ManagedTX, submissionRejected bool, err error) {
	txID, err := sth.requestIDPreCheck(ctx, &txReq.Headers)
	if err != nil {
		return nil, false, err
	}

	// Prepare the transaction, which will mean we have a transaction that should be submittable.
	// If we fail at this stage, we don't need to write any state as we are sure we haven't submitted
	// anything to the blockchain itself.
	prepared, reason, err := sth.toolkit.Connector.TransactionPrepare(ctx, &ffcapi.TransactionPrepareRequest{
		TransactionInput: txReq.TransactionInput,
	})
	if err != nil {
		return nil, ffcapi.MapSubmissionRejected(reason), err
	}

	mtx, err = sth.createManagedTx(ctx, txID, &txReq.TransactionHeaders, prepared.Gas, prepared.TransactionData)
	return mtx, false, err
}

func (sth *simpleTransactionHandler) HandleNewContractDeployment(ctx context.Context, txReq *apitypes.ContractDeployRequest) (mtx *apitypes.ManagedTX, submissionRejected bool, err error) {
	txID, err := sth.requestIDPreCheck(ctx, &txReq.Headers)
	if err != nil {
		return nil, false, err
	}

	// Prepare the transaction, which will mean we have a transaction that should be submittable.
	// If we fail at this stage, we don't need to write any state as we are sure we haven't submitted
	// anything to the blockchain itself.
	prepared, reason, err := sth.toolkit.Connector.DeployContractPrepare(ctx, &txReq.ContractDeployPrepareRequest)
	if err != nil {
		return nil, ffcapi.MapSubmissionRejected(reason), err
	}

	mtx, err = sth.createManagedTx(ctx, txID, &txReq.TransactionHeaders, prepared.Gas, prepared.TransactionData)
	return mtx, false, err
}

func (sth *simpleTransactionHandler) HandleCancelTransaction(ctx context.Context, txID string) (mtx *apitypes.ManagedTX, err error) {
	res := sth.policyEngineAPIRequest(ctx, &policyEngineAPIRequest{
		requestType: ActionDelete,
		txID:        txID,
	})
	return res.tx, res.err
}

func (sth *simpleTransactionHandler) HandleSuspendTransaction(ctx context.Context, txID string) (mtx *apitypes.ManagedTX, err error) {
	res := sth.policyEngineAPIRequest(ctx, &policyEngineAPIRequest{
		requestType: ActionSuspend,
		txID:        txID,
	})
	return res.tx, res.err
}

func (sth *simpleTransactionHandler) HandleTransactionUpdate(ctx context.Context, txID string, updates apitypes.TXUpdatesExternal) (mtx *apitypes.ManagedTX, err error) {
	res := sth.policyEngineAPIRequest(ctx, &policyEngineAPIRequest{
		requestType: ActionUpdate,
		txID:        txID,
		txUpdates:   updates,
	})
	return res.tx, res.err
}

func (sth *simpleTransactionHandler) HandleResumeTransaction(ctx context.Context, txID string) (mtx *apitypes.ManagedTX, err error) {
	res := sth.policyEngineAPIRequest(ctx, &policyEngineAPIRequest{
		requestType: ActionResume,
		txID:        txID,
	})
	return res.tx, res.err
}

// createManagedTx 创建一个托管交易对象，并为其分配nonce
// 这是nonce管理的入口点，所有新交易都会在此处获得唯一的nonce值
func (sth *simpleTransactionHandler) createManagedTx(ctx context.Context, txID string, txHeaders *ffcapi.TransactionHeaders, gas *fftypes.FFBigInt, transactionData string) (*apitypes.ManagedTX, error) {

	// 如果gas估算值存在，将其设置到交易头中
	// gas是执行交易所需的计算资源量
	if gas != nil {
		txHeaders.Gas = gas
	}

	// 获取当前时间戳，用于记录交易的创建和更新时间
	now := fftypes.Now()

	// 构造托管交易对象（ManagedTX）
	// 此时交易还没有nonce，nonce将在持久化过程中分配
	mtx := &apitypes.ManagedTX{
		ID:                 txID,                     // 交易ID，必须是命名空间化的操作ID，用于幂等性保证
		Created:            now,                      // 交易创建时间
		Updated:            now,                      // 交易最后更新时间
		TransactionHeaders: *txHeaders,               // 交易头信息（包含from、to、value等）
		TransactionData:    transactionData,          // 交易数据（已编码的交易内容）
		Status:             apitypes.TxStatusPending, // 初始状态为Pending（待处理）
		PolicyInfo:         fftypes.JSONAnyPtr(`{}`), // 策略引擎信息，初始为空JSON对象
	}

	// ========== 关键的Nonce分配逻辑开始 ==========
	// 序列ID（SequenceID）会在持久化过程中添加，确保交易有确定性的顺序
	//
	// 重要说明：
	// 1. 持久化必须在nonce锁内完成，这是最关键的设计决策
	// 2. 这样可以确保nonce序列和全局交易序列保持一致
	// 3. 如果不在锁内持久化，可能导致nonce分配与数据库记录不同步
	//
	// 调用InsertTransactionWithNextNonce方法，该方法会：
	// - 为同一签名者的交易请求串行化处理（通过路由到同一worker或使用互斥锁）
	// - 调用提供的回调函数获取下一个nonce
	// - 将nonce分配给交易对象
	// - 将交易持久化到数据库
	err := sth.toolkit.TXPersistence.InsertTransactionWithNextNonce(ctx, mtx, func(ctx context.Context, signer string) (uint64, error) {
		// NextNonceCallback回调函数：查询区块链节点获取签名者的下一个可用nonce
		//
		// 参数：
		// - ctx: 上下文对象，用于超时控制和取消传播
		// - signer: 签名者地址（例如：0x1234...）
		//
		// 返回：
		// - uint64: 下一个可用的nonce值
		// - error: 查询失败时的错误信息
		//
		// 此回调会被持久化层调用，持久化层会决定是否真的需要查询区块链
		// （可能会使用缓存或数据库中的值）
		nextNonceRes, _, err := sth.toolkit.Connector.NextNonceForSigner(ctx, &ffcapi.NextNonceForSignerRequest{
			Signer: signer, // 传入签名者地址查询其下一个nonce
		})
		if err != nil {
			// 查询失败，返回0和错误
			// 持久化层会处理此错误，并可能清理相关的缓存
			return 0, err
		}
		// 查询成功，将FFBigInt类型的nonce转换为uint64返回
		return nextNonceRes.Nonce.Uint64(), nil
	})

	// 如果插入成功（包括nonce分配成功），记录nonce分配动作到交易历史
	if err == nil {
		// AddSubStatusAction记录交易的子状态转换和动作
		// - TxSubStatusReceived: 交易已接收状态
		// - TxActionAssignNonce: 动作类型为分配nonce
		// - JSON信息: 记录分配的nonce值，便于审计和调试
		err = sth.toolkit.TXHistory.AddSubStatusAction(
			ctx,
			txID,
			apitypes.TxSubStatusReceived, // 当前子状态
			apitypes.TxActionAssignNonce, // 执行的动作
			fftypes.JSONAnyPtr(`{"nonce":"`+mtx.Nonce.String()+`"}`), // 动作信息（包含分配的nonce）
			nil,           // 错误信息（无错误时为nil）
			fftypes.Now(), // 动作发生时间
		)
	}

	// 如果有任何错误（nonce分配失败或历史记录失败），返回错误
	if err != nil {
		return nil, err
	}
	// ========== Nonce分配逻辑结束 ==========

	// 记录日志：开始追踪此交易
	// 日志格式：交易ID / 签名者地址 / nonce值
	// 例如："Tracking transaction tx-123 at nonce 0x1234... / 100"
	log.L(ctx).Infof("Tracking transaction %s at nonce %s / %d", mtx.ID, mtx.TransactionHeaders.From, mtx.Nonce.Int64())

	// 标记飞行中交易列表需要刷新
	// 新交易已创建，策略循环需要重新加载交易列表
	sth.markInflightStale()

	// 返回已分配nonce的托管交易对象
	return mtx, nil
}

// submitTX 提交交易到区块链网络
// 使用之前分配的nonce值，将交易发送到区块链节点的交易池
func (sth *simpleTransactionHandler) submitTX(ctx *RunContext) (reason ffcapi.ErrorReason, err error) {
	// 获取当前要提交的托管交易对象
	mtx := ctx.TX

	// 步骤1: 获取Gas价格
	// Gas价格决定了矿工打包交易的优先级，价格越高越快被打包
	mtx.GasPrice, err = sth.getGasPrice(ctx, sth.toolkit.Connector)
	if err != nil {
		// Gas价格获取失败，记录错误动作到历史
		ctx.AddSubStatusAction(apitypes.TxActionRetrieveGasPrice, nil, fftypes.JSONAnyPtr(`{"error":"`+err.Error()+`"}`), fftypes.Now())
		return "", err
	}
	// Gas价格获取成功，记录成功动作到历史
	ctx.AddSubStatusAction(apitypes.TxActionRetrieveGasPrice, fftypes.JSONAnyPtr(`{"gasPrice":`+string(*mtx.GasPrice)+`}`), nil, fftypes.Now())

	// 步骤2: 构造交易发送请求
	// 准备要发送到区块链的完整交易数据
	sendTX := &ffcapi.TransactionSendRequest{
		TransactionHeaders: mtx.TransactionHeaders, // 交易头（包含from、to、value等）
		GasPrice:           mtx.GasPrice,           // Gas价格
		TransactionData:    mtx.TransactionData,    // 已编码的交易数据
	}

	// ========== 关键：设置Nonce到交易头 ==========
	// 将之前分配的nonce值设置到即将发送的交易中
	// 这是nonce被实际使用的地方，确保交易按正确的顺序被区块链处理
	sendTX.TransactionHeaders.Nonce = (*fftypes.FFBigInt)(mtx.Nonce.Int())

	// 同时设置Gas限制（允许的最大计算量）
	sendTX.TransactionHeaders.Gas = (*fftypes.FFBigInt)(mtx.Gas.Int())

	// 记录调试日志：即将发送交易
	// 包含：交易ID、签名者地址、使用的nonce、上次提交时间
	// 例如："Sending transaction tx-123 at nonce 0x1234... / 100 (lastSubmit=2024-01-01...)"
	log.L(ctx).Debugf("Sending transaction %s at nonce %s / %d (lastSubmit=%s)", mtx.ID, mtx.TransactionHeaders.From, mtx.Nonce.Int64(), mtx.LastSubmit)

	// 步骤3: 发送交易到区块链节点
	// 记录开始时间，用于性能监控
	transactionSendStartTime := time.Now()

	// 调用connector的TransactionSend方法，将交易提交到区块链节点的交易池
	// 返回值：
	// - res: 包含交易哈希的响应
	// - reason: 错误原因分类（如果失败）
	// - err: 详细错误信息（如果失败）
	res, reason, err := sth.toolkit.Connector.TransactionSend(ctx, sendTX)

	// 更新监控指标
	sth.incTransactionOperationCounter(ctx, mtx.Namespace(ctx), "transaction_submission")
	sth.recordTransactionOperationDuration(ctx, mtx.Namespace(ctx), "transaction_submission", time.Since(transactionSendStartTime).Seconds())

	// 步骤4: 处理提交结果
	if err == nil {
		// ========== 提交成功分支 ==========
		// 记录提交成功动作
		ctx.AddSubStatusAction(apitypes.TxActionSubmitTransaction, fftypes.JSONAnyPtr(`{"reason":"`+string(reason)+`"}`), nil, fftypes.Now())

		// 保存区块链返回的交易哈希
		// 交易哈希是交易在区块链上的唯一标识符
		mtx.TransactionHash = res.TransactionHash

		// 记录最后提交时间
		mtx.LastSubmit = fftypes.Now()

		// 标记需要更新数据库
		// 因为交易哈希和提交时间已变更，需要持久化
		ctx.UpdateType = Update
		ctx.TXUpdates.TransactionHash = &res.TransactionHash
		ctx.TXUpdates.LastSubmit = mtx.LastSubmit
		ctx.TXUpdates.GasPrice = mtx.GasPrice
	} else {
		// ========== 提交失败分支 ==========
		// 记录提交失败动作，包含错误原因和详细错误信息
		ctx.AddSubStatusAction(apitypes.TxActionSubmitTransaction, fftypes.JSONAnyPtr(`{"reason":"`+string(reason)+`"}`), fftypes.JSONAnyPtr(`{"error":"`+err.Error()+`"}`), fftypes.Now())

		// ========== 关键：Nonce相关错误的特殊处理 ==========
		// 我们对connector返回的错误原因进行分类处理
		// 这些规则可以通过扩展connector来增强
		switch reason {
		case ffcapi.ErrorKnownTransaction, ffcapi.ErrorReasonNonceTooLow:
			// 情况1: ErrorKnownTransaction - 相同的交易已经提交过
			// 情况2: ErrorReasonNonceTooLow - nonce值过低，已被使用
			//
			// 这两种情况在某些场景下是可以接受的：

			// 如果我们已经有了交易哈希，说明之前某次提交成功了
			// 这种情况下，这个错误是正常的，不应该视为失败
			// 例如：重试提交时，交易可能已经在链上了
			if mtx.TransactionHash != "" {
				// 记录调试日志：交易已知且有哈希
				// 格式：交易ID / 签名者地址 / nonce / 交易哈希 / 错误信息
				log.L(ctx).Debugf("Transaction %s at nonce %s / %d known with hash: %s (%s)", mtx.ID, mtx.TransactionHeaders.From, mtx.Nonce.Int64(), mtx.TransactionHash, err)
				// 返回成功（无错误）
				return "", nil
			}

			// 边缘情况说明：
			// 如果首次TransactionSend超时或其他失败，但节点实际已接收交易
			// 这时我们没有交易哈希，但交易可能已在链上
			//
			// 解决方案（需要connector支持）：
			// - 需要一个新的FFCAPI接口来重新计算预期的交易哈希
			// - 这需要connector能够执行签名而不提交到节点
			// - 例如对于EVM，可以使用 `eth_signTransaction` JSON-RPC方法
			//
			// 目前：如果没有交易哈希，我们将错误返回给调用者
			return reason, err
		default:
			// 其他类型的错误，直接返回
			// 例如：gas price too low、insufficient funds等
			return reason, err
		}
	}

	// 步骤5: 提交成功后的处理
	// 记录信息日志：交易已成功提交
	// 格式：交易ID / 签名者地址 / nonce / 交易哈希
	// 例如："Transaction tx-123 at nonce 0x1234... / 100 submitted. Hash: 0xabcd..."
	log.L(ctx).Infof("Transaction %s at nonce %s / %d submitted. Hash: %s", mtx.ID, mtx.TransactionHeaders.From, mtx.Nonce.Int64(), mtx.TransactionHash)

	// 更新子状态为Tracking（追踪中）
	// 表示交易已提交，现在需要追踪其确认状态
	ctx.SetSubStatus(apitypes.TxSubStatusTracking)

	// 返回成功
	return "", nil
}

func (sth *simpleTransactionHandler) processTransaction(ctx *RunContext) (err error) {

	// Simply policy engine allows deletion of the transaction without additional checks ( ensuring the TX has not been submitted / gap filling the nonce etc. )
	mtx := ctx.TX
	if mtx.DeleteRequested != nil {
		ctx.UpdateType = Delete
		return nil
	}

	if mtx.FirstSubmit == nil {
		// Submit the first time
		if _, err := sth.submitTX(ctx); err != nil {
			return err
		}
		mtx.FirstSubmit = mtx.LastSubmit
		ctx.TXUpdates.FirstSubmit = mtx.FirstSubmit
		return nil

	} else if ctx.Receipt == nil {

		// A more sophisticated policy engine would look at the reason for the lack of a receipt, and consider taking progressive
		// action such as increasing the gas cost slowly over time. This simple example shows how the policy engine
		// can use the FireFly core operation as a store for its historical state/decisions (in this case the last time we warned).
		lastWarnTime := ctx.Info.LastWarnTime
		if lastWarnTime == nil {
			lastWarnTime = mtx.FirstSubmit
		}
		now := fftypes.Now()
		if now.Time().Sub(*lastWarnTime.Time()) > sth.resubmitInterval {
			secsSinceSubmit := float64(now.Time().Sub(*mtx.FirstSubmit.Time())) / float64(time.Second)
			log.L(ctx).Infof("Transaction %s at nonce %s / %d has not been mined after %.2fs", mtx.ID, mtx.TransactionHeaders.From, mtx.Nonce.Int64(), secsSinceSubmit)
			ctx.UpdateType = Update
			ctx.UpdatedInfo = true
			ctx.Info.LastWarnTime = now
			// We do a resubmit at this point - as it might no longer be in the TX pool
			ctx.AddSubStatusAction(apitypes.TxActionTimeout, nil, nil, fftypes.Now())
			ctx.SetSubStatus(apitypes.TxSubStatusStale)
			if reason, err := sth.submitTX(ctx); err != nil {
				if reason != ffcapi.ErrorKnownTransaction {
					return err
				}
			}
			return nil
		}

	}
	// No action in the case we have a receipt
	return nil
}

// getGasPrice either uses a fixed gas price, or invokes a gas station API
func (sth *simpleTransactionHandler) getGasPrice(ctx context.Context, cAPI ffcapi.API) (gasPrice *fftypes.JSONAny, err error) {
	if sth.gasOracleQueryValue != nil && sth.gasOracleLastQueryTime != nil &&
		time.Since(*sth.gasOracleLastQueryTime.Time()) < sth.gasOracleQueryInterval {
		return sth.gasOracleQueryValue, nil
	}
	switch sth.gasOracleMode {
	case GasOracleModeRESTAPI:
		// Make a REST call against an endpoint, and extract a value/structure to pass to the connector
		gasPrice, err := sth.getGasPriceAPI(ctx)
		if err != nil {
			return nil, err
		}
		sth.gasOracleQueryValue = gasPrice
		sth.gasOracleLastQueryTime = fftypes.Now()
		return sth.gasOracleQueryValue, nil
	case GasOracleModeConnector:
		// Call the connector
		res, _, err := cAPI.GasPriceEstimate(ctx, &ffcapi.GasPriceEstimateRequest{})
		if err != nil {
			return nil, err
		}
		sth.gasOracleQueryValue = res.GasPrice
		sth.gasOracleLastQueryTime = fftypes.Now()
		return sth.gasOracleQueryValue, nil
	default:
		// Disabled - just a fixed value - note that the fixed value can be any JSON structure,
		// as interpreted by the connector. For example EVMConnect support a simple value, or a
		// post EIP-1559 structure.
		return sth.fixedGasPrice, nil
	}
}

func (sth *simpleTransactionHandler) getGasPriceAPI(ctx context.Context) (gasPrice *fftypes.JSONAny, err error) {
	res, err := sth.gasOracleClient.R().
		Execute(sth.gasOracleMethod, "")
	if err != nil {
		return nil, i18n.WrapError(ctx, err, tmmsgs.MsgErrorQueryingGasOracleAPI, -1, err.Error())
	}
	if res.IsError() {
		return nil, i18n.WrapError(ctx, err, tmmsgs.MsgErrorQueryingGasOracleAPI, res.StatusCode(), res.RawResponse)
	}
	// Parse the response body as JSON
	var data map[string]interface{}
	err = json.Unmarshal(res.Body(), &data)
	if err != nil {
		return nil, i18n.WrapError(ctx, err, tmmsgs.MsgInvalidJSONGasObject)
	}
	buff := new(bytes.Buffer)
	err = sth.gasOracleTemplate.Execute(buff, data)
	if err != nil {
		return nil, i18n.WrapError(ctx, err, tmmsgs.MsgGasOracleResultError)
	}
	return fftypes.JSONAnyPtr(buff.String()), nil
}
