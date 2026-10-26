#!/system/bin/sh
# WPAForge 出包脚本 —— 第一段（Android 侧，aapt2 是 Android arm64 二进制）
#
# 用法: sh 1-aapt2.sh
#
# 所有路径通过环境变量覆盖，都有默认值。项目根目录自动从脚本位置推导，
# 不再写死 —— 换目录、换机器都能用。
#
#   WP_AAPT2_DIR    aapt2 及其依赖 .so 所在目录
#   WP_ANDROID_JAR  android.jar 路径
#   WP_VERSION_CODE / WP_VERSION_NAME   版本号

# 项目根 = 本脚本所在目录
P="$(cd "$(dirname "$0")" && pwd)"

# 工具位置（可用环境变量覆盖）
AR="${WP_AAPT2_DIR:-/data/local/tmp/eta/aapt2run}"
AJ="${WP_ANDROID_JAR:-/storage/emulated/0/Download/TGAutoSign-build/tools/libs/android.jar}"

VER_CODE="${WP_VERSION_CODE:-121}"
VER_NAME="${WP_VERSION_NAME:-1.9.26}"

OUT="$P/build/out"

# ---- 前置检查：缺东西就明确报错，而不是跑一半失败 ----
if [ ! -x "$AR/bin/aapt2" ]; then
  echo "错误: 找不到可执行的 aapt2：$AR/bin/aapt2" >&2
  echo "请用 WP_AAPT2_DIR 指定 aapt2 所在目录（需含 bin/aapt2 与 lib/）" >&2
  exit 1
fi
if [ ! -f "$AJ" ]; then
  echo "错误: 找不到 android.jar：$AJ" >&2
  echo "请用 WP_ANDROID_JAR 指定路径" >&2
  exit 1
fi
if [ ! -d "$P/app/src/main/res" ]; then
  echo "错误: 找不到资源目录：$P/app/src/main/res" >&2
  exit 1
fi

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
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  "$OUT/flat/res.zip" || exit 1

echo "==> 完成"
ls -l "$OUT/apk/base.apk"
