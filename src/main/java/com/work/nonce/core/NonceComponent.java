package com.work.nonce.core;

import com.work.nonce.core.execution.NonceExecutionHandler;
import com.work.nonce.core.execution.NonceExecutionResult;
import com.work.nonce.core.execution.NonceExecutionTemplate;
import com.work.nonce.core.execution.SignerExecutor;
import com.work.nonce.core.model.NonceAllocation;
import com.work.nonce.core.service.NonceService;

/**
 * 门面（Facade）层，对业务侧暴露最少的调用面。
 */
public class NonceComponent {

    private final NonceExecutionTemplate executionTemplate;
    private final NonceService nonceService;
    private final SignerExecutor signerExecutor;

    public NonceComponent(NonceExecutionTemplate executionTemplate,
                          NonceService nonceService,
                          SignerExecutor signerExecutor) {
        this.executionTemplate = executionTemplate;
        this.nonceService = nonceService;
        this.signerExecutor = signerExecutor;
    }

    /**
     * 推荐用法：在 handler 中执行业务逻辑，模板自动根据执行结果处理状态。
     */
    public NonceExecutionResult withNonce(String signer, NonceExecutionHandler handler) {
        return signerExecutor.execute(signer, () -> executionTemplate.execute(signer, handler));
    }

    /**
     * 低阶接口，允许业务先领取 nonce，再在合适的时机显式标记 CONSUMED/RELEASED。
     */
    public NonceAllocation allocate(String signer) {
        return signerExecutor.execute(signer, () -> nonceService.allocate(signer));
    }

    public void markUsed(String signer, long nonce, String txHash) {
        signerExecutor.execute(signer, () -> nonceService.markUsed(signer, nonce, txHash));
    }

    public void markRecyclable(String signer, long nonce, String reason) {
        signerExecutor.execute(signer, () -> nonceService.markRecyclable(signer, nonce, reason));
    }

    /**
     * 兼容“任意位置随机申请/随机修改/回收”的高级用法：
     * 允许业务把一段逻辑（可包含多次 allocate/markUsed/markRecyclable/自定义状态变更）放到同一个 signer 串行上下文里执行。
     */
    public <T> T withSignerSerial(String signer, java.util.concurrent.Callable<T> work) {
        return signerExecutor.execute(signer, work);
    }
}

