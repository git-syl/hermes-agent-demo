package com.example.chat.sandbox;

import io.github.markpollack.sandbox.ExecResult;
import io.github.markpollack.sandbox.ExecSpec;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 沙箱版 ShellTools —— Bash / BashOutput / KillShell。所有命令都在绑定的
 * {@link Sandbox}（通常是 Docker 容器）里跑，宿主机不可达。
 *
 * <p>与上游 {@code ShellTools} 的对齐关系：
 * <ul>
 *     <li>工具名、参数签名、{@code @ToolParam} 描述：严格对齐。</li>
 *     <li>{@code @Tool} 主体描述：对齐主干，删掉 git / PR / gh 那一长段
 *         （沙箱里这些场景跑不通且只会污染模型推理）。</li>
 *     <li>同步 / 后台返回格式（{@code bash_id:} 头、Shell ID/Status 块、三态 not_found）：
 *         一一对齐。</li>
 *     <li>状态存储：上游用 JVM 内 {@code static Map<id, BackgroundProcess>}，沙箱版用
 *         {@code /work/.bash-sessions/} 下的六个文件（{@code .out} / {@code .err} /
 *         {@code .exit} / {@code .pid} / {@code .out.pos} / {@code .err.pos}）做"文件版 Map"，
 *         跟随沙箱寿命自动回收，Java 端零状态。</li>
 * </ul>
 *
 * <p>沙箱内每个后台 shell 的文件视图：
 * <pre>
 * /work/.bash-sessions/&lt;shell_id&gt;.out      ← stdout 流（nohup 重定向）
 * /work/.bash-sessions/&lt;shell_id&gt;.err      ← stderr 流（nohup 重定向）
 * /work/.bash-sessions/&lt;shell_id&gt;.exit     ← 进程退出码（完成后由 wrapper 写入）
 * /work/.bash-sessions/&lt;shell_id&gt;.pid      ← 后台进程 PID（不存在 == Map miss）
 * /work/.bash-sessions/&lt;shell_id&gt;.out.pos  ← BashOutput 读到的 stdout 字节位置（增量游标）
 * /work/.bash-sessions/&lt;shell_id&gt;.err.pos  ← BashOutput 读到的 stderr 字节位置（增量游标）
 * </pre>
 */
public class SandboxBashTool {

    private static final int MAX_OUTPUT_CHARS = 30_000;

    /** 沙箱内后台 shell 状态目录（绝对路径）。 */
    private static final String BG_DIR = "/work/.bash-sessions";

    /**
     * shell_id 合法形态：{@code shell_<毫秒>}。所有 BashOutput / KillShell 收到的 id 都拼到 shell
     * 命令里，必须严格校验以杜绝命令注入。
     */
    private static final Pattern SHELL_ID_PATTERN = Pattern.compile("^shell_\\d+$");

    private final Sandbox sandbox;
    /**
     * 单次同步 Bash 调用的超时上限。
     * <p>双语义：LLM 不传 timeout 时作为默认值；LLM 传 timeout 时作为硬上限，超过会被夹紧。
     */
    private final long maxTimeoutMs;
    private final Duration defaultTimeout;
    private final Map<String, String> envOverrides;
    /**
     * 是否把 envOverrides 以 {@code export KEY=...; } 的形式拼到 bash 命令前。
     * <p>Docker 模式下 env 通过 {@code docker exec -e} 注入容器进程，最干净 —— 关闭即可。
     * <p>Local 模式下 {@code bash -lc} 会跑 login profile，且在 Windows + WSL/Git Bash
     * 边界上自定义 env 经常被 {@code WSLENV} 白名单或 profile 吃掉，必须靠内联 export 兜底。
     */
    private final boolean inlineExportEnv;

    public SandboxBashTool(Sandbox sandbox, long defaultTimeoutMs) {
        this(sandbox, defaultTimeoutMs, Map.of(), false);
    }

    public SandboxBashTool(Sandbox sandbox, long defaultTimeoutMs, Map<String, String> envOverrides) {
        this(sandbox, defaultTimeoutMs, envOverrides, false);
    }

