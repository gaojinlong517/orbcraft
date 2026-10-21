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
    com.orbcraft.core.hatch.HatchPipelineSelfTest
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
if [ $FAIL -eq 0 ]; then echo "全部通过"; else echo "存在失败项"; fi
exit $FAIL
