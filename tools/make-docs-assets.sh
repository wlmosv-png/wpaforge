#!/bin/sh
# 用手机原始截图生成 README / 站点用图（2026-10-26 建立）。
#
# 为什么要有这个脚本：截图换新版时，手工裁状态栏、缩放、做 GIF 很容易漏一张、
# 或每张尺寸不一致（GitHub 上就会一行大一行小）。这里把口径固定下来：
#   · 统一裁掉状态栏（crop 1080x2286+0+90，ColorOS 1080x2376）
#   · 统一缩放到 640 宽 —— 网格里每张都一样大
#   · GIF 统一 640 宽 / 8fps / 交叉淡入，帧序固定
#
# 用法:
#   sh tools/make-docs-assets.sh <截图目录> <输出目录>
#
# 截图目录里按下面的**固定文件名**放原图（1080x2376 的原始截图，不用预先处理）：
#   shot01.png 观测（频谱雷达）      shot05.png 审计（字典与离线校验）
#   shot02.png 破解（进度与速率）    shot06.png WPS
#   shot03.png 命中（口令 + 复制）   shot07.png 设置
#   shot04.png 字典管理（命中率排序）shot08.png 网络侦察
# GIF 用图（可选，缺了就跳过对应的 GIF）：
#   gif-a.png 观测  gif-b.png 破解  gif-c.png 命中  gif-d.png 字典管理
#   hit-a.png 破解中  hit-b.png 命中卡
#
# 依赖：ffmpeg（Linux 侧）
set -e

IN="${1:?用法: sh tools/make-docs-assets.sh <截图目录> <输出目录>}"
OUT="${2:?用法: sh tools/make-docs-assets.sh <截图目录> <输出目录>}"
SHOTS="$OUT/screenshots"
mkdir -p "$SHOTS"

CROP="crop=1080:2286:0:90"
W=640
FPS=6          # 6fps 足够顺滑，GIF 体积比 8fps 小三分之一
HOLD=2.0       # 每张图停留秒数（用户要求 2 秒/张）

# 原始截图 → 640 宽、去掉状态栏
prep() {
  # $1 源文件  $2 目标文件
  ffmpeg -y -loglevel error -i "$1" -vf "$CROP,scale=$W:-1" "$2"
  printf '  %-28s %s\n' "$(basename "$2")" "$(ffprobe -v error -select_streams v \
      -show_entries stream=width,height -of csv=p=0 "$2")"
}

echo "== 单图 =="
i=0
for name in shot01 shot02 shot03 shot04 shot05 shot06 shot07 shot08; do
  i=$((i+1))
  [ -f "$IN/$name.png" ] || { echo "  （缺 $name.png，跳过）"; continue; }
  prep "$IN/$name.png" "$SHOTS/$name.png"
done

# 交叉淡入 GIF：n 帧，每帧停留 $1 秒，转场 $2 秒
# xfade 的 offset 是**前面所有片段累计时长**：第 k 次转场的起点 = k*(hold)
make_gif() {
  out="$1"; hold="$2"; fade="$3"; shift 3
  set -- "$@"
  n=$#
  [ "$n" -ge 2 ] || { echo "  （$out 需要至少 2 帧，跳过）"; return 0; }
  # 每段必须比「停留时长」多出转场时长 —— xfade 要第 k 段的画面一直延续到
  # offset+fade，只给 hold 会让输出在第 1 次转场后就被截断（实测只出 19 帧）
  seg=$(awk "BEGIN{printf \"%.3f\", $hold + $fade}")
  args=""; fc=""; idx=0
  for f in "$@"; do
    args="$args -loop 1 -t $seg -i $f"
    fc="$fc[$idx:v]scale=$W:-1,fps=$FPS,format=rgba,setsar=1[v$idx];"
    idx=$((idx+1))
  done
  last="[v0]"; k=1; off=$(awk "BEGIN{printf \"%.3f\", $hold}")
  while [ "$k" -lt "$n" ]; do
    lab="[x$k]"
    fc="$fc$last[v$k]xfade=transition=fade:duration=$fade:offset=$off$lab;"
    last="$lab"
    k=$((k+1))
    off=$(awk "BEGIN{printf \"%.3f\", $off + $hold}")
  done
  fc="$fc${last}split[s1][s2];[s1]palettegen=max_colors=128:stats_mode=diff[p];[s2][p]paletteuse=dither=none"
  # shellcheck disable=SC2086
  ffmpeg -y -loglevel error $args -filter_complex "$fc" -map "[p]" "$out" 2>/dev/null || \
  ffmpeg -y -loglevel error $args -filter_complex "$fc" -loop 0 "$out"
  printf '  %-28s %s KB\n' "$(basename "$out")" "$(( $(stat -c%s "$out") / 1024 ))"
}

