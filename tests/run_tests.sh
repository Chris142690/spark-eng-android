#!/usr/bin/env bash
# JVM 单测：不需要 Android SDK / 真机，只用 JDK。
# 用法: bash tests/run_tests.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
# 仓库布局为 app/src/...；旧工作副本布局为 android/src/...，两者都支持
if [ -d "$HERE/../app/src/com/aurora/word" ]; then
  SRC="$HERE/../app/src/com/aurora/word"
else
  SRC="$HERE/../android/src/com/aurora/word"
fi
OUT="$HERE/out"
# javac 是 Windows 程序，吃不下 MSYS 的 /d/… 路径（中文目录会直接报「找不到文件」）。
# 有 cygpath 就转成 D:/… 形式，两个路径都转，避免混用两种风格。
if command -v cygpath >/dev/null 2>&1; then
  SRC="$(cygpath -m "$SRC")"
  OUT="$(cygpath -m "$OUT")"
  CP="$(cygpath -m "$HERE")"
else
  CP="$HERE"
fi
rm -rf "$OUT" && mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" "$SRC/Fsrs.java" "$SRC/CardRules.java" "$SRC/Sense.java" "$SRC/Pattern.java" "$CP/FsrsTest.java" "$CP/InvariantTest.java" "$CP/SenseTest.java" "$CP/PatternTest.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" FsrsTest
echo
java -Dfile.encoding=UTF-8 -cp "$OUT" InvariantTest
echo
java -Dfile.encoding=UTF-8 -cp "$OUT" SenseTest
java -Dfile.encoding=UTF-8 -cp "$OUT" PatternTest
