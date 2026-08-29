package com.example.chat.config;

import com.example.chat.tools.TodoUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link TodoWriteTool} 作为内置工具。
 *
 * <p>对模型：工具名 {@code TodoWrite}，由 {@code ChatService.filterBuiltinTools} 跟
 * {@code WebSearch} / {@code WebFetch} 一起按 {@code ChatRequest.tools} 白名单暴露 ——
 * 客户端 {@code req.tools=["TodoWrite", ...]} 才会启用，避免不需要任务编排的请求徒增 schema。
 *
 * <p>对服务端：handler lambda 做两件事：
 * <ol>
 *   <li>打日志（INFO 级，进度 + 单条状态/标签，方便 ops 端肉眼跟踪）；</li>
 *   <li>{@code publisher.publishEvent(TodoUpdatedEvent)} —— 留给将来 SSE/WebSocket
 *       推前端的扩展点，新增 {@code @EventListener} 即可消费，无需改这里。</li>
 * </ol>
 *
 * <p>上游 doc 提示 {@code TodoWriteTool} 需要 {@code ChatMemory + ToolCallAdvisor}。本项目
 * {@code ChatService} 已经默认装好 {@code org.springframework.ai.chat.client.advisor.ToolCallingAdvisor}
 * + {@code MessageChatMemoryAdvisor}（启用 server memory 时），开箱即用。
 *
 * <p>引导模型主动使用本工具的提示词在 {@link com.example.chat.config.SystemPromptComposer}
 * 里以常量 {@code TODO_WRITE_HINT} 维护，**仅当本次请求的 {@code req.tools} 显式启用
 * "TodoWrite" 时才追加进 system 消息**（由 {@code ChatService.assembleSpec} 判断后传给
 * {@code compose(...,todoWriteEnabled)})。这样设计避免：①工具未启用时仍占 token；
 * ②模型幻觉调用未暴露的工具。
 * <p>工具描述（{@link TodoWriteTool} 的 {@code @Tool} 注解）已经包含 4 正例 + 4 反例，因此
 * {@code TODO_WRITE_HINT} 做成"规则化、零示例"的精简版，避免重复占用 token。
 */
@Configuration
public class TodoWriteToolConfig {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteToolConfig.class);

    @Bean
    public TodoWriteTool todoWriteTool(ApplicationEventPublisher publisher) {
        TodoWriteTool tool = TodoWriteTool.builder()
                .todoEventHandler(todos -> {
                    if (log.isInfoEnabled()) {
                        log.info("TodoWrite updated:\n{}", renderTodos(todos));
                    }
                    publisher.publishEvent(new TodoUpdatedEvent(TodoWriteToolConfig.class, todos));
                })
                .build();
        log.info("TodoWriteTool registered as builtin tool (name=TodoWrite)");
        return tool;
    }

    /**
     * 渲染成"进度 + 多行 todo"的紧凑视图，对齐上游 doc 里 {@code Progress: x/y} + 每行状态 icon 的格式：
     * <pre>
     * Progress: 2/4 tasks completed (50%)
     * [x] Find top 10 movies          ← 已完成用 content（过去式）
     * [~] Printing inverted titles    ← 进行中用 activeForm（进行时）
     * [ ] Group movies in pairs       ← 待办用 content
     * </pre>
     */
    private static String renderTodos(TodoWriteTool.Todos todos) {
        if (todos == null || todos.todos() == null || todos.todos().isEmpty()) {
            return "  (empty)";
        }
        int total = todos.todos().size();
        long completed = todos.todos().stream()
                .filter(t -> t != null && t.status() == TodoWriteTool.Todos.Status.completed)
                .count();
        int percent = total > 0 ? (int) Math.round(completed * 100.0 / total) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  Progress: %d/%d tasks completed (%d%%)", completed, total, percent));
        for (TodoWriteTool.Todos.TodoItem item : todos.todos()) {
            if (item == null) {
                continue;
            }
            sb.append('\n').append("  ").append(renderItem(item));
        }
        return sb.toString();
    }

    private static String renderItem(TodoWriteTool.Todos.TodoItem item) {
        String prefix = switch (item.status()) {
            case completed -> "[x]";
            case in_progress -> "[~]";
            case pending -> "[ ]";
        };
        // in_progress 用 activeForm（"Running tests"）更直观，其余用 content（"Run tests"）。
        String label = item.status() == TodoWriteTool.Todos.Status.in_progress
                ? item.activeForm()
                : item.content();
        return prefix + " " + label;
    }
}
