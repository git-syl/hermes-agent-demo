package com.example.chat.config;

import com.example.chat.api.dto.ChatRequest;
import com.example.chat.api.dto.ChatRequest.SubagentRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SystemPromptComposer} 子代理委派提示（{@code agent_hint}）的条件渲染回归：
 * <ol>
 *   <li>{@code req.subagents()} 非空 → system prompt 含子代理委派段；</li>
 *   <li>{@code req.subagents()} 为 null / 空 → 不含子代理委派段，且不残留多余空行；</li>
 *   <li>委派段出现在 {@code <builtin_rules>} 块内部（与 skill_prompt / todo_hint 同级）。</li>
 * </ol>
 *
 * <p>只覆盖 agent_hint 相关 case，其余 prompt 装配细节由既有测试覆盖（如有）。
 * Clock 固定避免时间字段噪声污染断言。
 */
class SystemPromptComposerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-22T10:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private SystemPromptComposer composerWithBuiltin() {
        ChatPromptProperties props = new ChatPromptProperties();
        props.setSystem("你是助手。");
        return new SystemPromptComposer(props, FIXED_CLOCK);
    }

    private ChatRequest requestWithSubagents(List<SubagentRef> subagents) {
        return requestWithSubagents(subagents, null);
    }

    private ChatRequest requestWithSubagents(List<SubagentRef> subagents, Boolean includeClaudeBuiltin) {
        return new ChatRequest(
                List.of(),             // tools
                null,                  // mcpConfig
                null,                  // skills
                subagents,             // subagents
                "sess",                // sessionId
                null, null, null,      // temp, thinking, userId
                null,                  // assistantId
                "claude-sonnet-4-6",   // modelName
                "hello",               // query
                null, null, null, null, null, null, null, includeClaudeBuiltin);  // system..includeClaudeBuiltin
    }

    @Test
    void agentHintIncludedWhenSubagentsPresent() {
        ChatRequest withSubagents = requestWithSubagents(
                List.of(new SubagentRef("spring-ai-expert", "http://h/a.md")));

        String prompt = composerWithBuiltin().compose(withSubagents, null);

        assertThat(prompt)
                .contains("子代理委派")
                .contains("Task 工具");
        // 委派段必须在 <builtin_rules> 块内部
        int builtinStart = prompt.indexOf("<builtin_rules");
        int builtinEnd = prompt.indexOf("</builtin_rules>");
        int hintStart = prompt.indexOf("子代理委派");
        assertThat(hintStart).isGreaterThan(builtinStart).isLessThan(builtinEnd);
    }

    @Test
    void agentHintOmittedWhenSubagentsAbsent() {
        String prompt = composerWithBuiltin().compose(requestWithSubagents(null), null);

        assertThat(prompt).doesNotContain("子代理委派");
        assertThat(prompt).doesNotContain("Task 工具");
    }

    @Test
    void agentHintOmittedWhenSubagentsEmpty() {
        ChatRequest req = requestWithSubagents(List.of()); // 空 list —— 等同于未启用

        String prompt = composerWithBuiltin().compose(req, null);

        assertThat(prompt).doesNotContain("子代理委派");
        // 关键：不残留空行 —— builtin_rules 块内不应出现连续三个换行
        assertThat(prompt).doesNotContain("\n\n\n");
    }

    /**
     * 用户 subagents 单独（无 skills、includeClaudeBuiltin=null）→ 装配 Task 但无沙箱：
     * AGENT_HINT 渲染，且追加 NO_TOOLS_CLAUSE（点明子代理无文件工具）。
     */
    @Test
    void agentHintHasNoToolsClauseWhenUserSubagentsOnly() {
        ChatRequest req = requestWithSubagents(
                List.of(new SubagentRef("spring-ai-expert", "http://h/a.md")));

        String prompt = composerWithBuiltin().compose(req, null);

        assertThat(prompt).contains("子代理委派");
        assertThat(prompt).contains("未启用沙箱");
        assertThat(prompt).contains("不携带");
    }

    /**
     * 显式开 Claude 模式（includeClaudeBuiltin=true）→ 建沙箱、装配 Task（4 内置）：
     * AGENT_HINT 渲染，但不追加 NO_TOOLS_CLAUSE（沙箱在，子代理有文件工具）。
     */
    @Test
    void agentHintRenderedWithoutClauseWhenExplicitBuiltin() {
        ChatRequest req = requestWithSubagents(null, true);

        String prompt = composerWithBuiltin().compose(req, null);

        assertThat(prompt).contains("子代理委派");
        assertThat(prompt).doesNotContain("未启用沙箱");
        assertThat(prompt).doesNotContain("不携带");
    }
}
