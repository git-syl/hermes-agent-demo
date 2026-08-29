# Sandbox Runtime Image

如果你需要自定义镜像请参考这个示例：  

基于 `ghcr.io/spring-ai-community/agents-runtime` 扩展，给 `python-matplotlib` 等绘图类 skill 提供运行环境。

## 锁定的 Base 版本

为了避免上游 `:latest` 浮动带来的不可复现构建，Dockerfile 的 `FROM` 用 **digest 引用**而不是 tag：

| 字段 | 值 |
|---|---|
| Digest（不变） | `sha256:0f136fc9abc20f448f6b0739e53e6a88b1b4e2ecf0ae15d82ef37bbd82f87a61` |
| 等价 commit-SHA tag | `:62fec6661b5b9eccfd705de671dde435076976be` |
| 上游发布时间 | 2024-08（3 个月前的 latest） |
| 上游 Tags 页面 | <https://github.com/orgs/spring-ai-community/packages/container/agents-runtime/versions> |

升级时把上面 Tags 页面新出的 digest 复制下来，替换 `Dockerfile` 的 `FROM @sha256:...` 那一行，重新 build + push 一个新的日期 tag（如 `:2024-12-01`），改 `application.yaml` 切过去，验证 OK 后再灰度。

## 内容

在上游 base 之上加了：

- **CJK 字体**：`fonts-noto-cjk`（~60 MB，覆盖 GB18030 / JIS / KS，解决中文标题豆腐块问题。没装 `-extra` 那 ~145 MB 的变体字补充集，图表用不到，去掉能让镜像变小、构建在网络不稳的环境里也不会失败）
- **Python 绘图栈**：`matplotlib`、`numpy`、`pandas`、`seaborn`、`pillow`
- **C/C++ 工具链**：`build-essential`（gcc / g++ / make + libc 头）、`gdb`、`valgrind` —— 给 C/C++ 编程题目用。没装 `clang`（带 LLVM 太大）和 `cmake`（单文件题目用不上），要的话在 Dockerfile 的 apt install 列表里加一行即可
- **matplotlib font cache 预热**：避免首次绘图请求慢 3–5 秒
- **可配置镜像源**：apt / pip 源通过 `--build-arg` 切换，默认走官方源

**没动**：`ENTRYPOINT` (`/__cacert_entrypoint.sh`)、`CMD` (`sleep infinity`)、`WORKDIR` (`/work`)、`USER` (`agent`)。这些是 `DockerSandbox` 依赖的契约，改了沙箱起不来。

## 构建

> 约定：`<user>` 替换为你的 Docker Hub 用户名（例如 `mmdjzm`），`<date>` 用构建当天日期，例如 `2024-11-10`。**生产部署不要用 `:latest`**，永远用固定日期 tag，方便回滚。

### 默认（走官方源）

```bash
# 在项目根目录
docker build \
    -t <user>/sandbox-runtime:<date> \
    -t <user>/sandbox-runtime:latest \
    docker/sandbox-runtime/
```

### 国内构建（强烈推荐，走清华源）

国内直接拉 `archive.ubuntu.com` / `pypi.org` 几乎必慢，且如果本机开了 Clash / Mihomo 等代理软件（fake-IP `198.18.0.0/15`），Docker 容器流量经常被代理拦截后超时。用 `--build-arg` 把 apt / pip 都切到国内镜像，彻底绕开：

```bash
docker build \
    --build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn \
    --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    -t <user>/sandbox-runtime:<date> \
    -t <user>/sandbox-runtime:latest \
    docker/sandbox-runtime/
```

| Build arg | 默认 | 国内可选值 |
|---|---|---|
| `APT_MIRROR` | 空（用 `archive.ubuntu.com`） | `mirrors.tuna.tsinghua.edu.cn`、`mirrors.aliyun.com`、`mirrors.ustc.edu.cn`、`mirrors.163.com` |
| `PIP_INDEX_URL` | 空（用 `pypi.org`） | `https://pypi.tuna.tsinghua.edu.cn/simple`、`https://mirrors.aliyun.com/pypi/simple/`、`https://pypi.mirrors.ustc.edu.cn/simple/` |

> Dockerfile 默认值留空就是为了不写死镜像源，海外构建保持上游行为；只在 build 时用 `--build-arg` 切换。

### 网络仍然抽风？

如果换源后还是失败，多半是 Docker Desktop 没绕开本机代理。两条路任选：

