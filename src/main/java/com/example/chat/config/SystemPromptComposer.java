package com.example.chat.config;

import com.example.chat.api.dto.ChatRequest;
import com.example.chat.api.dto.ClientInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 把"内置规则 + 用户人设 + 运行时上下文（当前时间等）"按 XML 结构化拼成最终的 system 消息。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>XML 分块</b>：Anthropic / OpenAI 都推荐用 XML tag 给不同语义段做"硬边界"。</li>
 *   <li><b>优先级显式声明</b>：{@code <builtin_rules priority="highest">} 并在规则正文末尾再用
 *       自然语言强调一次，避免用户人设覆盖服务方规则。</li>
 *   <li><b>动态上下文与静态规则分离</b>：{@code <context>} 只放运行时变量，不污染 prompt 缓存。</li>
 *   <li><b>空段不渲染</b>：用户没传 {@code system} 时整段 {@code <user_persona>} 省略。</li>
 *   <li><b>统一用 Spring AI {@link PromptTemplate} 渲染</b>：ST4 默认 {@code {}} 分隔符与 XML
 *       的 {@code <>} 不冲突；所有"渲染/不渲染"判断留在 Java 侧，把算好的值（含标签或空串）
 *       通过占位符注入模板。</li>
 * </ul>
 *
 * <p>最终输出形如：
 * <pre>
 * &lt;system_prompt&gt;
 * &lt;context&gt;
 *   &lt;current_time&gt;2026-05-23 22:30:00 +08:00&lt;/current_time&gt;
 * &lt;/context&gt;
 *
 * &lt;builtin_rules priority="highest" override_user_persona="true"&gt;
 * （application.yaml 的 chat.prompt.system 正文）
 * （+ Skills 提示段：路径说明 + Windows 翻译(条件) + FinalAnswer 协议）
 * （+ TodoWrite 纪律段，仅当本次请求启用 TodoWrite）
 * &lt;/builtin_rules&gt;
 *
 * &lt;user_persona priority="lower"&gt;
 * （/chat/stream 请求体里 system 字段）
 * &lt;/user_persona&gt;
 * &lt;/system_prompt&gt;
 * </pre>
 */
@Component
public class SystemPromptComposer {

    /**
     * 主模板：所有动态内容通过 {@code {var}} 占位符注入，一次 render 出最终 system 消息。
     * 可选子块（client_info / user_persona / agent_hint / todo_hint）由 Java 侧算好整段
     * （含标签或空串）后注入 —— 模板是唯一的"输出结构真相"。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            <system_prompt>
            <context>
              <current_time>{now}</current_time>{client_info_block}
            </context>

            <builtin_rules priority="highest" override_user_persona="true">
            {builtin_body}{skill_prompt}{agent_hint}{todo_hint}
            </builtin_rules>{user_block}
            </system_prompt>
            """;

    /** 用户人设子模板：前导空行与上一个块视觉分离；不渲染时整段（含标签）一起消失。 */
    private static final String USER_BLOCK_TEMPLATE = """

            <user_persona priority="lower">
            {user}
            </user_persona>""";

    /** 客户端地理信息子模板：每个字段一个占位符，缺失时 Java 侧填空串，全部为空时整段不渲染。 */
    private static final String CLIENT_INFO_TEMPLATE = """

              <client_info>{country}{city}{region}{timezone}
              </client_info>""";

    /** 启动时一次性判定；Windows 宿主才会渲染"路径翻译"提示段。 */
    private static final boolean ON_WINDOWS_HOST = File.separatorChar == '\\';

    /** Skills 工具链运行约束：沙箱路径说明 + Windows 路径翻译（仅 Windows）+ FinalAnswer 交付协议。 */
    private static final String SKILL_PROMPT = """


            关于 Skill 工具与沙箱路径：
            - Skill 工具返回的 "Base directory" 在沙箱里直接可用 —— 宿主机与沙箱的路径已对齐到
              相同的 `/work/...` 形态；脚本里直接 cd / cat 这个路径即可。
            - 沙箱中只有 `python3`，没有 `python`，请直接使用 `python3` 调用脚本。"""
            + (ON_WINDOWS_HOST ? """


            关于沙箱路径翻译（仅服务端运行在 Windows 上时适用）：
            - 工具返回的 Base directory / 文件路径可能形如 `X:\\work\\skills-cache\\...`。
            - 脚本实际执行在 Linux 沙箱里，工作目录是 `/work`。翻译规则：剥掉盘符前缀（`X:`），
              反斜杠换正斜杠 —— `X:\\work\\skills-cache\\foo` → `/work/skills-cache/foo`。
            - host 路径里 `work\\` 之后的子路径与沙箱内路径完全一致，无需做其它改写。""" : "")
            + """


            关于 FinalAnswer 工具（结构化结果交付协议）：
            - 当某个 Skill 的 SKILL.md 明确要求"原样返回 JSON / verbatim 输出结构化结果"时，
              必须调用 `FinalAnswer` 工具，把脚本 stdout 整段作为 `payload` 参数传入，
              而不是把 JSON 写在助手回复正文里。
            - `payload` 必须是脚本 stdout 的字节级原文，不要包 ```json 围栏、不要重新缩进、
              不要添加任何前后说明文字。
            - 调用 FinalAnswer 之后立即结束本轮，不要再输出任何 token 文本，也不要再调用其它工具。
            - 普通的自然语言回答（例如"现在是 xx 点"）仍按规则 3 用正文回复，不要走 FinalAnswer。""";

