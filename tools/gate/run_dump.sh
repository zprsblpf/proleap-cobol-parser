#!/usr/bin/env bash
#
# run_dump.sh —— DumpAsg 的瘦运行入口（P0 验证闸，迁自 spike_proleap/run_dump.sh）
#
# 用途：拼出 ProLeap 运行时 classpath（IntelliJ 自带 mvn 生成依赖清单 + 主 jar），
#       编译并运行 DumpAsg，把 ASG 导成 JSON。只做参数解析 + 调用，无业务逻辑。
#
# 对应设计文档：docs/详细设计/task-1-验证闸脚手架.md（§4 run_dump 入口）
#
# 与 spike 版本的关键区别：
#   - 不再调用 clean_source.py / gen_stub_copybooks.py，直接吃脏原始源；
#   - 不再依赖 stub_copybooks 目录；
#   - 拷贝簿目录只指向真实 CPY FILES（+ CBL FILES 兜底），不含桩目录。
#
# 用法：
#   tools/gate/run_dump.sh [cob文件] [程序名] [格式] [输出json]
# 默认（不传参时）使用 P0 样本：
#   cob=/home/zp/Documents/cob/源码一期/源码/CBL FILES/ZPOLDWNM.cob  程序名=ZPOLDWNM  格式=FIXED
#
set -euo pipefail

# —— 可配置参数（规范 §12：不硬编码，留默认但可覆盖）——
SRC_ROOT="/home/zp/Documents/cob/源码一期/源码"
COB="${1:-$SRC_ROOT/CBL FILES/ZPOLDWNM.cob}"
PROG="${2:-ZPOLDWNM}"
FMT="${3:-FIXED}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${4:-$HERE/asg_dump.json}"

# 拷贝簿目录：真实 CPY FILES（主要） + CBL FILES（兜底，同目录可能含 .cob 格式簿）
# 注意：不再包含 stub_copybooks —— Task 1 基线就是要看缺簿时实际中断点
CPY_DIR="$SRC_ROOT/CPY FILES"
CBL_DIR="$SRC_ROOT/CBL FILES"
COPY_DIRS="$CPY_DIR:$CBL_DIR"

PROLEAP_REPO="$(cd "$HERE/../.." && pwd)"
PROLEAP_JAR="$PROLEAP_REPO/target/proleap-cobol-parser-4.0.0.jar"
MVN="/opt/idea-IU-261.23567.138/plugins/maven/lib/maven3/bin/mvn"
# 本机仅 IntelliJ JBR 带 javac（JDK21），统一用它编译+运行，版本一致
JBR="/opt/idea-IU-261.23567.138/jbr/bin"
DEP_PLUGIN="org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath"
CP_FILE="$HERE/.classpath.txt"

[ -f "$PROLEAP_JAR" ] || { echo "缺主 jar: $PROLEAP_JAR（先在 $PROLEAP_REPO 构建）" >&2; exit 1; }
[ -x "$MVN" ] || { echo "缺 mvn: $MVN" >&2; exit 1; }
[ -x "$JBR/javac" ] || { echo "缺 javac: $JBR/javac" >&2; exit 1; }

# —— 1. 生成依赖 classpath（缓存，避免每次跑 mvn）——
# 用插件全坐标调用，绕开 'dependency' 前缀解析（离线优先，失败回退在线）
if [ ! -f "$CP_FILE" ]; then
  echo "[run_dump] 生成依赖 classpath（首次，经 maven-dependency-plugin:build-classpath）..."
  ( cd "$PROLEAP_REPO" && "$MVN" -q -o "$DEP_PLUGIN" \
      "-Dmdep.outputFile=$CP_FILE" -DincludeScope=runtime ) \
    || ( cd "$PROLEAP_REPO" && "$MVN" -q "$DEP_PLUGIN" \
           "-Dmdep.outputFile=$CP_FILE" -DincludeScope=runtime )
fi
DEPS="$(cat "$CP_FILE")"
CP="$DEPS:$PROLEAP_JAR:$HERE"

# —— 2. 编译 DumpAsg.java ——
echo "[run_dump] javac DumpAsg.java"
"$JBR/javac" -cp "$CP" -d "$HERE" "$HERE/DumpAsg.java"

# —— 3. 运行（直接解析脏原始源）——
echo "[run_dump] 解析 $COB （程序 $PROG / 格式 $FMT；拷贝簿目录 $COPY_DIRS）"
"$JBR/java" -cp "$CP" DumpAsg "$COB" "$PROG" "$FMT" "$OUT" "$COPY_DIRS"
