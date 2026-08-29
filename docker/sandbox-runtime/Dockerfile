# Sandbox runtime image with matplotlib + CJK fonts on top of the
# spring-ai-community agents-runtime base.
#
# Base: ghcr.io/spring-ai-community/agents-runtime
#   pinned by digest (immutable; immune to upstream :latest drift):
#     sha256:0f136fc9abc20f448f6b0739e53e6a88b1b4e2ecf0ae15d82ef37bbd82f87a61
#   equivalent commit-SHA tag (informational only):
#     :62fec6661b5b9eccfd705de671dde435076976be
#   - Ubuntu 22.04, Python 3.10.12
#   - non-root user `agent` (uid=1000)
#   - WORKDIR /work
#   - ENTRYPOINT /__cacert_entrypoint.sh, CMD sleep infinity
#
# Upgrade procedure: pick a new digest from
#   https://github.com/orgs/spring-ai-community/packages/container/agents-runtime/versions
# then replace the @sha256:... below. Bump the image tag (e.g. 2024-11-10 -> 2024-12-01)
# when you push to your registry so deployments can roll back cleanly.
#
# IMPORTANT: do not override ENTRYPOINT / CMD / WORKDIR / USER at the end —
# DockerSandbox keeps the container alive via `sleep infinity` and runs each
# tool invocation through `docker exec`. Changing those breaks the sandbox.

FROM ghcr.io/spring-ai-community/agents-runtime@sha256:0f136fc9abc20f448f6b0739e53e6a88b1b4e2ecf0ae15d82ef37bbd82f87a61

# ---- root layer: apt + pip ----------------------------------------------
USER root

# Mirror knobs — let the user redirect apt / pip to a closer mirror at build
# time without editing the Dockerfile. Defaults preserve upstream behaviour.
#
# Common values for CN users:
#   --build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn
#   --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG APT_MIRROR=""
ARG PIP_INDEX_URL=""

# Rewrite Ubuntu apt sources to ${APT_MIRROR} when provided. Covers both the
# archive (main / updates / backports) and security pockets, since modern
# mirrors host both under the same host.
RUN if [ -n "${APT_MIRROR}" ]; then \
        sed -i \
            -e "s|http://archive.ubuntu.com/ubuntu|http://${APT_MIRROR}/ubuntu|g" \
            -e "s|http://security.ubuntu.com/ubuntu|http://${APT_MIRROR}/ubuntu|g" \
            /etc/apt/sources.list; \
        echo "Using apt mirror: ${APT_MIRROR}"; \
    fi

# CJK fonts so matplotlib renders Chinese/Japanese/Korean labels instead of
# tofu boxes. fonts-noto-cjk (~60MB) covers GB18030 / JIS / KS, which is
# enough for chart titles. fonts-noto-cjk-extra (~145MB) adds rare historical
# variants we don't need; skipped on purpose to keep the image small and the
# build resilient to flaky networks.
# DEBIAN_FRONTEND=noninteractive avoids tzdata prompts during build.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# C/C++ toolchain for programming exercises (编程题目). build-essential pulls in
# gcc / g++ / make plus the libc-dev headers (the minimum to compile a single
# source file); gdb for stepping through buggy solutions; valgrind for the
# memory-leak / out-of-bounds checks that come up in C courses. clang is left
# out on purpose — it drags in LLVM (~150MB); add it to the list below if a
# course needs its diagnostics. cmake is skipped too (single-file problems
# don't need a build system).
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        build-essential \
        gdb \
        valgrind \
    && rm -rf /var/lib/apt/lists/*

# Plotting + numeric stack. --no-cache-dir keeps the layer small.
# Pinned to current-stable majors; bump as needed.
# PIP_INDEX_URL is an ARG above but pip also reads it from env, so exporting
# it inline only when set keeps the default install path identical.
RUN if [ -n "${PIP_INDEX_URL}" ]; then export PIP_INDEX_URL="${PIP_INDEX_URL}"; fi \
    && pip3 install --no-cache-dir \
        "numpy>=1.26,<3"       \
        "pandas>=2.2,<3"       \
        "matplotlib>=3.8,<4"   \
        "seaborn>=0.13,<1"     \
        "pillow>=10,<12"

# Prime matplotlib's font cache as the runtime user so the first chart
# request in a fresh container doesn't pay the rebuild cost (~3-5s) and
# also picks up the CJK fonts we just installed.
USER agent
RUN python3 -c "import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt; plt.figure(); plt.close('all')"

# Sanity check: fail the build if any of the imports we promise are broken.
RUN python3 -c "import matplotlib, numpy, pandas, seaborn, PIL; print('runtime ok:', matplotlib.__version__, numpy.__version__, pandas.__version__)"

# C/C++ sanity check: compile + run a tiny program as the runtime user so a
# broken toolchain fails the build instead of failing the first request.
RUN printf '%s\n' '#include <stdio.h>' 'int main(void){puts("c ok");return 0;}' > /tmp/hello.c \
    && printf '%s\n' '#include <iostream>' 'int main(){std::cout<<"cxx ok\\n";return 0;}' > /tmp/hello.cpp \
    && gcc /tmp/hello.c -o /tmp/hello_c && /tmp/hello_c \
    && g++ /tmp/hello.cpp -o /tmp/hello_cxx && /tmp/hello_cxx \
    && rm -f /tmp/hello.c /tmp/hello.cpp /tmp/hello_c /tmp/hello_cxx

# Leave USER=agent, WORKDIR=/work, ENTRYPOINT, CMD untouched (inherited).
