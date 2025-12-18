package com.work.nonce.txmgr.service.stuck;

import java.time.Duration;

/**
 * STUCK 处置结果（由业务 hook 返回）。
 */
public class StuckResolutionDecision {

    private final StuckResolutionAction action;
    private final Duration delay;
    private final String subState;
    private final String note;
    /**
     * 可选：补救动作（CANCEL/REPLACE/PLACEHOLDER）需要的 payload。
     * 由业务自行约定其语义与格式（例如 JSON / RLP / 业务签名材料引用等）。
     */
    private final String remediationPayload;

    private StuckResolutionDecision(StuckResolutionAction action, Duration delay, String subState, String note, String remediationPayload) {
        this.action = action;
        this.delay = delay;
        this.subState = subState;
        this.note = note;
        this.remediationPayload = remediationPayload;
    }

    public static StuckResolutionDecision markStuck(String subState, String note) {
        return new StuckResolutionDecision(StuckResolutionAction.MARK_STUCK, null, subState, note, null);
    }

    public static StuckResolutionDecision resubmitNow(String note) {
        return new StuckResolutionDecision(StuckResolutionAction.RESUBMIT_NOW, null, null, note, null);
    }

    public static StuckResolutionDecision replace(String remediationPayload, String note) {
        return new StuckResolutionDecision(StuckResolutionAction.REPLACE, null, null, note, remediationPayload);
    }

    public static StuckResolutionDecision cancel(String remediationPayload, String note) {
        return new StuckResolutionDecision(StuckResolutionAction.CANCEL, null, null, note, remediationPayload);
    }

    public static StuckResolutionDecision placeholder(String remediationPayload, String note) {
        return new StuckResolutionDecision(StuckResolutionAction.PLACEHOLDER, null, null, note, remediationPayload);
    }

    public static StuckResolutionDecision delay(Duration delay, String note) {
        return new StuckResolutionDecision(StuckResolutionAction.DELAY, delay, null, note, null);
    }

    public static StuckResolutionDecision ignore(String note) {
        return new StuckResolutionDecision(StuckResolutionAction.IGNORE, null, null, note, null);
    }

    public StuckResolutionAction getAction() {
        return action;
    }

    public Duration getDelay() {
        return delay;
    }

    public String getSubState() {
        return subState;
    }

    public String getNote() {
        return note;
    }

    public String getRemediationPayload() {
        return remediationPayload;
    }
}


