# hermes-agent-demo

**中文** | [English](README.en.md)

  ---
基于 Spring Boot 4 / Spring AI 2.0.1 的多模型 Agent 运行时。支持 Anthropic / DeepSeek / OpenAI 及自定义模型接入，提供子智能体、Skills 技能包、Docker 沙箱 + Code Interpreter、MCP 工具生态、dify / n8n 外部工具平台、RAG 知识库、Human-in-the-Loop 审批等能力，执行过程以 SSE 结构化事件流实时回显前端。

  ---

## 演示

### 子代理（Subagent）+ MCP

子代理把多步任务委派到有独立上下文的子代理执行，可叠加 MCP 外部工具（如高德地图），任务进度实时可见：

![subagent](docs/sub-agent.png)

### TODO 任务清单（TodoWrite / Plan 模式）

复杂任务先落成 todo 列表，逐项推进、实时更新状态：

![todo-mode](docs/todo-mode.png)

### 贪吃蛇 Code Interpreter（沙箱跑代码）

模型生成代码 → 关进沙箱执行 → 产物导出为可预览/下载的 artifact：

![code-interpreter](docs/code.png)

![snake-game](docs/snake-game-code2.png)

### Human-in-the-Loop（工具审批）

命中 `Write`/`Edit`/`Bash` 白名单的工具调用会弹出审批，用户确认后才执行：

![human-in-loop](docs/human-in-loop.png)

### 外部工具平台（dify / n8n）

通过 `externalTools` + API 方式预留了外部工作流的集成，对接为 tools_call 调用：

![other-platform](docs/other-platform.png)

---

## 目录

- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [核心概念](#核心概念)
- [API 一览](#api-一览)
- [配置](#配置)
- [架构](#架构)
- [构建与部署](#构建与部署)

---

## 功能特性

| 特性 | 说明 |
|---|---|
| **多模型路由** | `modelName` 前缀路由到 Anthropic / DeepSeek / OpenAI / 自定义模型 |
| **深度思考** | `thinking: enabled/disabled` 统一映射到各家原生开关（Anthropic budget、DeepSeek `reasoningContent`、OpenAI `reasoningEffort`） |
| **服务端记忆** | `useServerMemory` 开启按 `sessionId` 持久化的服务端 ChatMemory，否则走无状态 `history[]` |
| **工具调用循环熔断** | `maxToolIterations` 限单轮工具调用总数，超限优雅收尾不打断 SSE |
| **Human-in-the-Loop** | 命中白名单的工具调用需 SSE 审批；`bypassApproval` 可按请求跳过 |
| **Skills 热插拔** | 请求级上传 zip 技能包，沙箱内执行脚本 |
| **子代理** | Claude 风格 `.md` 子代理 + 4 个内置（general-purpose / Explore / Plan / Bash） |
| **MCP 集成** | 按请求动态连接 MCP server，复用外部工具生态 |
| **外部工具平台** | 对接 dify / n8n 等平台，`platform`/`name`/`description`/`inputSchema`/`config` |
| **RAG 知识库** | pgvector 向量库 + 重排（rerank）+ 混合检索 |
| **可观察性** | `token` / `reasoning` / `tool_call` / `tool_result` / `approval_request` / `subagent_*` 事件流 |

---

## 快速开始

### 前置

- JDK 25+
- Docker（沙箱默认走 Docker，pgvector 向量库也需要）
- Maven 3.9+

### 1. 起依赖服务

> **数据库是可选的。** RAG 知识库是独立的接口（`/ai-api/ai-health-assistant/...`、`/ai-api/document-parser/...`），只有用到它时才需要起 pgvector；只跑对话 / 工具 / 沙箱不需要数据库。

```bash
# 可选：仅当使用 RAG 时启动 pgvector 向量库（5433）
cd docker/pgsql && docker compose up -d
```

### 2. 配置密钥

把真实 API key 放进环境变量，或新建 `src/main/resources/application.private.yaml`（已被 `.gitignore` 忽略）：

```yaml
spring:
  ai:
    anthropic:
      api-key: sk-ant-xxxxxxxx
    openai:
      api-key: sk-xxxxxxxx
    deepseek:
      api-key: sk-xxxxxxxx
```

> 仓库里所有 key 都是 `${XXX_API_KEY:sk-*******}` 占位，启动不报错，但实际调用需注入真 key。

### 3. 启动

```bash
# 开发（默认 profile=prod，本地切 dev）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或打包后跑 jar
mvn package -DskipTests
java -jar target/skills-tools-call-demo-1.0.0-SNAPSHOT.jar
```

启动后访问：

- SPA 前端：`http://localhost:8080/`（或 `http://localhost:8080/playground`）
- 内置 Skills / Agents 下载（开箱即用的技能包与子代理定义，目录见 `src/main/resources/public-download/`）：
  - `http://localhost:8080/ai-api/public/skills/code-interpreter.zip`
  - `http://localhost:8080/ai-api/public/skills/archify.zip`
  - `http://localhost:8080/ai-api/public/agents/code-reviewer.md`

> **关于前端**：前端暂时不开源——`src/main/resources/dist/` 下只有编译后的产物（`index.html` + `assets/…`），并非专业前端代码。这个 demo 后端其实只暴露一个对话接口（`/ai-api/chat/stream`，外加审批回填与下载），前端想自己用 AI / vibe coding 复刻一个界面很容易，对着 SSE 事件流渲染即可。

---

## 核心概念

### 深度思考（thinking）

`thinking` 字段统一为三态，后端按 provider 映射到原生开关：

| thinking | Anthropic | DeepSeek | OpenAI |
|---|---|---|---|
| `enabled` | `thinkingEnabled(10000)` + `maxTokens(16384)` | `thinking(ENABLED)` + `reasoningEffort(HIGH)` | `reasoningEffort("high")` |
| `disabled` | `thinkingDisabled()` | `thinking(DISABLED)` | `reasoningEffort("minimal")` |
| 不传 / 其他 | provider 默认 | provider 默认 | provider 默认 |

`modelName` 一律透传给上游；DeepSeek 的思考输出走独立 `reasoningContent` 字段，由 `ReasoningExtractor` 统一抽取。

### 服务端记忆（useServerMemory）

- `false`（默认）：无状态，调用方每次在 `history[]` 里带完整历史。
- `true`：服务端用 `MessageChatMemoryAdvisor` 按 `sessionId` 自管历史，`history[]` 被忽略。两种模式互斥。

### 工具迭代上限（maxToolIterations）

防工具调用死循环。`null`/`≤0` 保留官方默认（单工具 40 / 合计 150），`≥1` 收紧为本 turn 总次数上限。超限由 `ToolCallingAdvisor` 以 `finishReason=toolCallLimitExceeded` 优雅收尾，不打断 SSE。

### 对话历史（history）

对话历史有两种提供方式，二选一：

- **前端携带历史**：`history[]` 里带最近 N 条消息，服务端无状态。
- **服务端记忆**：设 `useServerMemory=true` 后，服务端按 `sessionId` 自管历史，**前端无需再携带 `history`**（携带也会被忽略）。

```jsonc
// useServerMemory=false（默认）时，前端每次带历史：
"history": [
  { "role": 1, "content": "..." },   // 1 = user
  { "role": 2, "content": "..." }    // 2 = assistant
]
```

### 系统提示词（system prompt）

请求体 `system` 字段为用户人设，与内置 `chat.prompt.system` 拼接（内置在前、用户在后），由 `SystemPromptComposer` 包成 XML 结构（`<context>` / `<builtin_rules>` / `<user_persona>`）。

### 工具上下文（toolContext）

请求体 `toolContext` 是键值对，注入工具而不进模型对话历史 / JSON Schema：

- 内置工具：以 `ToolContext` 参数可见；
- Skill 沙箱脚本：转成环境变量（key 转大写、非法字符转下划线），Python 里 `os.environ["API_KEY"]` 可读。

适合放 apiKey、tenantId 这类敏感/与请求绑定的字段。

### 自定义模型（mymodel）

有些模型服务商 Spring AI 没有现成 starter，可以**自己扩展接入 Spring AI**——本项目 `com.example.chat.mymodel` 就是一个完整示例：

- `MyModelChatModel implements ChatModel, StreamingChatModel` —— 把私有 HTTP 接口适配成 Spring AI 的模型抽象，实现 `call()`（同步）与 `stream()`（SSE 流式）两个方法即可；
- `MyModelApi` —— 私有协议 HTTP 客户端层，封装 `/call` / `/callStream` 端点；
- `MyModelChatOptions` / `MyModelProperties` / `MyModelConfig` —— 自定义选项与配置装配（`my-model.enabled=true` 开启）；
- 接入 `ModelRouter`：`modelName` 以 `qwen` / `glm` / `doubao` / `openai-compatible` / `my-` 等前缀开头即路由到自定义模型，可再加自己的前缀。

接好后整套 `ChatClient`（advisor、工具调用、记忆、HITL）都能复用，不用动其它代码。

---

## API 一览

统一前缀：`/ai-api`（由 `WebConfig.configurePathMatch` 给所有 `@RestController` 加）。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/ai-api/chat` | 同步对话，返回完整 `ChatResponse` （已弃用）|
| `POST` | `/ai-api/chat/stream` | 流式对话（SSE），返回 `ChatEvent` 事件流 |
| `POST` | `/ai-api/chat/approval` | HITL 审批回填（`requestId` + `decision`） |
| `GET` | `/ai-api/download/{id}/{filename}` | 下载 artifact（按 id 定位，inline 可预览类型直接渲染） |
| `GET` | `/ai-api/public/{*path}` | 公开资料下载（`src/main/resources/public-download/`） |
| `GET` | `/ai-api/ai-health-assistant/...` | RAG 助手 |
| `POST` | `/ai-api/document-parser/parse-and-save` | 文档解析入库 |

### 请求体关键字段（ChatRequest）

```jsonc
{
  "query": "...",                     // 必填
  "modelName": "deepseek-reasoner",   // 路由到 DeepSeek provider
  "thinking": "enabled",              // enabled / disabled / 省略
  "useServerMemory": true,            // 服务端记忆：开启后前端无需再携带 history
  "sessionId": "s-xxx",
  "userId": 1001,
  "assistantId": 7,
  "system": "你是...",                 // 用户人设
  "tools": ["TodoWrite", "WebSearch", "WebFetch"],   // 内置工具白名单
  "skills": [{ "name": "...", "url": "https://.../skill.zip" }],
  "subagents": [{ "name": "...", "url": "https://.../agent.md" }],
  "includeClaudeBuiltinSubagents": true,
  "mcpConfig": { "github": { "url": "...", "headers": {...} } },
  "externalTools": [{ "platform": "dify", "name": "...", "description": "...", "inputSchema": "{...}", "config": {...} }],
  "toolContext": { "apiKey": "sk-xxx" },
  "maxToolIterations": 25,
  "bypassApproval": false
  // "history": [...]                 // 仅 useServerMemory=false 时由前端携带；开启服务端记忆后无需携带
}
```

---

## 配置

关键配置项（`application.yaml`）：

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:sk-*******}
    openai:
      api-key: ${OPENAI_API_KEY:sk-*******}
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:sk-*******}

chat:
  hitl:
    timeout: 3m                    # 审批超时，到期按 DECLINE 兜底
    heartbeat-interval: 15s        # SSE 保活心跳
    required-tools:                # 需审批的工具白名单（@Tool name 严格相等）
      - Write
      - Edit
      - Bash

app:
  brave-search:
    api-key: ${BRAVE_API_KEY:sk-*******}     # WebSearch 工具
  smart-web-fetch:
    enabled: true                            # WebFetch 工具
  public-download:
    dir: classpath:/public-download/         # 内置 Skills / Agents 目录（打进 jar）
```

> 占位符语法：`${VAR:默认值}` —— 环境变量 `VAR` 存在就用它，否则用默认值。`sk-*******` 只是占位，能启动但调用会 401。

---

## 架构

```
ChatController (HTTP)
   └── ChatService.assembleSpec          按请求组装 ChatClient
         ├── ModelRouter                  modelName 前缀路由 provider
         ├── SystemPromptComposer         内置规则 + 用户人设 → XML system
         ├── 工具装配                     沙箱 / 内置 / Skills / MCP / 外部工具 / FinalAnswer
         ├── HitlToolCallingGate          HITL 审批 gate（唯一事实源）
         └── ObservableToolCallingManager 工具结果旁路到 SSE sink
```

- **流式管线**：`Flux.using`（资源分配→业务流→释放）+ `mergeWith(toolEventSink)` + `takeUntilOther(deadline)`，5 分钟绝对上限防死循环。
- **工具事件旁路**：Spring AI 2.0 GA 已硬过滤流式 chunk 里的 `hasToolCalls()`，所以 `tool_call`/`tool_result` 由 `ObservableToolCallingManager.executeToolCalls` 在工具执行前后旁路 emit，不走 delta chunk。
- **HITL 单一事实源**：`HitlToolCallingGate` 负责整个 gate（gateApprovals / awaitDecision / fail-safe），主 agent 与子代理通过 `HitlEventFactory` 薄适配器复用同一套逻辑。

---

## 构建与部署

```bash
mvn package -DskipTests
java -jar target/hermes-agent-demo-1.0.0-SNAPSHOT.jar
```

- SPA 产物在 `src/main/resources/dist/`，打进 jar，开发与 jar 启动都能访问。
- 公开资料在 `src/main/resources/public-download/`，同样打进 jar。
- 生产想改公开资料而不重新打包：把 `app.public-download.dir` 设成磁盘绝对路径即可。

### Native Image（GraalVM）

部署场景推荐打 **native 镜像**（启动毫秒级、内存占用低），参考 Spring Boot 官方指南：
[Developing Your First GraalVM Native Application](https://docs.spring.io/spring-boot/how-to/native-image/developing-your-first-application.html)。

```bash
mvn -Pnative native:compile
./target/hermes-agent-demo
```

### 关于容器化部署（⚠️ 暂不支持 Docker-in-Docker）

本项目沙箱基于 `agent-sandbox-docker` —— 代码解释器 / Skills 脚本会由应用**在宿主机 Docker 里再拉起容器**执行。这意味着：

- **不能把 hermes-agent-demo 本身再放进 Docker 里跑**，否则就是 Docker-in-Docker 嵌套虚拟化，当前不支持。
- 若必须容器启动，需要改造沙箱实现（换用非 Docker 的 sandbox 后端），例如：
  - **[CubeSandbox](https://github.com/TencentCloud/CubeSandbox)** —— 腾讯云开源的 AI Agent 沙箱，RustVMM + KVM 硬件级隔离，亚秒级冷启动，API 兼容 E2B；
  - **[E2B](https://e2b.dev/)** —— 商业托管沙箱服务，Firecracker microVM 隔离，开箱即用；
  - **[OpenSandbox](https://github.com/alibaba/OpenSandbox)** —— 阿里开源的通用 AI 应用沙箱平台，支持 Docker / Kubernetes / gVisor / Firecracker 运行时，提供多语言 SDK（含 Java）。

> **不需要沙箱的部署形态**：可以把 `SandboxGlobTool` 换成上游的 `org.springaicommunity.agent.tools.GlobTool` 实现（即不启用代码解释器 / Skills 沙箱执行），此时应用可直接容器化运行。

### 多节点部署注意（Sticky Sessions）

项目默认的本地沙箱是**有状态**的：`SandboxSessionManager` 按 `(userId, assistantId, sessionId)` 在节点内存里缓存复用同一个沙箱，同一对话的多轮请求共享其中的文件与执行状态。因此多副本部署时，必须保证同一对话的请求始终路由到同一节点。

建议用 `assistantId + sessionId` 做 Nginx 的 Sticky Sessions（会话保持）策略：

```nginx
upstream hermes_backend {
    # 以 assistantId + sessionId 的组合作为粘性 key
    hash "$arg_assistantId:$arg_sessionId" consistent;
    server 10.0.0.1:8080;
    server 10.0.0.2:8080;
}
```

> 若 ID 放在请求体（JSON）而非 query string，Nginx 原生 `hash` 读不到，需要改用 cookie 型 sticky 策略（`sticky cookie` / `ip_hash`），或由网关层把 ID 提到 header 再按 header 哈希。

---

## 参考

- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/index.html) — 官方参考文档
- [Subagent（Task/子代理）](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) — Spring AI 官方子代理模式介绍
- [Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills) — Spring AI 官方 Skills 思路
- [Spring AI Community](https://github.com/spring-ai-community) — 本项目用到的 `agent-sandbox`、`spring-ai-agent-utils` 等社区库
- [JavaClaw / ClawRunr](https://clawrunr.io/) — 后台长任务、工作空间与记忆（待实现）的参考实现
