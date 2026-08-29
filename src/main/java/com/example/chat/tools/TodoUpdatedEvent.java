package com.example.chat.tools;

import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.context.ApplicationEvent;

/**
 * Spring 应用事件 —— 模型每次调用 {@code TodoWrite} 工具时，由 {@link TodoWriteToolBridge}
 * （位于 {@code TodoWriteToolConfig}）通过 {@code ApplicationEventPublisher} 发出。
 *
 * <p>当前只有一个内置消费者：日志打印（见 config 类的 handler lambda）。
 *
 * <p>扩展路径：将来需要把 todo 列表实时推到前端，新增一个 {@code @EventListener} 即可，
 * 例如挂到 SSE/WebSocket sink，把 {@link #todos()} 转成 {@code ChatEvent} 推给客户端。
 * 不需要改动 {@code TodoWriteTool} 的 handler 或 ChatService。
 */
public class TodoUpdatedEvent extends ApplicationEvent {

    private final TodoWriteTool.Todos todos;

    public TodoUpdatedEvent(Object source, TodoWriteTool.Todos todos) {
        super(source);
        this.todos = todos;
    }

    public TodoWriteTool.Todos todos() {
        return this.todos;
    }
}
