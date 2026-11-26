# Sandbox Runtime Image

[中文](README.md) | **English**

Use this example as a reference when you need to customize the image.

This image extends `ghcr.io/spring-ai-community/agents-runtime` with a runtime environment for skills such as `python-matplotlib` and other plotting-related skills.

## Pinned Base Version

To keep builds reproducible and avoid the moving target behind the upstream `:latest` tag, the Dockerfile uses a **digest reference** instead of a tag in its `FROM` instruction.

| Field | Value |
|---|---|
| Immutable digest | `sha256:0f136fc9abc20f448f6b0739e53e6a88b1b4e2ecf0ae15d82ef37bbd82f87a61` |
| Equivalent commit-SHA tag | `:62fec6661b5b9eccfd705de671dde435076976be` |
| Upstream release | 2024-08, the `latest` version from three months earlier |
| Upstream Tags page | <https://github.com/orgs/spring-ai-community/packages/container/agents-runtime/versions> |

When upgrading, copy the new digest from the Tags page above, replace the `Dockerfile` line containing `FROM @sha256:...`, and build and push a new date tag such as `:2024-12-01`. Update `application.yaml`, switch over to the new image, and verify it before gradually rolling it out.

## Contents

The image adds the following components on top of the upstream base image.

- **CJK fonts**: `fonts-noto-cjk` (~60 MB), covering GB18030, JIS, and KS and preventing missing-glyph boxes in Chinese titles. The `-extra` package, which adds roughly 145 MB of variant glyphs, is not installed because charts do not need it. This keeps the image smaller and avoids build failures on unstable networks.
- **Python plotting stack**: `matplotlib`, `numpy`, `pandas`, `seaborn`, and `pillow`.
- **C/C++ toolchain**: `build-essential` (gcc, g++, make, and libc headers), `gdb`, and `valgrind` for C/C++ programming tasks. `clang` is not installed because LLVM is large, and `cmake` is not installed because single-file tasks do not need it. Add either package to the `apt install` list in the Dockerfile if required.
- **Warmed matplotlib font cache**: avoids a 3–5 second delay on the first plotting request.
- **Configurable package mirrors**: switch the apt and pip sources through `--build-arg`. The default uses the official sources.

The following runtime contract is preserved: `ENTRYPOINT` (`/__cacert_entrypoint.sh`), `CMD` (`sleep infinity`), `WORKDIR` (`/work`), and `USER` (`agent`). `DockerSandbox` depends on these values, so changing them prevents the sandbox from starting.

## Build

> Convention: replace `<user>` with your Docker Hub username, such as `mmdjzm`, and `<date>` with the build date, such as `2024-11-10`. **Do not use `:latest` in production.** Always use a fixed date tag so that you can roll back easily.

### Default build using official sources

```bash
# Run from the project root
docker build \
    -t <user>/sandbox-runtime:<date> \
    -t <user>/sandbox-runtime:latest \
    docker/sandbox-runtime/
```

### Build in mainland China using Tsinghua mirrors

Pulling directly from `archive.ubuntu.com` and `pypi.org` in mainland China can be extremely slow. If Clash, Mihomo, or another proxy is enabled locally and uses the fake-IP range `198.18.0.0/15`, Docker traffic can also time out after being intercepted by the proxy. Use `--build-arg` to switch both apt and pip to mainland China mirrors.

```bash
docker build \
    --build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn \
    --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    -t <user>/sandbox-runtime:<date> \
    -t <user>/sandbox-runtime:latest \
    docker/sandbox-runtime/
```

| Build argument | Default | Mainland China options |
|---|---|---|
| `APT_MIRROR` | Empty, using `archive.ubuntu.com` | `mirrors.tuna.tsinghua.edu.cn`, `mirrors.aliyun.com`, `mirrors.ustc.edu.cn`, `mirrors.163.com` |
| `PIP_INDEX_URL` | Empty, using `pypi.org` | `https://pypi.tuna.tsinghua.edu.cn/simple`, `https://mirrors.aliyun.com/pypi/simple/`, `https://pypi.mirrors.ustc.edu.cn/simple/` |

> The Dockerfile leaves these values empty by default so that the mirror is not hard-coded and builds outside mainland China retain the upstream behavior. Set them with `--build-arg` only when you need to switch mirrors during a build.

### If the network is still unstable

If the build still fails after switching mirrors, Docker Desktop may still be routing traffic through the local proxy. You can use either of these approaches.

