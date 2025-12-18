package com.work.nonce.txmgr.service.stuck;

import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;

/**
 * 业务侧可插拔 hook：当交易被判定为“可能卡住”（例如超过最大重提次数）时，允许业务决定如何处置。
 *
 * 约束：
 * - 该 hook 不应做耗时阻塞操作（会影响调度线程）。
 * - 若返回 null 或抛异常，系统将使用默认策略（MARK_STUCK）。
 */
@FunctionalInterface
public interface StuckResolutionHook {

    StuckResolutionDecision onStuckCandidate(ManagedTxEntity tx, StuckResolutionContext ctx);

    static StuckResolutionHook defaultHook() {
        // 设计稿口径：默认 NOOP（不自动填洞/取消/替换，也不强制 STUCK），仅让系统继续 resubmit/告警。
        return (tx, ctx) -> StuckResolutionDecision.ignore("default NOOP; submitAttempts=" +
                (ctx == null ? -1 : ctx.getSubmitAttempts()) + ", maxAttempts=" + (ctx == null ? -1 : ctx.getMaxAttempts()));
    }
}


