#!/usr/bin/env bash
# 集成验收：编译 + 全部自测 + 数据护栏
# 用法: bash tools/verify-all.sh [仓库根]
set -uo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
OUT="${TMPDIR:-/tmp}/oc_verify_all"
cd "$ROOT" || exit 1

echo "=== 仓库: $ROOT ==="
echo "=== 1. 编译（-Xlint:all，零警告为通过）==="
rm -rf "$OUT" && mkdir -p "$OUT"
javac --release 21 -encoding UTF-8 -Xlint:all -d "$OUT" \
    $(find core/src/main/java core/src/test/java -name "*.java") 2>&1 | tee /tmp/oc_javac.log
WARN=$(wc -l < /tmp/oc_javac.log)
if [ "$WARN" -ne 0 ]; then
    echo "编译告警/错误 $WARN 行 → 不通过"
    exit 1
fi
echo "编译零告警"
cp -r core/src/main/resources/. "$OUT"/

echo ""
echo "=== 2. 自测 ==="
FAIL=0
for cls in \
    com.orbcraft.core.draft.DraftSessionSelfTest \
    com.orbcraft.core.render.RenderContractSelfTest \
    com.orbcraft.core.settle.SettleSelfTest \
    com.orbcraft.core.build.BuildSelfTest \
    com.orbcraft.core.target.TargetSelfTest \
    com.orbcraft.core.render.CarrierModelSelfTest \
    com.orbcraft.core.hatch.HatchPipelineSelfTest \
    com.orbcraft.core.integration.TargetWiringSelfTest \
    com.orbcraft.core.integration.PersistenceSelfTest
do
    short=$(echo "$cls" | sed 's/com.orbcraft.core.//')
    out=$(java -cp "$OUT" -Dstdout.encoding=UTF-8 "$cls" 2>&1)
    rc=$?
    line=$(echo "$out" | grep -E "^通过" | tail -1)
    if [ $rc -ne 0 ] || [ -z "$line" ]; then
        echo "  ✗ $short  (退出码 $rc)"
        echo "$out" | grep -E "✗" | head -5
        FAIL=1
    else
        printf "  ✓ %-40s %s\n" "$short" "$line"
    fi
done

echo ""
echo "=== 3. 数据护栏 ==="
NODE="C:/Users/gaoji/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"
if [ -x "$NODE" ] || command -v "$NODE" >/dev/null 2>&1; then
    "$NODE" tools/validate-cards.mjs 2>&1 | tail -3
else
    echo "（未找到 node，跳过）"
fi

echo ""
echo "=== 4. 契约冻结（fabric/contract 只允许加方法）==="
if command -v python >/dev/null 2>&1; then
    python tools/contract-freeze.py > /tmp/oc_contract.log 2>&1
    if [ $? -ne 0 ]; then
        cat /tmp/oc_contract.log
        FAIL=1
    else
        grep -E "^  [A-Z]" /tmp/oc_contract.log
        echo "契约未变"
    fi
else
    echo "（未找到 python，跳过）"
fi

echo ""
echo "=== 5. fabric 编译 ==="
# core 的 javac 只覆盖 core/src，fabric 这条路一旦有人改了契约却不改实现，
# 上面几步全都还是绿的 —— 编译 fabric 是唯一能发现的地方。
# 设 SKIP_FABRIC=1 可跳过（离线环境 / 只想快速跑规则层时）。
if [ "${SKIP_FABRIC:-0}" = "1" ]; then
    echo "（SKIP_FABRIC=1，跳过）"
elif [ -x ./gradlew ]; then
    if ./gradlew compileJava -q > /tmp/oc_fabric.log 2>&1; then
        echo "fabric 编译通过（含契约实现方）"
    else
        echo "fabric 编译失败："
        tail -25 /tmp/oc_fabric.log
        FAIL=1
    fi
else
    echo "（未找到 gradlew，跳过）"
fi

echo ""
if [ $FAIL -eq 0 ]; then echo "全部通过"; else echo "存在失败项"; fi
exit $FAIL
