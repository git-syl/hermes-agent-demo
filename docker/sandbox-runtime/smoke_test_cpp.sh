#!/usr/bin/env bash
# Smoke test: can this image compile & run C/C++ the way a 编程题目 workflow
# needs (compile, run, stdin, STL, valgrind)?
#
# Mount-free runner — pipes the script to bash inside the container, so no
# Windows<->Linux path-translation headaches:
#   docker run --rm -i mmdjzm/sandbox-runtime:1.1.0 bash -s \
#       < docker/sandbox-runtime/smoke_test_cpp.sh
set -uo pipefail

pass=0; fail=0
ok()  { echo "  ok:   $1"; pass=$((pass+1)); }
bad() { echo "  FAIL: $1"; fail=$((fail+1)); }
section() { echo; echo "== $1 =="; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

section "toolchain versions"
gcc --version | head -1
g++ --version | head -1
valgrind --version

section "C: compile + run"
cat > hello.c <<'EOF'
#include <stdio.h>
int main(void) { printf("Hello, C!\n"); return 0; }
EOF
gcc hello.c -o hello_c \
  && [ "$(./hello_c)" = "Hello, C!" ] \
  && ok "gcc compile + run" \
  || bad "gcc compile/run"

section "C: stdin (a+b)"
cat > ab.c <<'EOF'
#include <stdio.h>
int main(void) {
    int a, b;
    if (scanf("%d %d", &a, &b) != 2) return 1;
    printf("%d\n", a + b);
    return 0;
}
EOF
gcc ab.c -o ab \
  && [ "$(echo '2 3' | ./ab)" = "5" ] \
  && ok "scanf from stdin" \
  || bad "C stdin a+b"

section "C++: compile + run"
cat > hello.cpp <<'EOF'
#include <iostream>
int main() { std::cout << "Hello, C++!\n"; return 0; }
EOF
g++ hello.cpp -o hello_cxx \
  && [ "$(./hello_cxx)" = "Hello, C++!" ] \
  && ok "g++ compile + run" \
  || bad "g++ compile/run"

section "C++: STL vector/sort + stdin"
cat > sort.cpp <<'EOF'
#include <iostream>
#include <vector>
#include <sstream>
#include <algorithm>
int main() {
    std::string line;
    std::getline(std::cin, line);
    std::istringstream iss(line);
    std::vector<int> v;
    int x;
    while (iss >> x) v.push_back(x);
    std::sort(v.begin(), v.end());
    for (size_t i = 0; i < v.size(); ++i) { if (i) std::cout << ' '; std::cout << v[i]; }
    std::cout << '\n';
    return 0;
}
EOF
g++ sort.cpp -o sort_cxx \
  && [ "$(echo '5 3 8 1 9' | ./sort_cxx)" = "1 3 5 8 9" ] \
  && ok "vector/sort + cin" \
  || bad "C++ STL sort"

section "valgrind: clean run, 0 errors"
cat > vgc.c <<'EOF'
#include <stdio.h>
#include <stdlib.h>
int main(void) {
    int *p = malloc(sizeof(int));
    if (!p) return 2;
    *p = 42;
    printf("%d\n", *p);
    free(p);
    return 0;
}
EOF
gcc -g vgc.c -o vgc
vg_out="$(valgrind --error-exitcode=99 --leak-check=full ./vgc 2>&1)"
echo "$vg_out" | grep -q "ERROR SUMMARY: 0 errors" \
  && ok "valgrind reports 0 errors" \
  || { echo "$vg_out" | tail -5; bad "valgrind reported errors"; }

echo
echo "============================="
echo "  pass=$pass  fail=$fail"
echo "============================="
[ "$fail" -eq 0 ] && { echo "SMOKE TEST PASSED"; exit 0; } || { echo "SMOKE TEST FAILED"; exit 1; }
