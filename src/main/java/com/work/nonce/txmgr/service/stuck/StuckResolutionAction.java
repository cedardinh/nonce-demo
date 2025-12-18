package com.work.nonce.txmgr.service.stuck;

/**
 * 业务可选的 STUCK 处置动作。
 *
 * 注意：本项目保持“策略可插拔”，具体动作如何落地由业务 hook 自行决定。
 */
public enum StuckResolutionAction {
    /**
     * 将交易标记为 STUCK（停止自动重提）。
     */
    MARK_STUCK,

    /**
     * 立即重提（等价于允许本轮 resubmit 执行）。
     */
    RESUBMIT_NOW,

    /**
     * 业务侧“取消交易/占位交易”类补救：系统会用 hook 提供的 payload 发送同 nonce 的交易。
     */
    CANCEL,

    /**
     * 业务侧“替换交易”补救：系统会用 hook 提供的 payload 发送同 nonce 的替换交易。
     */
    REPLACE,

    /**
     * 业务侧“占位交易”补救：系统会用 hook 提供的 payload 发送同 nonce 的占位交易。
     */
    PLACEHOLDER,

    /**
     * 延后下一次重提时间（不标记 STUCK）。
     */
    DELAY,

    /**
     * 什么也不做（保持原状）。
     */
    IGNORE
}


