#!/system/bin/sh
# WPAForge 日志标记可见性自检（设备侧采集器）
#
# 目的：只回答一个问题 —— 在这台设备与**生产读取路径**下，AttemptMarker 写进去的
# 起点标记能不能被看到。整套日志类判据都建立在它可见之上（isolated）。
#
# 安全边界（硬要求，任何人改动本脚本都必须保持）：
#   · 不发起任何 Wi-Fi 操作：不连接 / 不断开 / 不改开关 / 不使用候选密码
#     （脚本内不得出现 connect-network、forget-network、set-wifi-enabled、
#       svc wifi、disconnect、reconnect、list-networks）
#   · 只写一个**合成**标记：id 里 SSID 段固定为 selfcheck 的哈希，与真实目标无关
#   · 不改判定契约：isolated 的语义由 Java 侧决定，本脚本不参与判定
#   · 结束时清空日志缓冲（与每条候选前的清空是同一个动作），不留污染
#
# 本脚本**只采集 KEY=VALUE 观测**，不做判定。判定交给生产分类器：
#   java -cp <classes:android.jar> io.github.wlmosv_png.wpaforge.audit.MarkerSelfCheck obs.txt
#
# 用法：sh marker-selfcheck.sh [输出文件]     默认写到 stdout
#
# ── 与生产一致的三个常量（改动必须同步 Java 侧，有守卫测试钉住）─────────────
MARKER_TAG="WPF_PROBE"                       # = AttemptMarker.TAG
MARKER_FIELD="attempt="                      # = AttemptMarker.FIELD
SHELL_FILTER='associate|4-Way|RX message|negotiat|ASSOC-REJECT|SSID-TEMP-DISABLED|CONNECTED|WRONG_KEY|reason=|attempt='
                                             # = WpaLogReader.SHELL_FILTER（逐字一致）
SELFCHECK_SSID_HASH="60578b9b"               # = sha256("selfcheck")[:8]
# ────────────────────────────────────────────────────────────────────────────

OUT_FILE="$1"
emit() { printf '%s\n' "$1"; }

# 合成 id：<秒级十进制>-<SSID哈希8位>-<序号>#<候选号>
# （十进制数字属于 AttemptId 允许的字符集，见 AttemptId.isAlnum36）
MARKER_ID="$(date +%s)-${SELFCHECK_SSID_HASH}-0#1"
MARKER_LINE="${MARKER_TAG}|${MARKER_FIELD}${MARKER_ID}"
WRITE_CMD="log -t ${MARKER_TAG} \"${MARKER_FIELD}${MARKER_ID}\""
READ_CMD="logcat -d -s wpa_supplicant -s ${MARKER_TAG} | grep -iE '${SHELL_FILTER}'"
BROAD_CMD="logcat -d | grep -F '${MARKER_FIELD}${MARKER_ID}'"
# 原始读取文本落盘路径（供生产匹配器验证真机原文）
READ_RAW_FILE="/data/local/tmp/marker-selfcheck-read.txt"
BROAD_RAW_FILE="/data/local/tmp/marker-selfcheck-broad.txt"

emit "ran=1"
emit "marker_id=${MARKER_ID}"
emit "marker_line=${MARKER_LINE}"
emit "write_command=${WRITE_CMD}"
emit "read_command=${READ_CMD}"
emit "read_raw_file=${READ_RAW_FILE}"
emit "broad_raw_file=${BROAD_RAW_FILE}"
emit "broad_command=${BROAD_CMD}"
emit "filter=${SHELL_FILTER}"

# 1) 控制命令：证明通道能跑命令（与 Wi-Fi 无关）
CONTROL="$(id -u 2>&1)"
emit "control_out=$(printf '%s' "$CONTROL" | tr '\n' ' ')"

# 2) 写标记的机制是否存在
WHICH="$(command -v log 2>/dev/null || which log 2>/dev/null)"
if [ -n "$WHICH" ]; then emit "log_binary_found=1"; else emit "log_binary_found=0"; fi

# 3) 写标记
WRITE_OUT="$($WRITE_CMD 2>&1)"
if [ -n "$WRITE_OUT" ]; then
  emit "write_frame_ok=1"
  emit "write_output=$(printf '%s' "$WRITE_OUT" | tr '\n' ' ')"
else
  emit "write_frame_ok=1"          # 命令无输出是正常的（log 成功时不打印）
  emit "write_output="
fi

# 4) 用**生产读取命令**读回（重试 3 次，覆盖"写完立刻读还看不到"）
READ_OK=0; READ_CHARS=0; READ_LINE=""; ATTEMPTS=0
i=1
while [ "$i" -le 3 ]; do
  ATTEMPTS=$i
  READ="$(eval "$READ_CMD" 2>/dev/null)"
  printf '%s\n' "$READ" > "$READ_RAW_FILE"
  READ_CHARS=$(printf '%s' "$READ" | wc -c | tr -d ' ')
  if printf '%s' "$READ" | grep -qF "${MARKER_FIELD}${MARKER_ID}"; then
    READ_OK=1
    READ_LINE="$(printf '%s' "$READ" | grep -F "${MARKER_FIELD}${MARKER_ID}" | head -1)"
    break
  fi
  i=$((i + 1))
  sleep 0.3 2>/dev/null || sleep 1
done
emit "read_attempts=${ATTEMPTS}"
emit "read_chars=${READ_CHARS}"
if [ "$READ_OK" = "1" ]; then
  emit "read_frame_ok=1"; emit "read_saw_marker=1"
  emit "read_matched_line=$(printf '%s' "$READ_LINE" | tr '\n' ' ')"
else
  emit "read_frame_ok=1"; emit "read_saw_marker=0"
  emit "read_matched_line="
fi

# 5) 宽读：不加 tag、不加 grep -E —— 用于区分「过滤不匹配」与「标记不可见」
if [ "$READ_OK" != "1" ]; then
  BROAD="$(eval "$BROAD_CMD" 2>/dev/null)"
  printf '%s\n' "$BROAD" > "$BROAD_RAW_FILE"
  BROAD_CHARS=$(printf '%s' "$BROAD" | wc -c | tr -d ' ')
  if printf '%s' "$BROAD" | grep -qF "${MARKER_FIELD}${MARKER_ID}"; then
    emit "broad_frame_ok=1"; emit "broad_chars=${BROAD_CHARS}"; emit "broad_saw_marker=1"
  else
    emit "broad_frame_ok=1"; emit "broad_chars=${BROAD_CHARS}"; emit "broad_saw_marker=0"
  fi
fi

# 6) 清空缓冲（与每条候选前同一动作），不留污染
if logcat -c 2>/dev/null; then emit "buffer_cleared=1"; else emit "buffer_cleared=0"; fi

emit "# 提示：标记可见性通过只证明标记机制在该设备与该读取路径下可用，"
emit "#       不能证明判定链已通过真实网络场景验证。"