    public SandboxBashTool(Sandbox sandbox, long defaultTimeoutMs,
                           Map<String, String> envOverrides, boolean inlineExportEnv) {
        this.sandbox = sandbox;
        this.maxTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : 1L;
        this.defaultTimeout = Duration.ofMillis(this.maxTimeoutMs);
        this.envOverrides = envOverrides == null || envOverrides.isEmpty()
                ? Map.of()
                : Map.copyOf(envOverrides);
        this.inlineExportEnv = inlineExportEnv;
    }

    // @formatter:off
    @Tool(name = "Bash", description = """
        Execute a bash command for terminal operations like npm, docker, make, mvn, python.
        DO NOT use for file operations — use specialized tools instead:
        - File search: Use Glob (NOT find or ls)
        - Content search: Use Grep (NOT grep or rg)
        - Read files: Use Read (NOT cat/head/tail)
        - Edit files: Use Edit (NOT sed/awk)
        - Write files: Use Write (NOT echo >/cat <<EOF)

        Usage notes:
        - The command argument is required.
        - Optional timeout in milliseconds (capped server-side). Default: server config.
        - Output truncated at 30000 characters.
        - Use runInBackground for long-running commands; later read with BashOutput.
        - Quote file paths with spaces in double quotes.
        - Chain dependent commands with &&. Use ; if earlier failures are acceptable.
        - Prefer absolute paths over cd.
        """)
    public String bash(
        @ToolParam(description = "The command to execute") String command,
        @ToolParam(description = "Optional timeout in milliseconds (max 600000)", required = false) Long timeout,
        @ToolParam(description = "Clear, concise description of what this command does in 5-10 words, in active voice. Examples:\nInput: ls\nOutput: List files in current directory\n\nInput: git status\nOutput: Show working tree status\n\nInput: npm install\nOutput: Install package dependencies\n\nInput: mkdir foo\nOutput: Create directory 'foo'", required = false) String description,
        @ToolParam(description = "Set to true to run this command in the background. Use BashOutput to read the output later.", required = false) Boolean runInBackground) {
        // @formatter:on

        String wrapped = command;
        if (inlineExportEnv && !envOverrides.isEmpty()) {
            StringBuilder prefix = new StringBuilder();
            for (Map.Entry<String, String> e : envOverrides.entrySet()) {
                prefix.append("export ").append(e.getKey()).append('=')
                        .append(shellQuote(e.getValue())).append("; ");
            }
            wrapped = prefix + command;
        }

        if (Boolean.TRUE.equals(runInBackground)) {
            return launchBackground(wrapped);
        }
        return runSync(wrapped, timeout);
    }

