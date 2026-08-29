package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.config.ToolPolicyProperties;
import com.example.chat.service.ApprovalRegistry.Decision;
import com.example.chat.service.ApprovalRegistry.Pending;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 共享的 HITL 工具循环 gate。装饰一个 {@link ToolCallingManager}（作为方法参数逐次传入，不持有），
 * 在 {@code executeToolCalls} 入口承担两件事：
 *
 * <ol>
 *   <li><b>SSE 事件旁路</b>：本轮工具调用入参作为工具调用事件、执行结果作为工具结果事件 emit 到调用方持有的 sink。</li>
 *   <li><b>HITL 审批 gate</b>：命中 {@link ToolPolicyProperties#requiresApproval(String)} 的工具调用，
 *       先 emit 审批请求事件并阻塞等待前端通过 {@code POST /chat/approval} 回填决定；DECLINE / 超时的工具不执行，
 *       由本类合成一条固定话术的 {@code ToolResponse} 告诉模型"用户拒了，别再调"。</li>
 * </ol>
 *
 * <p>主 agent 与子代理共用本类，差异仅"事件标签"一维，由 {@link HitlEventFactory} 策略决定：
 * 主 agent（{@link HitlEventFactory#mainAgent()}）emit {@code tool_call} / {@code approval_request} / {@code tool_result}
 * （事件 {@code name}=null）；子代理（{@link HitlEventFactory#subagent(String)}）emit {@code subagent_*}
 * （事件 {@code name}=子代理名）。两个薄适配器 {@link ObservableToolCallingManager} /
 * {@link SubagentToolCallingManager} 只负责装配合适的 factory 并转发，整条 HITL 逻辑只此一份。
 *
 * <p>tool_call 事件也走这里的原因：GA 起流式路径已被 advisor 硬过滤掉 {@code hasToolCalls()} 的 chunk，
 * 下游 Flux 看不到工具调用帧，故在 {@code executeToolCalls} 入口提前 emit（此时 chatResponse 是
 * advisor 聚合好的完整版本，id/name/arguments 都规整）。
 *
 * <p>核心模式抄自 {@code McpToolCallingManager}（聚合 → 串行问 → 拆批执行 → 顺序保留 → fail-safe），
 * 交互通道不同：playground 用 Vaadin {@code UI.access()} 弹窗阻塞；本项目 emit 到 sink 后在
 * {@link ApprovalRegistry} 上 {@code future.get()} 阻塞，由独立 {@code POST /chat/approval} 回填。
 * 数据结构改进：playground 用问题文本作 map key（同轮同名同参会撞 key），本类用 {@code requestId}
 * （UUID）作 key，天然无冲突。
 *
 * <h2>fail-safe 不变式</h2>
 * <p>任何"非 APPROVE"路径都不允许工具执行：
 * <ul>
 *   <li>构造时 sink / policy / approvals / events 任一为 null → 立即 NPE，宁可启动失败也不让
 *       REQUIRED 工具静默执行（半装配不进生产）；</li>
 *   <li>超时（{@link TimeoutException}）→ DECLINE，工具不执行，模型收到"已拒"话术；</li>
 *   <li>线程中断（{@link InterruptedException}）→ DECLINE 并复位中断标志；</li>
 *   <li>其它任何异常 → DECLINE。</li>
 * </ul>
 *
 * <h2>per-request 绕行（{@code bypassApproval}）</h2>
 * <p>构造时传入的 {@code bypassApproval=true}（主 agent 源自 {@code ChatRequest.bypassApproval}，
 * 子代理跟随主请求）会让本次工具循环内<u>所有</u>命中白名单的工具直接放行执行：不 emit 审批请求、
 * 不注册 future、不等前端回填。适用于"调用方已自行审批 / 后端服务到服务 / 批处理"场景，
 * <b>必须由网关/BFF 层按调用来源决定，禁止暴露给终端用户控制</b>。被绕行的工具会无条件落审计日志
 * （{@code hitl.bypassed tool=...}），事后可追溯。
 *
 * <p><b>命名澄清</b>：字段叫 {@code bypassApproval} 而非 {@code autoApprove} —— 行为是"跳过整个
 * 审批流程"，不是"审批流程照走、按钮被自动点同意"。
 *
 * <h2>事件顺序</h2>
 * <pre>
 *   工具调用事件 (本轮全部 toolCall, 含需审批的; 主=tool_call / 子=subagent_tool_call)
 *   ├─ 审批请求事件 × N (仅需审批的, 一个工具一条; 主=approval_request / 子=subagent_approval_request)
 *   │  └─ 每条审批请求后立即补一条 heartbeat（撑满代理缓冲，确保前端即时收到审批提示）
 *   └─ 阻塞等待 future（周期性保活由 /chat/stream 流管线承担，不在本 gate）
 *      ├─ APPROVE → delegate.executeToolCalls(approved)
 *      └─ DECLINE → 合成 "用户拒了" 的 ToolResponse
 *   工具结果事件 (按原始 toolCall 顺序合并; 主=tool_result / 子=subagent_tool_result)
 * </pre>
 *
 * <h2>线程模型</h2>
 * <p>{@code ToolCallingAdvisor} 在流式路径上把 {@code executeToolCalls} 调度到
 * {@code Schedulers.boundedElastic()}，所以这里阻塞 {@code future.get()} 是安全的，不会卡死 Netty IO
 * 线程。子代理链路同理。非流式（{@code chat()}）不走到本 gate（{@code assembleSpec} 传 null manager）。
 *
 * <p>"最终答案"工具（{@code FinalAnswerTool}）的退出循环行为已交给 Spring AI 原生的
 * {@code @Tool(returnDirect = true)} 机制处理：{@code ToolCallingAdvisor} 检测到
 * {@link ToolExecutionResult#returnDirect()} 时直接把工具结果包成 {@code ChatResponse}
 * 单 chunk 下发，不再发起下一轮 LLM。
 */
class HitlToolCallingGate {

    private static final Logger log = LoggerFactory.getLogger(HitlToolCallingGate.class);

    private final Sinks.Many<ChatEvent> sink;
    private final ToolPolicyProperties policy;
    private final ApprovalRegistry approvals;
    private final boolean bypassApproval;
    private final HitlEventFactory events;
    /**
     * 日志后缀字面量：主 agent 为 {@code ""}，子代理为 {@code " subagent=<name>"}。
     * 直接拼进 SLF4J 格式串的 {@code {}} 槽 —— 主 agent 渲染为空、文本不变，子代理文本不变，零分支。
     */
    private final String subTrail;
    /**
     * emit 失败日志的中段后缀字面量：主 agent 为 {@code ""}，子代理为 {@code " (subagent=<name>)"}。
     */
    private final String subParen;

    HitlToolCallingGate(Sinks.Many<ChatEvent> sink, ToolPolicyProperties policy, ApprovalRegistry approvals,
                        boolean bypassApproval, HitlEventFactory events) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.events = Objects.requireNonNull(events, "events");
        this.bypassApproval = bypassApproval;
        String name = events.name();
        this.subTrail = (name == null) ? "" : " subagent=" + name;
        this.subParen = (name == null) ? "" : " (subagent=" + name + ")";
    }

    /**
     * 整条工具循环入口。顺序：工具调用事件 → 审批 gate → 拆批执行 → 工具结果事件。
     *
     * @param delegate 被装饰的原生 {@link ToolCallingManager}，approved 子集的真执行委托给它。
     *                 逐次传入而非持有，使本 gate 对任意 delegate 可复用、可独立单测。
     */
    ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse, ToolCallingManager delegate) {
        emitToolCallEvent(chatResponse);

        Set<String> declined = gateApprovals(chatResponse);

        ToolExecutionResult result = declined.isEmpty()
                ? delegate.executeToolCalls(prompt, chatResponse)
                : executeWithDeclined(prompt, chatResponse, declined, delegate);

        emitToolResultEvent(result);
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // HITL gate：聚合 → 推送审批请求 → 阻塞 future → 返回被拒的 toolCallId 集合
    // ---------------------------------------------------------------------------------------------

    /**
     * 仿 {@code McpToolCallingManager.resolveDeclinedToolCalls}：
     * <ol>
     *   <li>扫一遍 toolCalls，筛出 {@code policy.requiresApproval(name)} 命中的；</li>
     *   <li>逐个注册 future，把审批请求事件推给前端；</li>
     *   <li>串行 {@code future.get()}，把非 APPROVE 的 toolCallId 收进 declined。</li>
     * </ol>
     *
     * <p>串行（而非 {@code allOf} 并行总超时）与 playground 行为一致：N 个工具最坏总等待
     * N × timeout，但实现最简单、错误隔离也最干净。前端可以一次性收到 N 条审批请求
     * 后批量展示批量回填，不会被服务端的串行限制。
     */
    private Set<String> gateApprovals(ChatResponse chatResponse) {
        // per-request 绕行：所有"本来要 gate"的工具直接放行，但留审计日志以便事后追溯。
        // 故意不 short-circuit 到 "if (bypassApproval) return Set.of()" —— 我们仍然要遍历一遍
        // 识别"被绕行"的工具名 + toolCallId，否则审计断链。
        if (bypassApproval) {
            for (ToolCall toolCall : flattenToolCalls(chatResponse)) {
                if (policy.requiresApproval(toolCall.name())) {
                    log.info("hitl.bypassed tool={} toolCallId={}{} (per-request bypassApproval=true)",
                            toolCall.name(), toolCall.id(), subTrail);
                }
            }
            return Set.of();
        }

        List<PendingApproval> needed = new ArrayList<>();
        for (ToolCall toolCall : flattenToolCalls(chatResponse)) {
            if (!policy.requiresApproval(toolCall.name())) {
                continue;
            }
            Pending p = approvals.register(policy.getTimeout());
            needed.add(new PendingApproval(p, toolCall));
            // emit 失败仅记 debug：不影响主流程，主流程后面会因 future 超时按 DECLINE 兜底。
            emit(events.approvalRequest(
                    p.requestId(), toolCall.id(), toolCall.name(), toolCall.arguments()));
            // 立即补一条 heartbeat：把 nginx/CF/ALB 的代理缓冲撑满触发 flush，确保前端即时收到
            // 审批提示（不等周期性心跳的首次延迟）。长阻塞期间的周期性保活由 /chat/stream 流管线承担。
            emit(ChatEvent.heartbeat());
            log.info("hitl.approval.requested tool={} toolCallId={} requestId={}{}",
                    toolCall.name(), toolCall.id(), p.requestId(), subTrail);
        }
        if (needed.isEmpty()) {
            return Set.of();
        }

        Set<String> declinedToolCallIds = new HashSet<>();
        for (PendingApproval pa : needed) {
            Decision decision = awaitDecision(pa.pending);
            if (decision != Decision.APPROVE) {
                declinedToolCallIds.add(pa.toolCall.id());
                log.info("hitl.declined tool={} toolCallId={} requestId={}{}",
                        pa.toolCall.name(), pa.toolCall.id(), pa.pending.requestId(), subTrail);
            } else {
                log.info("hitl.approved tool={} toolCallId={} requestId={}{}",
                        pa.toolCall.name(), pa.toolCall.id(), pa.pending.requestId(), subTrail);
            }
        }
        return declinedToolCallIds;
    }

    /** 一条待审批：把 {@link Pending}（future + requestId）与原 toolCall 配对，便于按原序串行等待。 */
    private record PendingApproval(Pending pending, ToolCall toolCall) {}

    /**
     * 阻塞等待一条审批决定。所有"非 APPROVE"路径都按 DECLINE 兜底 —— 这是 fail-safe 的核心：
     * 没有任何异常路径能让 REQUIRED 工具被静默执行。
     *
     * <p><b>SSE 保活不在本方法</b>：审批请求 emit 后 {@code gateApprovals} 已立即补一条
     * {@code heartbeat} 撑满代理缓冲，确保前端即时收到审批提示；长阻塞期间的周期性保活
     * 由 {@code /chat/stream} 流管线承担（见 {@code ChatService.buildStreamFlux} 接的
     * {@code StreamHeartbeat}）。本方法只管阻塞 + fail-safe 兜底。
     */
    private Decision awaitDecision(Pending p) {
        try {
            return p.future().get();
        } catch (InterruptedException e) {
            // 复位中断标志，让上层 reactor 调度链能继续看到中断信号（例如 Flux.using 关流）。
            Thread.currentThread().interrupt();
            log.warn("hitl.await.interrupted requestId={}{}", p.requestId(), subTrail);
            return Decision.DECLINE;
        } catch (ExecutionException e) {
            // orTimeout 完成时会把 TimeoutException 包在 ExecutionException 里。
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                log.info("hitl.await.timeout requestId={}{}", p.requestId(), subTrail);
            } else {
                log.warn("hitl.await.failed requestId={}{} error={}", p.requestId(), subTrail,
                        cause == null ? e.getMessage() : cause.toString());
            }
            return Decision.DECLINE;
        } catch (RuntimeException e) {
            log.warn("hitl.await.failed requestId={}{} error={}", p.requestId(), subTrail, e.getMessage());
            return Decision.DECLINE;
        }
    }

    /**
     * 仿 {@code McpToolCallingManager.executeWithDeclined}：把 toolCalls 拆成 approved / declined
     * 两组分别处理，按原顺序合并 ToolResponse。顺序关键 —— assistant.tool_calls 与 tool[i] 必须
     * 一一对应，否则 DeepSeek/OpenAI 下一轮回放会 400。
     */
    private ToolExecutionResult executeWithDeclined(Prompt prompt, ChatResponse chatResponse,
                                                    Set<String> declinedToolCallIds, ToolCallingManager delegate) {
        AssistantMessage assistantMessage = chatResponse.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && !output.getToolCalls().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No tool call requested by the chat model"));

        List<ToolCall> allToolCalls = assistantMessage.getToolCalls();
        List<ToolCall> approvedToolCalls = allToolCalls.stream()
                .filter(toolCall -> !declinedToolCallIds.contains(toolCall.id()))
                .toList();

        Map<String, ToolResponseMessage.ToolResponse> responsesById = new LinkedHashMap<>();

        // 只对 approved 的子集走真正执行；如果全被拒就跳过 delegate，避免空 toolCalls 的非法请求。
        if (!approvedToolCalls.isEmpty()) {
            AssistantMessage approvedAssistant = AssistantMessage.builder()
                    .content(assistantMessage.getText())
                    .properties(assistantMessage.getMetadata())
                    .toolCalls(approvedToolCalls)
                    .build();
            ToolExecutionResult approvedResult = delegate.executeToolCalls(prompt,
                    new ChatResponse(List.of(new Generation(approvedAssistant))));
            Message last = approvedResult.conversationHistory().getLast();
            if (last instanceof ToolResponseMessage toolResponseMessage) {
                toolResponseMessage.getResponses()
                        .forEach(response -> responsesById.put(response.id(), response));
            }
        }

        // 被拒的工具：合成固定话术的 ToolResponse，让模型读到"用户拒了"并停止重试。
        for (ToolCall toolCall : allToolCalls) {
            if (declinedToolCallIds.contains(toolCall.id())) {
                responsesById.put(toolCall.id(), new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), declinedMessage(toolCall.name())));
            }
        }

        // 按 assistant.tool_calls 的原序排列 tool responses —— 顺序错位会被严格校验的上游 400。
        List<ToolResponseMessage.ToolResponse> orderedResponses = allToolCalls.stream()
                .map(toolCall -> responsesById.get(toolCall.id()))
                .filter(Objects::nonNull)
                .toList();
        List<Message> conversationHistory = new ArrayList<>(prompt.getInstructions());
        conversationHistory.add(assistantMessage);
        conversationHistory.add(ToolResponseMessage.builder().responses(orderedResponses).build());
        return ToolExecutionResult.builder().conversationHistory(conversationHistory).build();
    }

    /**
     * 给模型的"已拒"话术：必须明确"不要再调"，否则 DeepSeek/OpenAI 这类强工具循环模型会
     * 立刻原样重发同一个 toolCall，造成"审批死循环"。文案与 spring-ai-playground 完全一致。
     */
    private static String declinedMessage(String toolName) {
        return "The user declined to approve running the tool '" + toolName + "'. It was NOT executed. "
                + "Do not call '" + toolName + "' again for this request. If another available tool can accomplish "
                + "the goal, use it instead; otherwise tell the user the action could not be completed because they "
                + "declined approval.";
    }

    // ---------------------------------------------------------------------------------------------
    // SSE 事件 emit（observability 失败一律不影响主流程）
    // ---------------------------------------------------------------------------------------------

    private void emitToolCallEvent(ChatResponse chatResponse) {
        try {
            List<ChatEvent.ToolCallRef> callRefs = extractToolCalls(chatResponse);
            if (!callRefs.isEmpty()) {
                emit(events.toolCall(callRefs));
            }
        } catch (RuntimeException e) {
            log.warn("emit {} failed{}: {}", events.toolCallEventType(), subParen, e.getMessage());
        }
    }

    private void emitToolResultEvent(ToolExecutionResult result) {
        try {
            List<ChatEvent.ToolResultRef> refs = extractToolResponses(result);
            if (!refs.isEmpty()) {
                emit(events.toolResult(refs));
            }
        } catch (RuntimeException e) {
            log.warn("emit {} failed{}: {}", events.toolResultEventType(), subParen, e.getMessage());
        }
    }

    private static List<ToolCall> flattenToolCalls(ChatResponse chatResponse) {
        List<ToolCall> calls = new ArrayList<>();
        if (chatResponse == null) {
            return calls;
        }
        for (Generation gen : chatResponse.getResults()) {
            if (gen == null) continue;
            AssistantMessage msg = gen.getOutput();
            if (msg == null) continue;
            List<ToolCall> tcs = msg.getToolCalls();
            if (tcs != null) calls.addAll(tcs);
        }
        return calls;
    }

    /**
     * 从聚合后的 ChatResponse 抽取所有 generation 的 tool_calls。
     * 多 generation 场景（如 Anthropic thinking 拆块）下，正文 generation 才会带 tool_calls，
     * 这里全量遍历再合并，避免漏掉。
     */
    private static List<ChatEvent.ToolCallRef> extractToolCalls(ChatResponse chatResponse) {
        List<ChatEvent.ToolCallRef> refs = new ArrayList<>();
        for (ToolCall tc : flattenToolCalls(chatResponse)) {
            refs.add(new ChatEvent.ToolCallRef(tc.id(), tc.name(), tc.arguments()));
        }
        return refs;
    }

    private static List<ChatEvent.ToolResultRef> extractToolResponses(ToolExecutionResult result) {
        List<ChatEvent.ToolResultRef> refs = new ArrayList<>();
        if (result == null) return refs;
        List<Message> history = result.conversationHistory();
        if (history == null || history.isEmpty()) return refs;
        Message last = history.get(history.size() - 1);
        if (last instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                refs.add(new ChatEvent.ToolResultRef(r.id(), r.name(), r.responseData()));
            }
        }
        return refs;
    }

    private void emit(ChatEvent event) {
        Sinks.EmitResult er = sink.tryEmitNext(event);
        if (er.isFailure()) {
            log.debug("emit {} failed{}: {}", event.type(), subParen, er);
        }
    }
}
