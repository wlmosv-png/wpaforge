#!/system/bin/sh
# NetScope 出包脚本 —— 第一段（Android 侧，aapt2 是 Android arm64 二进制）
# 用法: sh 1-aapt2.sh
AR=/data/local/tmp/eta/aapt2run
P=/sdcard/Download/projects/netscope
OUT=$P/build/out
AJ=/storage/emulated/0/Download/TGAutoSign-build/tools/libs/android.jar

mkdir -p "$OUT/flat" "$OUT/apk" "$OUT/gen"
export LD_LIBRARY_PATH=$AR/lib

echo "==> aapt2 compile"
$AR/bin/aapt2 compile --dir "$P/app/src/main/res" -o "$OUT/flat/res.zip" || exit 1

echo "==> aapt2 link"
$AR/bin/aapt2 link \
  -o "$OUT/apk/base.apk" \
  -I "$AJ" \
  --manifest "$P/app/src/main/AndroidManifest.xml" \
  -A "$P/app/src/main/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 5 \
  --version-name 0.5.0 \
  "$OUT/flat/res.zip" || exit 1

echo "==> 完成"
ls -l "$OUT/apk/base.apk"