1. **Docker Desktop → Settings → Resources → Proxies** 里关掉 "Use system proxy"，或者明确填写代理地址。
2. **代理软件**（Clash / Mihomo / Surge）里给 `mirrors.tuna.tsinghua.edu.cn`、`pypi.tuna.tsinghua.edu.cn`、`*.docker.io` 加直连规则。

构建结束时 Dockerfile 末尾的 sanity check 会跑一遍 `import matplotlib, numpy, pandas, seaborn, PIL`，import 失败会让构建直接 fail，不会留下一个坏镜像。

## 验证

### 基础 import 检查

```bash
docker run --rm <user>/sandbox-runtime:<date> \
    python3 -c "import matplotlib, numpy, pandas; print(matplotlib.__version__)"
# → 3.10.x（或当前 pip 拉到的版本）

# 顺手验证中文字体也装上了
docker run --rm <user>/sandbox-runtime:<date> \
    bash -lc "fc-list :lang=zh | head -3"
# → 能看到 Noto Sans CJK 相关条目
```

### CJK 渲染严格 smoke test

`fc-list` 只能证明字体文件存在，证明不了 matplotlib 能找到它（OTC family-name 经常对不上）。仓库自带 `smoke_test_cjk.py` 跑一遍真实渲染，并把 matplotlib 的 `missing-glyph` UserWarning 升级成 error —— 渲染失败立刻退非零。每次升级镜像后建议跑一次：

```bash
docker run --rm \
    -v "$(pwd)/docker/sandbox-runtime/smoke_test_cjk.py:/tmp/smoke.py:ro" \
    <user>/sandbox-runtime:<date> \
    python3 /tmp/smoke.py
# 期望输出：
#   picked font -> Noto Sans CJK JP
#   OK: no missing-glyph warnings
```

> 注意 family 名是 **`Noto Sans CJK JP`** 而不是 SC ——`fonts-noto-cjk` 这个 deb 装的是 OTC 合并文件，matplotlib 登记的是第一个 sub-face（JP）的名字，但它本身包含全套简中字形，渲染简体中文没问题。`Ref/skills/python-matplotlib/SKILL.md` 里的字体备选列表已按此顺序排好。

### C/C++ 工具链检查

```bash
docker run --rm <user>/sandbox-runtime:<date> \
    bash -lc 'echo "int main(){return puts(\"hello\");}" | gcc -xc - -o /tmp/t && /tmp/t && g++ --version | head -1 && valgrind --version'
# → hello / g++ (Ubuntu 11.x.x) / valgrind-3.18.x
```

> 构建时 Dockerfile 末尾已经跑过一遍 gcc/g++ 编译+运行，toolchain 坏了构建会直接 fail。

## 推到 Docker Hub

```bash
docker login docker.io          # 第一次：用 Personal Access Token 当密码

docker push <user>/sandbox-runtime:<date>
docker push <user>/sandbox-runtime:latest
```

推完后看一眼远端 manifest，确认 digest 已经登记到自家仓库：

```bash
docker image inspect <user>/sandbox-runtime:<date> --format "{{index .RepoDigests 0}}"
```

把这个返回的 `<user>/sandbox-runtime@sha256:...` 记到部署 changelog 里，万一以后 tag 被覆盖也能按 digest 拉回去。

## 切换到这个镜像

在 `src/main/resources/application.yaml` 里加一行 `chat.sandbox.image`：

```yaml
chat:
  sandbox:
    mode: docker
    image: docker.io/<user>/sandbox-runtime:<date>   # 用固定日期 tag，不用 :latest
```

或者通过环境变量覆盖（更适合不同环境用不同镜像）：

```bash
export CHAT_SANDBOX_IMAGE=docker.io/<user>/sandbox-runtime:<date>
```

Spring Boot 的 `@ConfigurationProperties("chat.sandbox")` 会把 `CHAT_SANDBOX_IMAGE` 自动映射到 `SandboxProperties.image`。

## 体积

构建完一般在 1.4–1.7 GB（base ~700MB + CJK 字体 ~60MB + numpy/pandas/matplotlib/seaborn ~500MB + C/C++ 工具链 ~150MB）。如果还要更小，可以：

- 去掉 `seaborn`（如果只用 matplotlib 原生 API）
- 让 matplotlib 自己拉 `pillow`，不显式 pin
- 用 `pip3 install --no-deps` 手动控制传递依赖

## 常用命令

docker build -t sandbox-runtime . --build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple


docker tag sandbox-runtime mmdjzm/sandbox-runtime:1.0.0

docker push mmdjzm/sandbox-runtime:1.0.0