    /**
     * 子代理委派提示：仅当本次请求装配了 {@code Task} 工具时追加。装配条件与
     * {@code ChatService.buildTaskTool} 对齐（用户 subagents 非空，或沙箱存在且未显式禁用内置）。
     * 子代理列表本身由 {@code TaskTool} 的工具描述携带，这里只补"使用纪律"。
     */
    private static final String AGENT_HINT = """

            关于子代理委派（Task 工具，可选使用）：
            - 本次请求已装配 `Task` 工具，可把复杂多步任务委派给专门的子代理执行。
            - 子代理有独立的上下文窗口与 system prompt，执行完返回结果文本；主对话只看到最终结果，
              子代理内部工具调用不消耗主上下文。
            - 委派时机：当任务需要专门的领域知识 / 隔离的探索 / 并行多路径研究时，优先委派；
              单步可答的问题不要委派（委派开销大于收益）。
            - 委派后请基于子代理返回的结果继续推理，不要重复其已完成的工作；
              若子代理结果不足，可再次委派并附上更具体的指引，而不是自行重做。
            - 子代理不能 spawn 子代理（层级扁平），不要在委派给子代理的 prompt 里要求它再委派。""";

    /** 仅当装配了 Task 但本次未建沙箱（用户 subagents 单独）时追加：告知子代理无文件工具、只能纯文本回答。 */
    private static final String NO_TOOLS_CLAUSE = """

            本次未启用沙箱：子代理不携带 Bash/Read/Write/Edit 等文件系统工具，仅能给出纯文本回答。
            不要委派需要读写文件或执行命令的任务给子代理；如需文件操作请让用户开启 Skills 或 Claude 模式。""";

    /**
     * 仅当本次请求显式启用 {@code TodoWrite} 工具时才追加的"任务管理纪律"提示。
     * 不放进 application.yaml：跟工具启用状态强绑定，应由代码动态注入，避免静态文案在工具
     * 未启用时也占 token、且让模型幻觉调用不存在的工具。
     */
    private static final String TODO_WRITE_HINT = """


            强制使用：以下任一条件满足时，必须在开始任何分析或编码动作之前先调用 `TodoWrite`，
            把整体计划一次性落成 todo 列表（不允许"先做一点再补 todo"，那样你大概率漏步骤）：
            - 任务需要 3 步及以上才能完成（工具调用、文件读写、信息检索都各算 1 步）；
            - 用户在一条消息里列出多个待办（编号、顿号、换行、"然后"等任何形式都算）；
            - 涉及多文件改动、跨模块改造、需要先研究代码再动手、调试构建/类型/性能问题；
            - 你预计无法在一次回答内完整给出结果，或需要多轮工具调用才能收尾。

            判断犹豫时一律使用本工具 —— 漏规划等同于漏任务，这是不可接受的。
            仅以下情况可以不使用：单步可一句话答完的问题、纯定义解释、闲聊、纯信息查询。

            进度维护（每条都是硬规则）：
            - 同一时刻有且仅有 1 个任务处于 `in_progress`；开始下一项前必须把上一项标为 `completed`；
            - 完成 1 项立即调用 `TodoWrite` 把它标为 `completed`，禁止"批量收尾"；
            - 中途发现遗漏步骤要立即追加 todo，不要绕过列表自行执行；
            - 遇到阻塞、测试失败、未解决报错时，保持 `in_progress` 不要乐观标完成，
              并新增一条描述"待解决问题"的 todo。""";

    /** 渲染器：NONE 模式 —— 业务正文里出现 {@code {anything}} 时不抛错不警告；Renderer 线程安全，单例复用。 */
    private static final StTemplateRenderer RENDERER = StTemplateRenderer.builder()
            .validationMode(ValidationMode.NONE)
            .build();

