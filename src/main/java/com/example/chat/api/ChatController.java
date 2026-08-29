package com.example.chat.api;

import com.example.chat.api.dto.ApprovalRequest;
import com.example.chat.api.dto.ApprovalResponse;
import com.example.chat.api.dto.ChatEvent;
import com.example.chat.api.dto.ChatRequest;
import com.example.chat.api.dto.ChatRequestContext;
import com.example.chat.api.dto.ChatResponse;
import com.example.chat.service.ApprovalRegistry;
import com.example.chat.service.ApprovalRegistry.Decision;
import com.example.chat.service.ChatService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Chat 入口：只做 HTTP ↔ Domain 的协议适配，业务逻辑全部在 {@link ChatService}。
 *
 * <p>请求头解析（X-Ctx-* 与 CF-*）和 sessionId 兜底已下沉到
 * {@link ChatRequestContext#of}，controller 因此只剩 "路由 + 一行调度"。
 *
 * <p>{@code /chat/stream} 端点出错时仍然以 SSE {@code error} 事件返回（见
 * {@link ChatService#streamChat}），不会让客户端拿到混搭的 200/SSE 与 500/JSON。
 *
 * <p>{@code POST /chat/approval}：HITL 审批回填端点。SSE 流推 {@code approval_request} 事件时
 * 带 {@code requestId}，前端展示审批 UI 后用本端点回填 approve/decline。详见
 * {@link com.example.chat.service.ObservableToolCallingManager}。
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final ApprovalRegistry approvalRegistry;

    public ChatController(ChatService chatService, ApprovalRegistry approvalRegistry) {
        this.chatService = chatService;
        this.approvalRegistry = approvalRegistry;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request,
                             @RequestHeader HttpHeaders headers) {
        return chatService.chat(ChatRequestContext.of(request, headers));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatEvent> streamChat(@RequestBody ChatRequest request,
                                      @RequestHeader HttpHeaders headers,
                                      HttpServletResponse response) {
        // X-Accel-Buffering: no —— nginx 私有头，本路径关闭代理缓冲，让 SSE 帧立即下发到客户端。
        // Cloudflare / 多数云 LB（AWS ALB、阿里云 SLB）也识别该头。
        // 与 ObservableToolCallingManager.awaitDecision 期间发的 heartbeat 帧形成双保险：
        // 头不被认时还有应用层心跳兜底，把 approval_request 帧推过代理缓冲。
        // 注意：本项目是 spring-boot-starter-web（MVC/servlet），用 HttpServletResponse 而非
        // reactive 的 ServerHttpResponse。MVC 允许 controller 返回 Flux<T> 配合 SSE 媒体类型，
        // 内部通过 ReactiveAdapterRegistry 桥接成 servlet async streaming，response 头依然走 servlet API。
        response.setHeader("X-Accel-Buffering", "no");
        return chatService.streamChat(ChatRequestContext.of(request, headers));
    }

    /**
     * 回填 HITL 审批决定。请求体形如：
     * <pre>{@code {"requestId": "...", "decision": "approve" | "decline"}}</pre>
     *
     * <p>响应 {@code accepted=false} 通常意味着该 requestId 已超时被自动判 DECLINE，
     * 或来自其它进程实例（多实例部署下若未做 sticky session 会命中此情况）；前端展示
     * "审批已超时" 即可，不必重试 —— 工具循环此时早已按 DECLINE 推进。
     */
    @PostMapping(value = "/chat/approval",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApprovalResponse approve(@RequestBody ApprovalRequest body) {
        if (body == null || body.requestId() == null || body.requestId().isBlank()) {
            return ApprovalResponse.miss();
        }
        Decision decision = body.isApprove() ? Decision.APPROVE : Decision.DECLINE;
        boolean ok = approvalRegistry.complete(body.requestId(), decision);
        return ok ? ApprovalResponse.ok() : ApprovalResponse.miss();
    }
}
