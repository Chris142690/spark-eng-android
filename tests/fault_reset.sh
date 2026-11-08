#!/usr/bin/env bash
# 故障注入的「干净基线」恢复器 —— 让每次注入都能重复从同一个起点再来一遍。
#
# 用法：
#   bash tests/fault_reset.sh save            # 建基线：抓真库到本机并记指纹
#   bash tests/fault_reset.sh restore         # 从基线还原，并比对指纹（必须一致）
#   bash tests/fault_reset.sh restore --force # 指纹不一致也要还原（仅确实需要时）
#
# 为什么需要它（GPT 第 12 轮验收裁决第二条）：
#   故障注入必须失败后能重建干净测试基线，且恢复过程可重复执行。
#   这里把「抓基线 / 还原 / 校验」做成同一条命令，避免人工 cp 漏步。
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="/d/asdk/platform-tools/adb.exe"
PKG="com.aurora.word"
BASE_DIR="$ROOT/tests/shots/baseline"
BASE="$BASE_DIR/aurora-baseline.db"
DIGEST="$BASE_DIR/aurora-baseline.digest.txt"
mkdir -p "$BASE_DIR"

pull_pkg_db() { # $1 = 包内路径（相对 databases/） $2 = 目标本地路径
  "$ADB" shell "run-as $PKG cat databases/$1" > "$2" 2>/dev/null
  [ -s "$2" ]
}
pull_db() { pull_pkg_db aurora.db "$1"; }

case "${1:-}" in
  save)
    # 一致快照：先停 App（避免 WAL 里压着未合并事务），再用 sqlite3 .backup 生成副本
    "$ADB" shell "am force-stop $PKG"
    "$ADB" shell "run-as $PKG sh -c 'rm -f databases/fault-baseline.db'"
    "$ADB" shell "run-as $PKG sh -c 'sqlite3 databases/aurora.db \".backup databases/fault-baseline.db\"'"
    if ! pull_pkg_db fault-baseline.db "$BASE"; then echo "FAIL 无法从设备抓取 fault-baseline.db（设备未连接？）"; exit 2; fi
    "$ADB" shell "am start -n $PKG/.MainActivity" >/dev/null
    python "$ROOT/tests/stable_digest.py" --db "$BASE" > "$DIGEST" || exit 1
    echo "基线已建立（.backup 在线一致快照）：$BASE"
    grep -E "user_version|^card|^review_event" "$DIGEST" || true
    ;;
  restore)
    if [ ! -s "$BASE" ]; then echo "FAIL 没有基线，先跑：bash tests/fault_reset.sh save"; exit 2; fi
    "$ADB" shell "am force-stop $PKG"
    "$ADB" push "$BASE" /sdcard/aurora-restore.db >/dev/null 2>&1 || { echo "FAIL push 失败"; exit 2; }
    # 必须先删 -wal/-shm：留着旧 WAL，覆盖主库后旧事务会被重新应用，还原等于没做
    "$ADB" shell "run-as $PKG sh -c 'rm -f databases/aurora.db-wal databases/aurora.db-shm'"
    "$ADB" shell "run-as $PKG sh -c 'cp /sdcard/aurora-restore.db databases/aurora.db'"
    "$ADB" shell "am start -n $PKG/.MainActivity" >/dev/null
    sleep 2
    TMP="$BASE_DIR/after-restore.db"
    if ! pull_db "$TMP"; then echo "FAIL 还原后无法读库"; exit 2; fi
    python "$ROOT/tests/stable_digest.py" --db "$BASE" --compare "$TMP"
    RC=$?
    if [ "$RC" -eq 0 ]; then
      echo "还原校验：PASS（指纹与基线完全一致）"
      exit 0
    fi
    echo "还原校验：FAIL（rc=$RC；若确有编码差异，检查 integrity_check）"
    "$ADB" shell "run-as $PKG sqlite3 databases/aurora.db 'PRAGMA integrity_check'"
    [ "${2:-}" = "--force" ] && exit 0
    exit 1
    ;;
  *)
    sed -n '2,12p' "$0"
    exit 64
    ;;
esac
