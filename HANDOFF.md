# WPAForge 交接单（2026-10-23）

> 给下一个会话的自己看。照这份就能直接接着干，不需要重新排查。

---

## 一、当前状态一览

| 项 | 值 |
|---|---|
| 设备 | OnePlus PJX110 / Android 15 / ColorOS 15，Magisk root，**不要重启** |
| 项目（Linux） | `/workspace/promo-wpaforge` |
| 同一目录（Android） | `/data/local/tmp/eta/promo-wpaforge`（**同一挂载**，Linux 改完 Android 立即可见） |
| 分支 / HEAD | `main` / **`3f349a7`**（本轮 commit，**尚未推送远端**） |
| 版本号 | `92` / `1.9.22`（`1-aapt2.sh` 里 `WP_VERSION_CODE` / `WP_VERSION_NAME`） |
| 已装机 | `/data/local/tmp/wpaforge-v2.apk`（用户手上跑的就是它） |
| 门禁 | 37 个测试类全部通过 |

---

## 二、本轮做完的（commit `3f349a7`）

### ① 后台不跑 → 保活从未生效（已修，实测通过）
**根因**：`CrackService.showKeepAliveOverlay()` 是从工作线程 `wpaforge-crack` 调用的，
而 `WindowManager.addView()` 内部要 `new ViewRootImpl → new Handler`，
要求线程已 `Looper.prepare()`。工作线程没有 Looper，**每次必抛**：

```
java.lang.RuntimeException: Can't create handler inside thread
  Thread[wpaforge-crack,5,main] that has not called Looper.prepare()
```

异常被 `catch(Throwable)` 压成一行「悬浮窗不可用: RuntimeException」，根因丢失 ——
所以从上线起**一次都没成功过**（翻遍 10-22~10-23 所有任务日志，无一例外）。

**修了**：
- `addView` / `removeView` 一律 `new Handler(Looper.getMainLooper()).post(...)`
- 新增**静音音频保活**：`AudioTrack` 8000Hz 单声道、全 0 PCM 循环写，不申请音频焦点
- manifest 加 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `foregroundServiceType="connectedDevice|mediaPlayback"`（0x12）
- `startForeground` 显式带类型（API>=29 分支）

**实测**：切后台 53 秒 → 日志 +12 行、队列 offset 1→11；
进程 `oom_score_adj` **915（缓存级）→ 50（前台服务级）**；后台连跑 43 条未停。

### ② 「越跑越慢」→ 退避阈值定在了正常耗时上（已修）
`audit/BackoffPolicy.SLOW_MS = 6000`，而本机**正常单条就是 6~7 秒**
（connect→M1 3.8s + 前置 0.5s + M1 静默复核 0.6s + 收尾）。
于是「连续 3 条 >=6 秒」在正常运行时**必然成立** → 每条插 0.4~1.5s 冷却 →
更慢 → 更容易触发 —— **恶性循环**（用户日志：20 秒触发 2 次）。
**改为 12000**，测试样本同步 7000/8000/9000 → 13000/14000/15000。

### ③ 暂停后无法恢复（UI 死锁，已修）
`MainActivity`：`boolean canPause = running;` → 暂停时 `running=false` →
「继续」永久 disabled，用户卡死在暂停态，且该 PAUSED 任务连带禁用「开始」。
改为 `running || paused`。

### ④ 附带（上轮遗留，一并提交）
`CrackEngine` 每条候选的**耗时分解** `timing(tAssocMs, tM1Ms, tAuthMs)`，
把「关联 / M1 / 广播」三个节点附在判定结果里，用于定位时快时慢。

---

## 三、没做完的（下一轮直接从这里开始）

### A. 速度：单条仍 ~7 秒，理论下限 3.9 秒
已量出的固定开销（真机）：
```
主循环每轮 = cmd wifi status 44ms  (+ su 包装)
           + logcat -d -s wpa_supplicant   73ms
           + logcat -d -e 'ClientModeImpl' 129ms   ← 正则过滤，贵
每条另有    logcat -c 153ms
su 包装     每次 +23ms
```
**方向**：
1. 两次 logcat 合并为一次读取
2. `-e 'ClientModeImpl'`（正则）换成 `-s <精确 tag>`（`WifiClientModeImpl`）
3. 常驻一个 `su -c sh` 会话，代替每条命令各起一次 su

⚠️ 这三处都在**主循环**里，紧邻判定链 —— **改前先 A/B 拿数据，改后必须回归 37 类单测 + 不假阳性验证**。

### B. 「进其他界面就回连其他 WiFi」
**机制已查清**：每条 `forget` 目标 → 候选池出现真空 →
framework 在 **11 毫秒内**就去连 B212/B214（该环境 38 个保存网络在抢）。
**方向（按风险从低到高）**：
1. **锁定 BSSID**：`ConnectSpec.buildConnect` 已支持 `-b`，
   `Prefs.pinBssid` 已有开关但**默认关**，且日志里显示 `-b 未指定（由系统选 AP）`。
   实证必要：2.4G-3 空口上有 8 个 BSS，系统会挑花眼。
2. 破解期间临时压制其他保存网络，结束恢复（需谨慎，别弄丢用户配置）。

