#!/usr/bin/env bash
# ============================================================
# 快速编译检查 —— 不启动 Gradle 守护进程
#
# 用途：五路并行开发时，每路在自己的 worktree 里反复改代码。用 ./gradlew 检查的话
# 五个 daemon 同时驻留会吃掉 7–8G 内存（本机空闲只有 4G），而且共享 ~/.gradle 的
# 缓存锁会互相等待。这个脚本直接调 javac，一次全量编译约 3 秒、零常驻内存。
#
# 用法：
#   tools/fast-compile.sh            # 编译到 /tmp/orbcraft-fast/<worktree 名>
#   tools/fast-compile.sh <输出目录>  # 指定输出目录
#
# 前置：类路径由 `./gradlew -q printCompileClasspath` 导出一次，默认读
#       /tmp/orbcraft-cp.txt。worktree 之间共用同一份 —— 里面的 jar 全在
#       ~/.gradle 全局缓存里，路径与项目目录无关。
#
# 边界：只验「编译得过」。资源处理（processResources 展开 ${version}）、
#       jar 打包、Loom 的 remap 都不在覆盖范围内，合并回主线后必须再跑
#       ./gradlew build 与 tools/verify-all.sh。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP_FILE="${ORBCRAFT_CP_FILE:-/tmp/orbcraft-cp.txt}"

if [ ! -s "$CP_FILE" ]; then
    echo "缺少编译类路径文件：$CP_FILE" >&2
    echo "在仓库根目录执行一次：" >&2
    echo "  ./gradlew -q printCompileClasspath | tail -1 > $CP_FILE" >&2
    exit 2
fi

if ! command -v javac >/dev/null 2>&1; then
    echo "找不到 javac。本项目要求 JDK 21（见 gradle.properties 的 java_version）。" >&2
    exit 2
fi

OUT="${1:-/tmp/orbcraft-fast/$(basename "$ROOT")}"
rm -rf "$OUT"
mkdir -p "$OUT"

SRC_DIRS=()
for d in "$ROOT/core/src/main/java" "$ROOT/fabric/src/main/java"; do
    [ -d "$d" ] && SRC_DIRS+=("$d")
done
if [ ${#SRC_DIRS[@]} -eq 0 ]; then
    echo "找不到源码目录（core/src/main/java 或 fabric/src/main/java）" >&2
    exit 2
fi

# 用 -Xlint:all 与 Gradle 侧保持同一套告警口径；告警不阻断（Gradle 也不阻断），
# 但会打出来 —— 合并前请把新增告警清干净，否则主线构建会变吵。
#
# -J-Duser.language=en：javac 的诊断信息跟随 JVM 默认 locale，Windows 中文环境下
# 会输出 GBK 中文，在 Git Bash 里就是一堆乱码，读不了。
# -Xlint:-options：抑制「注解处理未显式声明」这条与代码质量无关的提示。
javac -J-Duser.language=en -J-Duser.country=US \
      --release 21 -encoding UTF-8 -Xlint:all,-options \
      -cp "$(cat "$CP_FILE")" -d "$OUT" \
      $(find "${SRC_DIRS[@]}" -name '*.java')

STATUS=$?
if [ $STATUS -eq 0 ]; then
    echo "编译通过（javac，输出 $OUT）"
else
    echo "编译失败（exit=$STATUS）" >&2
fi
exit $STATUS
