package com.example.chat.api.dto;

/**
 * 前端回填 HITL 审批决定的请求体。
 *
 * @param requestId 服务端通过 SSE {@code approval_request} 事件下发的 {@code data} 字段，原样回传。
 * @param decision  {@code "approve"} 或 {@code "decline"}（大小写不敏感）。其它取值一律按
 *                  {@code decline} 处理 —— fail-safe，避免拼写错误意外授权。
 */
public record ApprovalRequest(String requestId, String decision) {

    /** 严格判定 APPROVE：必须是字面 {@code "approve"}（忽略大小写），其它一律视为 DECLINE。 */
    public boolean isApprove() {
        return decision != null && decision.equalsIgnoreCase("approve");
    }
}
