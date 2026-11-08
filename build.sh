#!/bin/bash
# Spark·ENG — 手工构建 APK：aapt2 → javac → d8 → pack.py → apksigner
#
# 这是**源码仓库**的构建脚本，不需要 Gradle。
# 依赖：JDK 17+（javac）、Android SDK build-tools 34.0.0、platforms/android-34
#
# 用法：
#   export ANDROID_SDK_ROOT=/path/to/android-sdk     # 必需
#   export KSPASS='你的签名口令'                      # 可选，不给则产出未签名 APK
#   ./build.sh
#
# 输出：build/apk/aurora.apk（已签名）或 build/apk/unsigned.apk（未签名）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
# javac / aapt2 是原生 Windows 程序，不认 MSYS 的 /d/... 形式，统一转成 D:/...
if command -v cygpath >/dev/null 2>&1; then ROOT="$(cygpath -m "$ROOT")"; fi
APP="$ROOT/app"
B="$ROOT/build"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

if [ -z "$SDK" ]; then
  echo "ERROR: 请先设置 ANDROID_SDK_ROOT（或 ANDROID_HOME）指向 Android SDK 目录"
  exit 1
fi

BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
# Windows 上是 .exe / .bat，Unix 上是无后缀
AAPT2="$BT/aapt2";    [ -x "$AAPT2" ] || AAPT2="$BT/aapt2.exe"
D8="$BT/d8";          [ -x "$D8" ]    || D8="$BT/d8.bat"
APKSN="$BT/apksigner";[ -x "$APKSN" ] || APKSN="$BT/apksigner.bat"
# 注意：Windows 的 WindowsApps\python3 是应用商店占位符，会静默退出（rc=49），所以优先 python
PY="$(command -v python || command -v python3 || true)"
[ -n "$PY" ] || { echo "ERROR: 找不到 python3/python"; exit 1; }
for f in "$BT/aapt2" "$BT/d8" "$PLAT"; do
  [ -e "$f" ] || [ -e "$f.bat" ] || { echo "ERROR: 缺少 $f"; exit 1; }
done

rm -rf "$B/compiled" "$B/classes" "$B/dex" "$B/apk" "$B/gen"
mkdir -p "$B/compiled" "$B/classes" "$B/dex" "$B/apk" "$B/gen"

echo "[1/6] aapt2 compile res"
"$AAPT2" compile --dir "$APP/res" -o "$B/compiled/" 2>&1 | tail -5

echo "[2/6] aapt2 link"
"$AAPT2" link \
  -o "$B/apk/base.apk" \
  -I "$PLAT" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$B/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  "$B"/compiled/*.flat 2>&1 | tail -5

echo "[3/6] javac"
find "$APP/src" "$B/gen" -name '*.java' > "$B/sources.txt"
if ! javac -nowarn -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$PLAT" -classpath "$PLAT" \
      -d "$B/classes" @"$B/sources.txt" > "$B/javac.log" 2>&1; then
  echo "!! JAVAC FAILED"; tail -30 "$B/javac.log"; exit 1
fi
echo "   javac OK: $(find "$B/classes" -name '*.class' | wc -l) classes"

echo "[4/6] d8"
find "$B/classes" -name '*.class' > "$B/classes.txt"
"$D8" --min-api 24 --output "$B/dex" --lib "$PLAT" @"$B/classes.txt" 2>&1 | tail -10
[ -f "$B/dex/classes.dex" ] || { echo "!! classes.dex 未产出"; exit 1; }

echo "[5/6] package dex"
"$PY" "$APP/tools/pack.py" "$B/apk/base.apk" "$B/dex/classes.dex" "$B/apk/unsigned.apk" || exit 1

echo "[6/6] sign"
KS="${KS_PATH:-$ROOT/aurora.keystore}"
if [ -z "${KSPASS:-}" ]; then
  echo "   KSPASS 未设置 → 跳过签名，产物为 $B/apk/unsigned.apk"
elif [ ! -f "$KS" ]; then
  echo "   未找到 $KS，用 KSPASS 现场生成一个自用签名："
  keytool -genkeypair -v -keystore "$KS" -alias aurora -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=SparkENG, OU=Dev, O=SparkENG, L=CN, S=CN, C=CN" 2>&1 | tail -3
fi

if [ -n "${KSPASS:-}" ] && [ -f "$KS" ]; then
  "$APKSN" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --out "$B/apk/aurora.apk" "$B/apk/unsigned.apk" 2>&1 | tail -5
  OUT="$B/apk/aurora.apk"
else
  OUT="$B/apk/unsigned.apk"
fi

"$PY" - "$OUT" <<'CHK'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); names = z.namelist()
need = ["AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/words.json", "assets/patterns.json"]
miss = [n for n in need if n not in names]
bad = [(i.filename, i.header_offset) for i in z.infolist()
       if i.filename in need and (i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)) % 4]
if miss or bad:
    print("!! APK 校验失败 missing=%s misaligned=%s" % (miss, bad)); sys.exit(1)
print("APK 校验通过：必需条目齐全且 data_off 4 字节对齐")
CHK

ls -l "$OUT"
echo "BUILD OK"
