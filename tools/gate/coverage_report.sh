#!/usr/bin/env bash
#
# coverage_report.sh —— P0 验证闸覆盖率报告
#
# 用途：调用 run_dump.sh 产出 JSON，再用 jq 取出 SECTION/paragraph/PERFORM 计数，
#       对照硬基线打印 x/Y (NN%) 三行。后续每个任务都靠它度量进展。
#
# 对应设计文档：docs/详细设计/task-1-验证闸脚手架.md（§5 覆盖率报告）
#
# 用法：
#   bash tools/gate/coverage_report.sh <源.cob> [程序名] [格式]
# 示例：
#   bash tools/gate/coverage_report.sh "/home/zp/Documents/cob/源码一期/源码/CBL FILES/ZPOLDWNM.cob"
#
# 退出码：
#   0  —— 三行报告均打印成功（不论覆盖率高低）
#   非0 —— run_dump.sh 本身失败（解析中断、缺 jar 等），含原始 stderr
#
set -euo pipefail

# —— 基线常量（便于换样本时统一改此处）——
BASELINE_SECTIONS=125
BASELINE_PARAGRAPHS=322
BASELINE_PERFORMS=616

# —— 参数 ——
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COB="${1:-}"
PROG="${2:-ZPOLDWNM}"
FMT="${3:-FIXED}"

if [ -z "$COB" ]; then
  echo "用法: $0 <源.cob> [程序名] [格式]" >&2
  exit 2
fi

# —— 运行 dump ——
TMPJSON="$(mktemp /tmp/asg_dump_XXXXXX.json)"
trap 'rm -f "$TMPJSON"' EXIT

echo "[coverage_report] 调用 run_dump.sh..."
bash "$HERE/run_dump.sh" "$COB" "$PROG" "$FMT" "$TMPJSON"

# —— 解析计数（jq 不可用时回退到 python3）——
if command -v jq &>/dev/null; then
  NSEC=$(jq '.sections | length' "$TMPJSON")
  NPAR=$(jq '.paragraphs | length' "$TMPJSON")
  NPER=$(jq '.performs | length' "$TMPJSON")
else
  NSEC=$(python3 -c "import json,sys; d=json.load(open('$TMPJSON')); print(len(d['sections']))")
  NPAR=$(python3 -c "import json,sys; d=json.load(open('$TMPJSON')); print(len(d['paragraphs']))")
  NPER=$(python3 -c "import json,sys; d=json.load(open('$TMPJSON')); print(len(d['performs']))")
fi

# —— 打印三行报告 ——
pct() { echo "scale=1; $1 * 100 / $2" | bc; }

echo ""
echo "=== P0 验证闸覆盖率报告 ==="
printf "SECTION   %d/%d (%.1f%%)\n"  "$NSEC" "$BASELINE_SECTIONS"   "$(pct "$NSEC" "$BASELINE_SECTIONS")"
printf "paragraph %d/%d (%.1f%%)\n"  "$NPAR" "$BASELINE_PARAGRAPHS" "$(pct "$NPAR" "$BASELINE_PARAGRAPHS")"
printf "PERFORM   %d/%d (%.1f%%)\n"  "$NPER" "$BASELINE_PERFORMS"   "$(pct "$NPER" "$BASELINE_PERFORMS")"
echo "==========================="
