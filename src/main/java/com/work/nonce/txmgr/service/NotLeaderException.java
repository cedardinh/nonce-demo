package com.work.nonce.txmgr.service;

/**
 * 对外语义：当前节点不是 submitter 的 leader（应返回 409）。
 */
public class NotLeaderException extends RuntimeException {
    public NotLeaderException(String message) {
        super(message);
    }
}


