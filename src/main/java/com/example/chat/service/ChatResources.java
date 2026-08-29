package com.example.chat.service;

import com.example.chat.sandbox.SandboxSessionManager;
import com.example.chat.service.DynamicMcpClientFactory.McpClients;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.sandbox.Sandbox;

import java.util.function.Supplier;

/**
 * Per-request 资源组合：MCP 会话 + Sandbox 租约。
 *
 * <p>解决 {@link ChatService#chat} / {@link ChatService#streamChat} 中两个独立资源
 * <b>级联分配 / 级联释放</b>的样板代码：之前需要在调用方手写 try-catch 处理"mcp 已分配
 * 但 sandbox 分配失败"导致的 mcp 泄漏。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@link #allocate}：按 mcp → lease 顺序分配，任一阶段抛异常时已分配的资源被
 *       自动关闭，调用方无需兜底；原异常 + {@code addSuppressed(closeEx)} 透传。</li>
 *   <li>{@link #close()}：按 lease → mcp 反向释放，<b>吞掉 close 自身异常</b>避免单个
 *       资源关闭失败连累另一个；这是符合 {@link AutoCloseable} 推荐实践的"幂等且最大化释放"。</li>
 * </ul>
 *
 * <h2>使用</h2>
 * <pre>{@code
 * // 同步路径
 * try (ChatResources res = ChatResources.allocate(
 *         () -> mcpFactory.build(req.mcpConfig()),
 *         () -> needSandbox ? sessionManager.acquire(key, dirs) : null)) {
 *     Sandbox sandbox = res.sandbox();
 *     // ... 业务 ...
 * }
 *
 * // 流式路径
 * ChatResources res = ChatResources.allocate(...);
 * return flux.doFinally(s -> res.close());
 * }</pre>
 */
public final class ChatResources implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatResources.class);

    private final McpClients mcp;
    // jspecify @Nullable 是 TYPE_USE，限定内部类型必须把注解放在最后一段（Lease）前面
    private final SandboxSessionManager.@Nullable Lease sandboxLease;

    private ChatResources(McpClients mcp, SandboxSessionManager.@Nullable Lease lease) {
        this.mcp = mcp;
        this.sandboxLease = lease;
    }

    /**
     * 级联分配：先 mcp 后 lease，后者失败时立即关 mcp 防泄漏。
     *
     * @param mcpSupplier   mcp 工厂；必须返回 non-null
     * @param leaseSupplier lease 工厂；返回 null 表示本次请求不需要 sandbox
     * @throws RuntimeException 透传任一阶段的异常；mcp 若已成功分配会被关闭后再抛
     */
    public static ChatResources allocate(
            Supplier<McpClients> mcpSupplier,
            Supplier<SandboxSessionManager.@Nullable Lease> leaseSupplier) {
        McpClients mcp = mcpSupplier.get();
        try {
            SandboxSessionManager.Lease lease = leaseSupplier.get();
            return new ChatResources(mcp, lease);
        } catch (RuntimeException e) {
            try {
                mcp.close();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    public McpClients mcp() {
        return mcp;
    }

    /** 当前请求未分配沙箱时返回 {@code null}（skills 列表为空、或灰度关闭 + sessionId 空）。 */
    @Nullable
    public Sandbox sandbox() {
        return sandboxLease == null ? null : sandboxLease.sandbox();
    }

    /** 反向关闭：先 lease 后 mcp，任一抛异常都不影响另一个被关。close 自身幂等。 */
    @Override
    public void close() {
        if (sandboxLease != null) {
            try {
                sandboxLease.close();
            } catch (Exception e) {
                log.warn("Sandbox lease close failed: {}", e.getMessage());
            }
        }
        try {
            mcp.close();
        } catch (Exception e) {
            log.warn("MCP session close failed: {}", e.getMessage());
        }
    }
}