    /**
     * 同步执行：完全保留原有语义（含输出截断 / exit code / 头部 bash_id）。
     */
    private String runSync(String wrapped, Long timeout) {
        // LLM 传入值仅作为"不超过上限的请求"使用；最终一律按 maxTimeoutMs 夹紧，
        // 防止 prompt-injection 把单次 Bash 拖到运维允许之上的时长。
        Duration t = (timeout != null && timeout > 0)
                ? Duration.ofMillis(Math.min(timeout, maxTimeoutMs))
                : defaultTimeout;

        ExecSpec.Builder builder = ExecSpec.builder()
                .command("bash", "-lc", wrapped)
                .timeout(t);
        if (!envOverrides.isEmpty()) {
            builder.env(envOverrides);
        }

        // 同步分支也给一个 shell_id 头部，跟上游对齐 —— 模型语义里 bash_id 始终是 Bash 的输出锚。
        String shellId = "shell_" + System.currentTimeMillis();

        ExecResult result;
        try {
            result = sandbox.exec(builder.build());
        } catch (RuntimeException e) {
            return "bash_id: " + shellId + "\n\nError executing command: " + e.getMessage();
        }

        StringBuilder out = new StringBuilder();
        out.append("bash_id: ").append(shellId).append("\n\n");
        if (!result.stdout().isEmpty()) {
            out.append(result.stdout());
        }
        if (!result.stderr().isEmpty()) {
            if (out.length() > ("bash_id: " + shellId + "\n\n").length()) {
                out.append('\n');
            }
            out.append("STDERR:\n").append(result.stderr());
        }
        if (result.exitCode() != 0) {
            if (out.length() > ("bash_id: " + shellId + "\n\n").length()) {
                out.append('\n');
            }
            out.append("Exit code: ").append(result.exitCode());
        }
        String s = out.toString();
        if (s.length() > MAX_OUTPUT_CHARS) {
            s = s.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)";
        }
        return s;
    }

    /**
     * 后台执行：在沙箱里用 nohup fork，wrapper 跑完后把 exit code 写到 {@code .exit}，
     * 一并落地 {@code .out} / {@code .err} / {@code .exit} / {@code .pid} 四个文件，立即返回 shell_id。
     * <p>stdout / stderr 分流到独立文件（而非合并 {@code 2>&1}），让 BashOutput 能像上游
     * 一样按 {@code STDOUT:} / {@code STDERR:} 两段分别呈现。
     * <p>不传 timeout —— 后台进程的寿命由 KillShell 或 sandbox session-max-lifetime 兜底。
     */
    private String launchBackground(String wrapped) {
        String shellId = "shell_" + System.currentTimeMillis();

        // 一次 exec 内：建目录 → 通过环境变量穿越引号 → nohup 启动 wrapper → 写 PID。
        //
        // 关键技巧 —— wrapped 已经被 shellQuote 包成 single-quote 字面量，要再嵌进 outer
        // bash -c '...' 的 single-quote 里无法避免引号互咬。这里走"环境变量穿越"：先 export
        // _USER_CMD 到外层 shell（值是 wrapped 的原文，shellQuote 的引号被赋值解构掉），
        // 外层 `nohup bash -c '...'` 用 single-quote 拒绝展开 $_USER_CMD，把它原样交给
        // 内层 nohup 启动的 bash 解析，内层 bash 才展开变量并执行用户命令。
        //
        // wrapper 内部：bash -lc "$_USER_CMD" > .out 2> .err；执行完 echo $? > .exit
        // 把退出码落地，BashOutput 在 Completed 时就能展示 Exit code: N。
        //
        // 关键：必须用换行分隔几条主命令，**不能**用 `A && B & C` —— `&&` 优先级高于 `&`，
        // 会把整段丢后台，echo $! 抢跑导致 PID 文件写不进去。
        String launch = String.format("""
                mkdir -p %s
                export _USER_CMD=%s
                nohup bash -c 'bash -lc "$_USER_CMD" > %s/%s.out 2> %s/%s.err; echo $? > %s/%s.exit' </dev/null > /dev/null 2>&1 &
                echo $! > %s/%s.pid
                """,
                BG_DIR,
                shellQuote(wrapped),
                BG_DIR, shellId, BG_DIR, shellId, BG_DIR, shellId,
                BG_DIR, shellId);

        ExecSpec.Builder builder = ExecSpec.builder()
                .command("bash", "-lc", launch)
                .timeout(Duration.ofSeconds(10));
        if (!envOverrides.isEmpty()) {
            builder.env(envOverrides);
        }

        try {
            ExecResult res = sandbox.exec(builder.build());
            if (res.exitCode() != 0) {
                return "bash_id: " + shellId + "\n\nError launching background shell: "
                        + res.stderr() + " (exit " + res.exitCode() + ")";
            }
        } catch (RuntimeException e) {
            return "bash_id: " + shellId + "\n\nError launching background shell: " + e.getMessage();
        }

        return String.format(
                "bash_id: %s\n\nBackground shell started with ID: %s\nUse BashOutput tool with bash_id='%s' to retrieve output.",
                shellId, shellId, shellId);
    }

    // @formatter:off
    @Tool(name = "BashOutput", description = """
        - Retrieves output from a running or completed background bash shell
        - Takes a shell_id parameter identifying the shell
        - Always returns only new output since the last check
        - Returns stdout and stderr output along with shell status
        - Supports optional regex filtering to show only lines matching a pattern
        - Use this tool when you need to monitor or check the output of a long-running shell
        """)
    public String bashOutput(
        @ToolParam(description = "The ID of the background shell to retrieve output from") String bash_id,
        @ToolParam(description = "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read.", required = false) String filter) {
        // @formatter:on

        if (bash_id == null || !SHELL_ID_PATTERN.matcher(bash_id).matches()) {
            return "Error: No background shell found with ID: " + bash_id;
        }
        // 用 .pid 文件是否存在做 not_found 判定 —— 对齐上游 Map miss 语义；KillShell 之后
        // .pid 会被删掉，再来查询就走这条分支，跟上游"已 remove 的 entry"行为一致。
        if (!sandbox.files().exists(".bash-sessions/" + bash_id + ".pid")) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        // 一次 exec 完成：分别取 stdout/stderr 大小、读旧位置、判活、读 exit code、tail 两段增量、
        // 回写两个位置、报告 STATUS+EXIT。三个标记 ---STDOUT--- / ---STDERR--- / ---META---
        // 把输出切成三段，Java 端用 indexOf 解析。
        // %s 是 String.format 占位符 (BG_DIR / bash_id)；shell 内 stat 用的 %s 转义为 %%s。
        String script = String.format("""
                BG=%s
                ID=%s
                OUT=$BG/$ID.out
                ERR=$BG/$ID.err
                EXIT_F=$BG/$ID.exit
                PID_F=$BG/$ID.pid
                OUT_POS_F=$BG/$ID.out.pos
                ERR_POS_F=$BG/$ID.err.pos
                SIZE_OUT=$(stat -c %%s "$OUT" 2>/dev/null || echo 0)
                SIZE_ERR=$(stat -c %%s "$ERR" 2>/dev/null || echo 0)
                POS_OUT=$(cat "$OUT_POS_F" 2>/dev/null || echo 0)
                POS_ERR=$(cat "$ERR_POS_F" 2>/dev/null || echo 0)
                PID=$(cat "$PID_F" 2>/dev/null)
                EXIT_CODE=$(cat "$EXIT_F" 2>/dev/null)
                if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then STATUS=Running; else STATUS=Completed; fi
                echo "---STDOUT---"
                if [ "$SIZE_OUT" -gt "$POS_OUT" ]; then tail -c +$((POS_OUT+1)) "$OUT"; fi
                echo "---STDERR---"
                if [ "$SIZE_ERR" -gt "$POS_ERR" ]; then tail -c +$((POS_ERR+1)) "$ERR"; fi
                echo "---META---"
                echo "STATUS=$STATUS"
                echo "EXIT=$EXIT_CODE"
                echo "$SIZE_OUT" > "$OUT_POS_F"
                echo "$SIZE_ERR" > "$ERR_POS_F"
                """, BG_DIR, bash_id);

        ExecResult res;
        try {
            res = sandbox.exec(ExecSpec.builder()
                    .command("bash", "-lc", script)
                    .timeout(Duration.ofSeconds(10))
                    .build());
        } catch (RuntimeException e) {
            return "Error reading background shell " + bash_id + ": " + e.getMessage();
        }

        String raw = res.stdout();
        int outIdx = raw.indexOf("---STDOUT---");
        int errIdx = outIdx >= 0 ? raw.indexOf("---STDERR---", outIdx + 1) : -1;
        int metaIdx = errIdx >= 0 ? raw.indexOf("---META---", errIdx + 1) : -1;

        String newStdout = "";
        String newStderr = "";
        String status = "Unknown";
        String exitCode = "";
        if (outIdx >= 0 && errIdx >= 0 && metaIdx >= 0) {
            // strip() 仅去掉 echo 标记前后产生的环绕空白；内部的换行/缩进保留。
            newStdout = raw.substring(outIdx + "---STDOUT---".length(), errIdx).strip();
            newStderr = raw.substring(errIdx + "---STDERR---".length(), metaIdx).strip();
            String meta = raw.substring(metaIdx + "---META---".length());
            for (String line : meta.split("\n")) {
                if (line.startsWith("STATUS=")) {
                    status = line.substring("STATUS=".length()).trim();
                } else if (line.startsWith("EXIT=")) {
                    exitCode = line.substring("EXIT=".length()).trim();
                }
            }
        }

        // filter 只影响展示；游标已经按全量推进（对齐上游 ShellTools.getNewOutput 行为）。
        if (filter != null && !filter.isEmpty()) {
            if (!newStdout.isEmpty()) {
                newStdout = filterByRegex(newStdout, filter);
            }
            if (!newStderr.isEmpty()) {
                newStderr = filterByRegex(newStderr, filter);
            }
        }

        // 返回格式严格对齐上游 ShellTools.bashOutput：
        //   Shell ID: <id>
        //   Status: Running | Completed
        //   Exit code: N           (仅 Completed 且 .exit 已落地时；race condition 下可能短暂缺)
        //
        //   New output:            (仅有任一段非空时)
        //   STDOUT:
        //   <stdout>
        //
        //   STDERR:
        //   <stderr>
        // 或：
        //   No new output since last check.
        StringBuilder out = new StringBuilder();
        out.append("Shell ID: ").append(bash_id).append('\n');
        out.append("Status: ").append(status).append('\n');
        if ("Completed".equalsIgnoreCase(status) && !exitCode.isEmpty()) {
            out.append("Exit code: ").append(exitCode).append('\n');
        }
        if (newStdout.isEmpty() && newStderr.isEmpty()) {
            out.append("\nNo new output since last check.");
        } else {
            out.append("\nNew output:\n");
            if (!newStdout.isEmpty()) {
                out.append("STDOUT:\n").append(newStdout);
            }
            if (!newStderr.isEmpty()) {
                if (!newStdout.isEmpty()) {
                    // strip() 已剥掉 stdout 末尾换行，这里补 \n\n 形成空行分隔两段。
                    out.append("\n\n");
                }
                out.append("STDERR:\n").append(newStderr);
            }
        }
        String s = out.toString();
        if (s.length() > MAX_OUTPUT_CHARS) {
            s = s.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)";
        }
        return s;
    }

    // @formatter:off
    @Tool(name = "KillShell", description = """
        - Kills a running background bash shell by its ID
        - Takes a shell_id parameter identifying the shell to kill
        - Returns a success or failure status
        - Use this tool when you need to terminate a long-running shell
        """)
    public String killShell(
        @ToolParam(description = "The ID of the background shell to kill") String bash_id) {
        // @formatter:on

        if (bash_id == null || !SHELL_ID_PATTERN.matcher(bash_id).matches()) {
            return "Error: No background shell found with ID: " + bash_id;
        }
        if (!sandbox.files().exists(".bash-sessions/" + bash_id + ".pid")) {
            return "Error: No background shell found with ID: " + bash_id;
        }

        // 一次 exec：读 PID、删 .pid / .out.pos / .err.pos / .exit、按存活情况决定 kill 还是
        // 直接报 ALREADY_TERMINATED。.out / .err 保留，模型若想看死前输出可用 Bash 工具显式 cat。
        // .exit 通常在 SIGKILL 场景下根本写不出来（wrapper 的 echo $? 永远跑不到），但万一
        // KillShell 抢在 wrapper 写完 .exit 之后才到也要顺手清掉，不留垃圾。
        String script = String.format("""
                BG=%s
                ID=%s
                PID_F=$BG/$ID.pid
                OUT_POS_F=$BG/$ID.out.pos
                ERR_POS_F=$BG/$ID.err.pos
                EXIT_F=$BG/$ID.exit
                PID=$(cat "$PID_F" 2>/dev/null)
                rm -f "$PID_F" "$OUT_POS_F" "$ERR_POS_F" "$EXIT_F"
                if [ -z "$PID" ] || ! kill -0 "$PID" 2>/dev/null; then
                    echo ALREADY_TERMINATED
                    exit 0
                fi
                kill -9 "$PID" 2>/dev/null
                sleep 0.3
                echo KILLED
                """, BG_DIR, bash_id);

        ExecResult res;
        try {
            res = sandbox.exec(ExecSpec.builder()
                    .command("bash", "-lc", script)
                    .timeout(Duration.ofSeconds(10))
                    .build());
        } catch (RuntimeException e) {
            return "Error killing background shell " + bash_id + ": " + e.getMessage();
        }

        String tag = res.stdout().trim();
        return switch (tag) {
            case "KILLED" -> "Successfully killed shell: " + bash_id;
            case "ALREADY_TERMINATED" ->
                    "Shell " + bash_id + " was already terminated. Removed from active shells.";
            default -> "Error: No background shell found with ID: " + bash_id;
        };
    }

    /**
     * 按行匹配 regex 过滤 —— 等价上游 {@code BackgroundProcess.filterOutput}。
     * <p>正则非法时返回原文，避免一个坏 filter 把 BashOutput 整段堵死。
     */
    private static String filterByRegex(String text, String regex) {
        Pattern p;
        try {
            p = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (p.matcher(line).find()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 单引号包裹 + 把 value 里的 ' 转义为 '\''，保证任意字符串安全拼进 shell。
     * 例：{@code abc'def} -&gt; {@code 'abc'\''def'}。
     */
    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
