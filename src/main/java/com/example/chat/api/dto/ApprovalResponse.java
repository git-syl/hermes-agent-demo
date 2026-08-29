package com.example.chat.api.dto;

/**
 * 审批回填的响应。
 *
 * @param accepted {@code true} 表示成功唤醒等待中的工具循环；
 *                 {@code false} 表示 {@code requestId} 不存在或已被 timeout/其它路径完成 ——
 *                 前端据此提示"审批已超时，请重新发起对话"。
 */
public record ApprovalResponse(boolean accepted) {

    public static ApprovalResponse ok() {
        return new ApprovalResponse(true);
    }

    public static ApprovalResponse miss() {
        return new ApprovalResponse(false);
    }
}
