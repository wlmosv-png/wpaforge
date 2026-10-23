#!/bin/sh
# WPAForge 出包脚本 —— 第二段（桌面侧）
#
# 前提：已在设备侧跑完 1-aapt2.sh，生成 build/out/apk/base.apk
#
# 项目根自动从脚本位置推导；工具路径可覆盖：
#   WP_TOOLS        工具根目录（需含 libs/android.jar 与 libs/android-16/）
#   WP_ANDROID_JAR  单独指定 android.jar
#   WP_D8_JAR       单独指定 d8.jar
#
# 签名通过环境变量传入，**不要把密码写进脚本**（详见下方说明）。
set -e

# 项目根 = 本脚本所在目录
PROJ="$(cd "$(dirname "$0")" && pwd)"

TOOLS="${WP_TOOLS:-/storage/emulated/0/Download/TGAutoSign-build/tools}"
ANDROID_JAR="${WP_ANDROID_JAR:-$TOOLS/libs/android.jar}"
D8_JAR="${WP_D8_JAR:-$TOOLS/libs/android-16/lib/d8.jar}"
APKSIGNER_JAR="${WP_APKSIGNER_JAR:-$TOOLS/libs/android-16/lib/apksigner.jar}"

# 无第三方依赖：Shizuku / 免 Root 通道已在 1.9.24 彻底移除，
# 现在只需要 android.jar。
# ---------------------------------------------------------------------------
# 签名材料通过环境变量传入，**不要把密码写进脚本**。
#
# 使用方式（在本地未入库的 env 文件里设置，或直接 export）：
#   export WP_FORGE_KS="/path/to/release.jks"
#   export WP_FORGE_KS_PASS="..."
#   export WP_FORGE_KEY_PASS="..."      # 省略则与 KS_PASS 相同
#   export WP_FORGE_KS_ALIAS="..."
#
# 脚本会在缺少这些变量时明确报错，而不是静默失败。
# ---------------------------------------------------------------------------
KS="${WP_FORGE_KS:-}"
KS_PASS="${WP_FORGE_KS_PASS:-}"
KEY_PASS="${WP_FORGE_KEY_PASS:-$KS_PASS}"
KS_ALIAS="${WP_FORGE_KS_ALIAS:-}"

BUILD=$PROJ/build
APP=$PROJ/app
OUT=$BUILD/out
DEX=$OUT/dex
rm -rf "$DEX"
mkdir -p "$DEX"

# ---- 前置检查 ----
if [ ! -f "$OUT/apk/base.apk" ]; then
  echo "错误: 缺少 $OUT/apk/base.apk" >&2
  echo "请先在设备侧执行:  sh 1-aapt2.sh" >&2
  exit 1
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "错误: 找不到 android.jar：$ANDROID_JAR" >&2
  echo "请用 WP_ANDROID_JAR 指定，或用 WP_TOOLS 指定工具根目录" >&2
  exit 1
fi
if [ ! -f "$D8_JAR" ]; then
  echo "错误: 找不到 d8.jar：$D8_JAR" >&2
  exit 1
fi
if [ ! -f "$APKSIGNER_JAR" ]; then
  echo "错误: 找不到 apksigner.jar：$APKSIGNER_JAR" >&2
  exit 1
fi

echo "==> javac 编译"
rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"
# 源码 + aapt2 生成的 R.java（资源引用需要）
#
# 生产 dex **只编译应用代码**，排除两类不进发行包的源文件：
#   · *Test.java        —— 50 个测试类（由门禁 /workspace/wpf-gate.sh 单独编译执行）
#   · R3AuditProbe.java —— 判定链审计探针（审计工具，非产品功能）
#   · MarkerSelfCheck.java —— 日志标记可见性自检工具（发布前复核用；**无运行期入口**，只被门禁测试与桌面 CLI 使用）
# 它们仍留在源码树里，门禁照常收集并执行（不从门禁排除、不删源文件），
# 只是不再被编进 classes.dex。
# 注意：**不能用 "*Probe.java"** —— 会误伤生产类 scan/LanProbe.java。
SRC="$(find "$APP/src/main/java" -name "*.java" \
        ! -name "*Test.java" ! -name "R3AuditProbe.java" \
        ! -name "MarkerSelfCheck.java") \
     $(find "$OUT/gen" -name "*.java" 2>/dev/null)"
javac --release 17 -encoding UTF-8 \
  -cp "$ANDROID_JAR" \
  -d "$OUT/classes" $SRC

echo "==> d8 转 dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$DEX" \
  $(find "$OUT/classes" -name "*.class")

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
if [ -z "$KS" ] || [ -z "$KS_PASS" ] || [ -z "$KS_ALIAS" ]; then
  echo "错误: 缺少签名配置。请先设置环境变量：" >&2
  echo "  WP_FORGE_KS        签名文件路径" >&2
  echo "  WP_FORGE_KS_PASS   keystore 密码" >&2
  echo "  WP_FORGE_KS_ALIAS  key 别名" >&2
  echo "（可选）WP_FORGE_KEY_PASS  key 密码，默认与 ks 密码相同" >&2
  exit 1
fi
if [ ! -f "$KS" ]; then
  echo "错误: 找不到签名文件 $KS" >&2
  exit 1
fi

java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --min-sdk-version 26 \
  --out "$OUT/wpaforge-signed.apk" \
  "$OUT/unsigned.apk"

echo "==> 完成"
ls -l "$OUT/wpaforge-signed.apk"
