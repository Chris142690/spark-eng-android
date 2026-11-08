#!/usr/bin/env bash
# 一键验收入口：JVM 单测 → 离线自检 → 构建 →（有设备时）真机 smoke + 句型探针 + 对账 + 指纹
# 用法：bash tests/verify_v14.sh            # 无设备也会跑完 JVM/离线自检/构建，并明确报 SKIP
#       bash tests/verify_v14.sh --apk      # 额外把构建产物复制到 re/ccg/aurora.apk
#
# 纪律（GPT 第 12 轮验收裁决）：
#   * SKIP 不算通过；脚本结论按「最弱一环」给
#   * 破坏性注入只在可还原的库副本上做，结束必须还原并比对指纹
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="/d/asdk/platform-tools/adb.exe"
PKG="com.aurora.word"
STAMP="$(date +%Y%m%d-%H%M)"
OUT="$ROOT/tests/shots/$STAMP"
mkdir -p "$OUT"
FAILS=0
SKIPS=0

say()  { printf '\n=== %s ===\n' "$1"; }
bad()  { FAILS=$((FAILS+1)); printf 'FAIL %s\n' "$1"; }
skip() { SKIPS=$((SKIPS+1)); printf 'SKIP %s\n' "$1"; }

say "1/7 JVM 单测"
if bash "$ROOT/tests/run_tests.sh" > "$OUT/jvm.log" 2>&1; then
  grep -E "PASS|FAIL" "$OUT/jvm.log" | tail -6
else
  bad "JVM 单测有失败项（见 $OUT/jvm.log）"
fi

say "2/7 离线自检（对账 SQL + 稳定指纹 + V5→V14 语句重放 + 句型内容结构）"
if python "$ROOT/tests/audit_selftest.py" > "$OUT/audit-selftest.log" 2>&1; then
  cat "$OUT/audit-selftest.log"
else
  cat "$OUT/audit-selftest.log"
  bad "离线自检未通过（见 $OUT/audit-selftest.log）"
fi
if python "$ROOT/tests/migrate_replay.py" > "$OUT/migrate-replay.log" 2>&1; then
  cat "$OUT/migrate-replay.log"
else
  cat "$OUT/migrate-replay.log"
  bad "V5→V14 语句重放未通过（见 $OUT/migrate-replay.log；只证明合成样本，不替代真机迁移）"
fi
if python "$ROOT/tests/content_check.py" > "$OUT/content-check.log" 2>&1; then
  cat "$OUT/content-check.log"
else
  cat "$OUT/content-check.log"
  bad "句型内容结构校验未通过（见 $OUT/content-check.log；坏一条会让 loadPatterns 整包失败）"
fi

say "3/7 构建 APK"
if ( cd /d/aw && bash build.sh ) > "$OUT/build.log" 2>&1; then
  APK="/d/aw/build/apk/aurora.apk"
  ls -l "$APK"
  if [ "${1:-}" = "--apk" ]; then cp "$APK" "$ROOT/aurora.apk"; echo "已复制到 $ROOT/aurora.apk"; fi
else
  bad "构建失败（见 $OUT/build.log）"
fi

say "4/7 设备探测"
if ! "$ADB" devices 2>/dev/null | grep -q "device$"; then
  skip "无真机连接 —— 真机 smoke / 句型探针 / 迁移对账 全部未执行，不算 PASS"
  echo "证据目录：$OUT"
  echo "本轮 FAIL=$FAILS SKIP=$SKIPS（真机项未执行 → 不构成验收通过）"
  exit 2
fi
"$ADB" devices

say "5/7 真机 smoke"
python "$ROOT/tests/smoke_adb.py" > "$OUT/smoke.log" 2>&1
SMOKE_RC=$?
tail -25 "$OUT/smoke.log"
[ "$SMOKE_RC" -eq 0 ] || bad "smoke 未全通过（rc=$SMOKE_RC，见 $OUT/smoke.log）"

say "6/7 句型线探针（T1–T4）"
python "$ROOT/tests/pattern_probe.py" > "$OUT/probe.log" 2>&1
PROBE_RC=$?
tail -25 "$OUT/probe.log"
case "$PROBE_RC" in
  0) : ;;
  2) skip "句型探针未执行（无设备/无基线），不算通过" ;;
  *) bad "句型探针有失败项（rc=$PROBE_RC，见 $OUT/probe.log）" ;;
esac

say "7/7 迁移对账 + 稳定指纹"
"$ADB" shell "run-as $PKG sqlite3 databases/aurora.db" < "$ROOT/tests/MIGRATION-AUDIT.sql" > "$OUT/audit.txt" 2>&1
echo "对账输出：$OUT/audit.txt"
grep -E "user_version|idx_review_eid|dup_eid|dup_guard|event_type_null|bad_fsrs_rating|bad_kind" "$OUT/audit.txt" \
  || echo "（未取到关键行，人工核对 $OUT/audit.txt）"
# 稳定指纹：pull 真库到本地，用 stable_digest.py 计算（不依赖 SQL 拼串）
"$ADB" shell "run-as $PKG cat databases/aurora.db" > "$OUT/aurora.db" 2>/dev/null
if [ -s "$OUT/aurora.db" ]; then
  python "$ROOT/tests/stable_digest.py" --db "$OUT/aurora.db" | tee "$OUT/digest.txt"
else
  skip "未能拉取 aurora.db，稳定指纹未计算"
fi

say "结果"
echo "证据目录：$OUT"
echo "本轮 FAIL=$FAILS SKIP=$SKIPS"
[ "$FAILS" -gt 0 ] && exit 1
[ "$SKIPS" -gt 0 ] && { echo "结论：有未执行项，不算验收通过"; exit 3; }
echo "结论：全部执行且通过"
