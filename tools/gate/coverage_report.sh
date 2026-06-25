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
#   0  —— 三行报告均打印成功（不论覆盖率高低，含 run_dump.sh 失败的 0% 基线情形）
#   2  —— 参数缺失
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
# 容忍 run_dump.sh 非零退出（如解析中断、缺 copybook 等）；
# 失败时 JSON 文件可能不存在或为空，后续回退到 0/N 基线。
bash "$HERE/run_dump.sh" "$COB" "$PROG" "$FMT" "$TMPJSON" || true

# —— 解析计数（jq 不可用时回退到 python3；JSON 缺失/损坏时回退到 0）——
_parse_counts() {
  local json="$1"
  # 文件不存在或为空时直接用 0
  if [ ! -s "$json" ]; then
    NSEC=0; NPAR=0; NPER=0
    return
  fi
  if command -v jq &>/dev/null; then
    NSEC=$(jq '.sections | length' "$json" 2>/dev/null) || NSEC=0
    NPAR=$(jq '.paragraphs | length' "$json" 2>/dev/null) || NPAR=0
    NPER=$(jq '.performs | length' "$json" 2>/dev/null) || NPER=0
  else
    NSEC=$(python3 -c "import json,sys; d=json.load(open('$json')); print(len(d['sections']))" 2>/dev/null) || NSEC=0
    NPAR=$(python3 -c "import json,sys; d=json.load(open('$json')); print(len(d['paragraphs']))" 2>/dev/null) || NPAR=0
    NPER=$(python3 -c "import json,sys; d=json.load(open('$json')); print(len(d['performs']))" 2>/dev/null) || NPER=0
  fi
  # 最终保险：空值也归零
  NSEC=${NSEC:-0}; NPAR=${NPAR:-0}; NPER=${NPER:-0}
}
_parse_counts "$TMPJSON"

# —— 打印三行报告 ——
pct() { echo "scale=1; $1 * 100 / $2" | bc; }

echo ""
echo "=== P0 验证闸覆盖率报告 ==="
printf "SECTION   %d/%d (%.1f%%)\n"  "$NSEC" "$BASELINE_SECTIONS"   "$(pct "$NSEC" "$BASELINE_SECTIONS")"
printf "paragraph %d/%d (%.1f%%)\n"  "$NPAR" "$BASELINE_PARAGRAPHS" "$(pct "$NPAR" "$BASELINE_PARAGRAPHS")"
printf "PERFORM   %d/%d (%.1f%%)\n"  "$NPER" "$BASELINE_PERFORMS"   "$(pct "$NPER" "$BASELINE_PERFORMS")"
echo "==========================="