### C. 其他
- commit `3f349a7` **未推远端**（用户未表态，推之前先问）
- 若发 Release，版本号按铁律「线上已发布 + 1」，并**四处同步**
  （`1-aapt2.sh` 的 VER_CODE/VER_NAME、`UpdateChecker`、`module.prop` 若有、`CHANGELOG.md`）

---

## 四、可照抄的操作

### 出包（两段）
```bash
# 第一段：Android 侧（必须用宿主路径！/workspace 只存在于 Linux chroot）
cd /data/local/tmp/eta/promo-wpaforge && sh 1-aapt2.sh

# 第二段：Linux 侧
cd /workspace/promo-wpaforge
WP_FORGE_KS="/storage/emulated/0/工程tg/签名材料/release.jks" \
WP_FORGE_KS_PASS="wlmosv@2026" \
WP_FORGE_KS_ALIAS="wlmosv" \
sh 2-build-dex.sh
```

### 装机
```bash
cp /data/local/tmp/eta/promo-wpaforge/build/out/wpaforge-signed.apk /data/local/tmp/wp.apk
chmod 644 /data/local/tmp/wp.apk
pm install -r /data/local/tmp/wp.apk
```

### 单测（全量）
```bash
cd /workspace/promo-wpaforge
AJ="/storage/emulated/0/Download/TGAutoSign-build/tools/libs/android.jar"
rm -rf /tmp/tc && mkdir -p /tmp/tc
javac --release 17 -encoding UTF-8 -nowarn \
  -cp "$AJ:app/libs/shizuku-api.jar:app/libs/shizuku-provider.jar:app/libs/shizuku-aidl.jar" \
  -d /tmp/tc $(find app/src/main/java -name "*.java") $(find build/out/gen -name "*.java")
java -cp "/tmp/tc:$AJ" io.github.wlmosv_png.wpaforge.BackoffPolicyTest   # 单个
```

### 免 GUI 起停任务（省掉一路点界面）
```bash
am start-foreground-service -n io.github.wlmosv_png.wpaforge/.CrackService \
   -a io.github.wlmosv_png.wpaforge.START     # 亦可 PAUSE / STOP
```

### 关键路径
| 内容 | 路径 |
|---|---|
| 任务日志（最有价值） | `/sdcard/Android/data/io.github.wlmosv_png.wpaforge/files/logs/WPAForge-log-*.txt` |
| 队列 | `/data/data/io.github.wlmosv_png.wpaforge/files/queue.json` |
| App prefs | `nsenter -t 1 -m -- cat /data/data/io.github.wlmosv_png.wpaforge/shared_prefs/wpaforge_prefs.xml` |
| 改队列前 | 先 `am force-stop`；改完 `chown u0_a601:u0_a601` + `chmod 600`；Android 侧无 python3，用 `sed -i` |

### 测速三件套（本轮写的，在 `/data/local/tmp/eta/`）
- `simapp.sh` —— 复刻 App 完整路径测单条耗时（前置 + M1）
- `long.sh` —— 40 条长跑，查是否越跑越慢（结果：**无递减趋势**，唯一一次超时在第 4 条）
- 关键判据来源：`dumpsys wifi` 里
  `CMD_START_CONNECT ... has_ever_connected=? scan_rssi=? scan_freq=?`
  （`scan_freq=-1` = framework 不知道目标信道 → 要全信道扫 → 慢）

---

## 五、踩过的坑（别重犯）

1. **`1-aapt2.sh` 不能在 Linux 侧跑**（`can't cd /workspace/...`）—— 它要用 Android 宿主路径
2. **`2-build-dex.sh` 自带 `set -e`**，任何一步失败就中断；`base.apk` 必须由第一段先产出
3. **密码 <8 字符**会被 `WifiConfigurationUtil.validatePassword` 拒绝，
   报 `Cannot add network with invalid config` → 命令**静默失败**。
   排查时我曾误判成「系统坏了」，务必先看这条
4. **`logcat -f <file> &` 会让脚本挂住**（terminal 等子进程），改后台抓日志要用 `setsid`
5. **busybox ash 里 `case ... & ;;` 会破坏结构**，后面的 `sleep` 被跳过（我因此白跑一轮实验）
6. **App 日志「事件 N: X↑ X↓」来自 `NetworkWatcher`（NetworkCallback），不是 supplicant** ——
   `B212↑↓` 是正常现象，**不代表目标没被尝试**（logcat 已证目标进过 `FOUR_WAY_HANDSHAKE`）
7. **`crack_mode=0` = `MODE_ROOT`**（`MODE_NO_ROOT=1` / `MODE_SHIZUKU=2`）

---

## 六、红线（任何情况都不能碰）

1. **判定链**：成功唯一判据 = **同一行 `WifiInfo` 四条件同时成立**
   （`SSID == 目标` + `Supplicant state: COMPLETED` + 有效 IP + `Net ID` = 本条新建的 netId）。
   本轮所有改动**一行都没动它**。
2. **宁可漏判，绝不误判**（不假阳性优先于不漏判）
3. **不重启设备**（用户明确要求）
4. 用户没同意的重大操作不做