echo "== 界面速览 GIF（4 帧）=="
if [ -f "$IN/gif-a.png" ] && [ -f "$IN/gif-b.png" ] \
   && [ -f "$IN/gif-c.png" ] && [ -f "$IN/gif-d.png" ]; then
  for f in gif-a gif-b gif-c gif-d; do ffmpeg -y -loglevel error -i "$IN/$f.png" -vf "$CROP" "/tmp/$f.raw.png"; done
  make_gif "$OUT/demo-ui.gif" "$HOLD" 0.5 /tmp/gif-a.raw.png /tmp/gif-b.raw.png /tmp/gif-c.raw.png /tmp/gif-d.raw.png
else
  echo "  （gif-a..d.png 不全，跳过）"
fi

echo "== 命中 GIF（2 帧）=="
if [ -f "$IN/hit-a.png" ] && [ -f "$IN/hit-b.png" ]; then
  for f in hit-a hit-b; do ffmpeg -y -loglevel error -i "$IN/$f.png" -vf "$CROP" "/tmp/$f.raw.png"; done
  make_gif "$OUT/demo-hit.gif" "$HOLD" 0.6 /tmp/hit-a.raw.png /tmp/hit-b.raw.png
else
  echo "  （hit-a/hit-b.png 不全，跳过）"
fi

echo "== 社交卡 og.png（1200x630）=="
if [ -f "$IN/shot01.png" ]; then
  FONT=/usr/share/fonts/truetype/eta/NotoSansCJK-Regular.ttc
  ffmpeg -y -loglevel error -i "$IN/shot01.png" -vf "$CROP,scale=-1:560" /tmp/og-shot.png
  ffmpeg -y -loglevel error -f lavfi -i "color=c=0x0B0B0F:s=1200x630" \
    -i /tmp/og-shot.png -filter_complex \
    "[0:v]drawtext=fontfile=$FONT:text='WPAForge':x=70:y=150:fontsize=62:fontcolor=0xFFB800,\
drawtext=fontfile=$FONT:text='安卓原生 · WPA/WPA2 密码审计工具':x=70:y=250:fontsize=28:fontcolor=0xF5F5F7,\
drawtext=fontfile=$FONT:text='按 SSID 与线索现场派生候选，逐条真实关联验证':x=70:y=305:fontsize=21:fontcolor=0xA5A5B1,\
drawtext=fontfile=$FONT:text='需要 Root · 仅限自有或已获授权的网络':x=70:y=345:fontsize=19:fontcolor=0x6E6E7A,\
drawbox=x=70:y=395:w=340:h=3:color=0xFFB800@1:t=fill[bg];\
[1:v]pad=iw+8:ih+8:4:4:color=0x2A2A36[ph];\
[bg][ph]overlay=x=1200-300-70:y=35" -frames:v 1 "$OUT/og.png"
  printf '  %-28s %s KB\n' og.png "$(( $(stat -c%s "$OUT/og.png") / 1024 ))"
else
  echo "  （缺 shot01.png，跳过）"
fi

echo "完成 → $OUT"
