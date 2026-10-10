#!/bin/sh
# NetScope 出包脚本 —— 第二段（Linux 侧）
# 前提：已在 Android 侧跑完 1-aapt2.sh 生成 build/out/apk/base.apk
set -e

PROJ=/sdcard/Download/projects/netscope
TOOLS=/storage/emulated/0/Download/TGAutoSign-build/tools
ANDROID_JAR=$TOOLS/libs/android.jar
D8=$TOOLS/libs/android-16/d8
APKSIGNER_JAR=$TOOLS/libs/android-16/lib/apksigner.jar
KS="/storage/emulated/0/工程tg/签名材料/release.jks"

BUILD=$PROJ/build
APP=$PROJ/app
OUT=$BUILD/out
DEX=$OUT/dex
rm -rf "$DEX"
mkdir -p "$DEX"

if [ ! -f "$OUT/apk/base.apk" ]; then
  echo "错误: 缺少 $OUT/apk/base.apk，请先在 Android 侧执行 1-aapt2.sh"
  exit 1
fi

echo "==> javac 编译"
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
# 源码 + aapt2 生成的 R.java（资源引用需要）
SRC="$(find "$APP/src/main/java" -name "*.java") $(find "$OUT/gen" -name "*.java" 2>/dev/null)"
javac --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$OUT/classes" $SRC

echo "==> d8 转 dex"
java -cp "$TOOLS/libs/android-16/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$DEX" $(find "$OUT/classes" -name "*.class")

echo "==> 组装未签名 APK"
STAGE=$OUT/stage
rm -rf "$STAGE"; mkdir -p "$STAGE"
cd "$STAGE"
unzip -o -q "$OUT/apk/base.apk"
cp "$DEX/classes.dex" ./classes.dex
rm -f "$OUT/unsigned.apk"
# classes.dex / AndroidManifest.xml / resources.arsc 必须 STORED（不压缩），
# 否则 Android 会拒绝安装或资源解析失败
zip -q -X -0 "$OUT/unsigned.apk" classes.dex
zip -q -X -0 "$OUT/unsigned.apk" AndroidManifest.xml resources.arsc
# 其余走默认压缩
zip -q -X -r "$OUT/unsigned.apk" . \
  -x classes.dex AndroidManifest.xml resources.arsc
cd "$OUT"

echo "==> 签名"
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" \
  --ks-pass "pass:wlmosv@2026" \
  --key-pass "pass:wlmosv@2026" \
  --ks-key-alias wlmosv \
  --min-sdk-version 26 \
  --out "$OUT/wpaforge-signed.apk" \
  "$OUT/unsigned.apk"

echo "==> 完成"
ls -l "$OUT/wpaforge-signed.apk"