    /** ISO 风格 + 偏移量，模型理解最稳；不带 locale 依赖。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    private final ChatPromptProperties props;
    private final Clock clock;

    // 本类有两个构造函数（生产 + 测试），不加 @Autowired 时 Spring 无法区分。
    @Autowired
    public SystemPromptComposer(ChatPromptProperties props) {
        this(props, Clock.system(ZoneId.of(props.getTimezone())));
    }

    /** 包私有，便于单元测试注入固定 Clock。 */
    SystemPromptComposer(ChatPromptProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /**
     * 拼装最终 system 消息；两段都为空时返回空串，调用方据此决定是否注入。
     * 所有动态内容一律通过 {@code {var} + Map} 注入主模板，不在 Java 侧 {@code +=} 拼接；
     * 可选段（todo 提示、Windows 路径提示、用户人设、客户端信息）由 Java 侧按请求字段
     * 算好值（完整内容或空串）作为占位符注入。
     *
     * @param req          /chat、/chat/stream 请求体；提供 {@code system}、{@code skills}、{@code tools}
     * @param clientInfo  客户端地理信息，可空；任一字段非空就会渲染 {@code <client_info>} 子段
     */
    public String compose(ChatRequest req, @Nullable ClientInfo clientInfo) {
        @Nullable String userSystem = req.system();
        boolean todoWriteEnabled = req.tools() != null && req.tools().contains("TodoWrite");
        boolean skillsEnabled = req.skills() != null && !req.skills().isEmpty();
        boolean hasUserSubagents = req.subagents() != null && !req.subagents().isEmpty();
        boolean explicitBuiltin = Boolean.TRUE.equals(req.includeClaudeBuiltinSubagents());
        boolean builtinIncl = !Boolean.FALSE.equals(req.includeClaudeBuiltinSubagents()); // null→true
        // 沙箱创建的 req 侧判定（与 ChatService.needSandbox 同口径，下载失败边界忽略）。
        boolean sandboxPresent = skillsEnabled || explicitBuiltin;
        // Task 是否装配：用户 subagents 单独也装（纯文本子代理）；内置需沙箱。与 buildTaskTool 对齐。
        boolean taskAssembled = hasUserSubagents || (sandboxPresent && builtinIncl);

        String builtin = props.getSystem();
        boolean hasBuiltin = StringUtils.hasText(builtin);
        boolean hasUser = StringUtils.hasText(userSystem);
        if (!hasBuiltin && !hasUser) {
            return "";
        }

        // 子块各自 render / 计算后作为占位符值注入主模板。
        Map<String, Object> vars = new HashMap<>();
        vars.put("now", OffsetDateTime.now(clock).format(TIME_FMT));
        vars.put("client_info_block", renderClientInfoBlock(clientInfo));
        vars.put("builtin_body", hasBuiltin ? builtin.strip() : "（未配置内置规则）");
        // Skills 提示段仅在请求带 skills 时渲染完整提示词；否则换成一句占位，省 token、防幻觉调用未暴露的工具。
        vars.put("skill_prompt", skillsEnabled
                ? SKILL_PROMPT
                : "\n\n            当前未启用 Skills 技能。");
        // AGENT_HINT 仅在 Task 真装配时渲染；无沙箱时追加 NO_TOOLS_CLAUSE 告知子代理无文件工具。
        vars.put("agent_hint", taskAssembled
                ? (AGENT_HINT + (sandboxPresent ? "" : NO_TOOLS_CLAUSE))
                : "");
        vars.put("todo_hint", todoWriteEnabled ? TODO_WRITE_HINT : "");
        vars.put("user_block", hasUser
                ? render(USER_BLOCK_TEMPLATE, Map.of("user", userSystem.strip()))
                : "");

        return render(SYSTEM_PROMPT_TEMPLATE, vars);
    }

    /** 渲染 {@code <client_info>} 子段：每个字段一个占位符，缺失填空串，全部为空时整段省略。 */
    private static String renderClientInfoBlock(@Nullable ClientInfo info) {
        if (info == null || info.isEmpty()) {
            return "";
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("country", xmlTagOrEmpty("country", info.country()));
        vars.put("city", xmlTagOrEmpty("city", info.city()));
        vars.put("region", xmlTagOrEmpty("region", info.region()));
        vars.put("timezone", xmlTagOrEmpty("timezone", info.timezone()));
        return render(CLIENT_INFO_TEMPLATE, vars);
    }

    /** 生成单个 XML 子标签字符串；值为空时返回空串，由模板占位符吞掉。 */
    private static String xmlTagOrEmpty(String tag, @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return "\n    <" + tag + ">" + value.strip() + "</" + tag + ">";
    }

    /** 统一的模板渲染入口：复用单例 RENDERER，消除各处重复的 builder 链。 */
    private static String render(String template, Map<String, Object> vars) {
        return PromptTemplate.builder()
                .template(template)
                .renderer(RENDERER)
                .build()
                .render(vars);
    }
}