1. In **Docker Desktop → Settings → Resources → Proxies**, disable **Use system proxy** or enter the proxy address explicitly.
2. In Clash, Mihomo, or Surge, add direct-connection rules for `mirrors.tuna.tsinghua.edu.cn`, `pypi.tuna.tsinghua.edu.cn`, and `*.docker.io`.

At the end of the build, the Dockerfile runs a sanity check that imports `matplotlib`, `numpy`, `pandas`, `seaborn`, and `PIL`. An import failure makes the build fail immediately instead of leaving a broken image behind.

## Verification

### Basic import check

```bash
docker run --rm <user>/sandbox-runtime:<date> \
    python3 -c "import matplotlib, numpy, pandas; print(matplotlib.__version__)"
# → 3.10.x, or the version currently fetched from pip

# Also verify that the CJK fonts are installed
docker run --rm <user>/sandbox-runtime:<date> \
    bash -lc "fc-list :lang=zh | head -3"
# → Should show entries related to Noto Sans CJK
```

### Strict CJK rendering smoke test

`fc-list` only proves that the font files exist. It does not prove that matplotlib can find them because OTC family names often do not match. The repository includes `smoke_test_cjk.py` for a real rendering test. It turns matplotlib `missing-glyph` `UserWarning` messages into errors, so a rendering failure immediately returns a non-zero exit code. Run it after upgrading the image.

```bash
docker run --rm \
    -v "$(pwd)/docker/sandbox-runtime/smoke_test_cjk.py:/tmp/smoke.py:ro" \
    <user>/sandbox-runtime:<date> \
    python3 /tmp/smoke.py
# Expected output:
#   picked font -> Noto Sans CJK JP
#   OK: no missing-glyph warnings
```

> The family name is **`Noto Sans CJK JP`**, not SC. The `fonts-noto-cjk` Debian package installs an OTC merged file, and matplotlib registers the first sub-face under the JP name. The file still contains the complete set of Simplified Chinese glyphs, so Simplified Chinese renders correctly. The fallback font list in `Ref/skills/python-matplotlib/SKILL.md` follows this order.

### C/C++ toolchain check

```bash
docker run --rm <user>/sandbox-runtime:<date> \
    bash -lc 'echo "int main(){return puts(\"hello\");}" | gcc -xc - -o /tmp/t && /tmp/t && g++ --version | head -1 && valgrind --version'
# → hello / g++ (Ubuntu 11.x.x) / valgrind-3.18.x
```

> The Dockerfile already compiles and runs a small gcc/g++ program at the end of the build. A broken toolchain makes the build fail immediately.

## Push to Docker Hub

```bash
docker login docker.io          # First time: use a Personal Access Token as the password

docker push <user>/sandbox-runtime:<date>
docker push <user>/sandbox-runtime:latest
```

After pushing, inspect the remote manifest and confirm that the digest has been registered in your repository.

```bash
docker image inspect <user>/sandbox-runtime:<date> --format "{{index .RepoDigests 0}}"
```

Record the returned `<user>/sandbox-runtime@sha256:...` value in the deployment changelog. If the tag is overwritten later, you can still pull the image by digest.

## Switch to This Image

Add `chat.sandbox.image` to `src/main/resources/application.yaml`.

```yaml
chat:
  sandbox:
    mode: docker
    image: docker.io/<user>/sandbox-runtime:<date>   # Use a fixed date tag instead of :latest
```

You can also override it with an environment variable, which is more convenient when different environments use different images.

```bash
export CHAT_SANDBOX_IMAGE=docker.io/<user>/sandbox-runtime:<date>
```

Spring Boot's `@ConfigurationProperties("chat.sandbox")` automatically maps `CHAT_SANDBOX_IMAGE` to `SandboxProperties.image`.

## Image Size

The final image is typically 1.4–1.7 GB. This includes the roughly 700 MB base image, 60 MB of CJK fonts, 500 MB for numpy, pandas, matplotlib, and seaborn, and 150 MB for the C/C++ toolchain. To make it smaller, you can:

- Remove `seaborn` if you only use the native matplotlib API.
- Let matplotlib install `pillow` itself instead of pinning it explicitly.
- Use `pip3 install --no-deps` and manage transitive dependencies manually.

## Common Commands

```bash
docker build -t sandbox-runtime . --build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

docker tag sandbox-runtime mmdjzm/sandbox-runtime:1.0.0

docker push mmdjzm/sandbox-runtime:1.0.0
```
