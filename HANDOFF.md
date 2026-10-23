# WPAForge 交接单（2026-10-23）

> 给下一个会话的自己看。照这份就能直接接着干，不需要重新排查。

---

## 一、当前状态一览

| 项 | 值 |
|---|---|
| 设备 | OnePlus PJX110 / Android 15 / ColorOS 15，Magisk root，**不要重启** |
| 项目（Linux） | `/workspace/promo-wpaforge` |
| 同一目录（Android） | `/data/local/tmp/eta/promo-wpaforge`（**同一挂载**，Linux 改完 Android 立即可见） |
| 分支 / HEAD | `main` / **`b31db27`**（本轮 commit，**尚未推送远端**） |
| 版本号 | `92` / `1.9.22`（`1-aapt2.sh` 里 `WP_VERSION_CODE` / `WP_VERSION_NAME`） |
| 已装机 | `/data/local/tmp/wp.apk`（= `build/out/wpaforge-signed.apk`，2,931,512 B） |
| 门禁 | 37 个测试类 / **947 例全过** |

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

## 二·补、本轮下半场：**暂停从来没停过**（真机复现 → 五处修掉）

**用户点「暂停」，界面显示已暂停，引擎一刻没停。**

**根因**：`CrackService.pause()` 只通知了免-root 执行器（`prober.pause()` 与反射调
`sugProberRef`），**从来没有调 `engineRef.pause()`**。Root / Shizuku 模式下这两个引用都是
`null` → 暂停退化成纯 UI 动作：队列状态写成 PAUSED、日志写「正在暂停…」，引擎毫不知情。

**修复前实测（同一次运行）**：08:38:21 点暂停 → 日志仍每 2~6 秒一条推进到 08:40:33，
队列 offset **82 → 158**（多跑了 76 条）。

| # | 位置 | 改了什么 |
|---|---|---|
| ① | `CrackService.pause()` | 补 `engineRef.pause()` —— 暂停真正到达引擎 |
| ② | `finishTask` 的 PAUSED 分支 | **以盘上状态为准**：收尾期用户点了继续（盘上已 PENDING）就不再写回 PAUSED |
| ③ | `saveProgress` → `TaskQueue.updateProgress` | 进度落盘**只写 offset/elapsedMs**，不再拿内存快照整对象回写（旧写法会把用户刚点出来的 PENDING 盖回 PAUSED） |
| ④ | `startQueue` + `runQueue` 收尾 | worker 还活着时点继续会被「已有任务在运行」静默吞掉 → 一次性 `restartWanted`，收尾时接上 |
| ⑤ | `CrackEngine` 主循环 | 中断发生在候选执行到一半时**回退游标**，不再把它算作「试过了」（属漏判） |

**修后实测**（受控，全程免 GUI，靠 `am start-foreground-service -a ...PAUSE/START` + 改 queue.json）：

- 暂停：08:48:31 点暂停 → 08:48:32「已暂停，进度 173/3336」→「任务处于暂停，跳过」→
  「队列已结束」，offset 连续 24 秒不动
- 收尾期点继续：日志出现「暂停收尾期间已点过「继续」，保持待运行」→「===== 开始任务」→
  「从第 184 条继续」，offset 184 → 189 继续涨
- 单测 37 类 / **947 例 / 0 失败**

**顺带修掉门禁假绿**：旧 `wpf-test.sh` 只认「N 通过 / N 失败」，而早退/广播两类输出的是
`pass=24 fail=0` → 这两类恒记 0/0，**连它们的失败也一起漏判**。新版 `/workspace/wpf-gate.sh`
（可传项目根参数）两种格式都认，且**解析不到任何结果就判红**。

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
- 本轮 commit **未推远端**（用户未表态，推之前先问）
- 若发 Release：已查 GitHub releases，线上最新是 **`v1.9.21`** → `1.9.22 / 92` 正是
  「线上 + 1」，不涨版本号也对；四处同步（`1-aapt2.sh` 的 VER_CODE/VER_NAME、
  `UpdateChecker`、`module.prop` 若有、`CHANGELOG.md`）

### D. 新发现：应用不在前台时 WifiInfo / NetworkCallback 拿不到 SSID（**未修**）
用 `am start-foreground-service` 起任务（界面没开）时，通道自检报
`WifiInfo 信号: 读不到`，`NetworkWatcher` 的诊断是
`SSID unavailable every time (getTransportInfo also unavailable)` —— **事件监听这一层等于瞎的**。
同一台机器同一天，用户开着界面跑的那次（08:34）是正常的（`✓ WifiInfo 信号: B212`，
日志有「事件 N: B212↑ B212↓」）。
已排除：权限都 granted（`inspect_app`），系统定位总开关是开的（`get_device_state`）。
怀疑是「仅在使用时允许」的定位授权 + 后台进程被框架隐去 SSID。
**主判定链走 root 的 `cmd wifi status`，不受影响**（08:46~08:57 那几轮照样判、照样推进），
但日志的「事件 N」行会消失、有的一层证据失效。方案待定。

### E. 另外注意：任务日志会被切成多个文件
`executeTask` 每次进任务会新开一个 `WPAForge-log-*.txt`，所以一轮实验的日志可能分散在
两三个文件里（08:56:15 与 08:56:22 就是同一轮的两段）。**看日志要按 mtime 连着看几个**，
只 `head -1` 会漏掉「正在暂停」「开始任务」这些关键行。

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
