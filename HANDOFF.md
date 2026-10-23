# WPAForge 交接单（2026-10-23）

> 给下一个会话的自己看。照这份就能直接接着干，不需要重新排查。

---

## 一、当前状态一览

| 项 | 值 |
|---|---|
| 设备 | OnePlus PJX110 / Android 15 / ColorOS 15，Magisk root，**不要重启** |
| 项目（Linux） | `/workspace/promo-wpaforge` |
| 同一目录（Android） | `/data/local/tmp/eta/promo-wpaforge`（**同一挂载**，Linux 改完 Android 立即可见） |
| 分支 / HEAD | `main`（`git log -1` = 本轮 commit，**尚未推送远端**） |
| 版本号 | `93` / `1.9.23`（唯一来源是 `1-aapt2.sh` 的 `WP_VERSION_CODE` / `WP_VERSION_NAME`；APK 清单不写 versionName，`Ver.java` 一律读 PackageManager，所以只改这一处） |
| 已装机 | `/data/local/tmp/wp.apk`（2,947,896 B；sha256 `a50b12af…`；**93/1.9.23**） |
| 门禁 | 41 个测试类 / **1003 例全过**（`/workspace/wpf-gate.sh`） |

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

---

## 二·再补、速度：**4.9 秒 → 2.4 秒（~2.1×）**（真机实测）

### 先装表，再动手
后台跑任务时界面没开、逐条结果不进日志 —— 以前只能靠猜。
新增 `CostMeter`：**每 20 条一行汇总**（均值 + 四段分解 + 判定归类），
3336 条的字典只多 167 行，不破降噪。四段＝前置 / netId / 轮询 / 读日志。

### 第一版分解就把方向指出来了：**不是命令慢，是起进程贵**

| 分段 | 修前 | 修后 |
| --- | --- | --- |
| 前置 | 1071 ms | 397 ms |
| netId | 378 ms | 196 ms |
| 轮询（status + logcat） | 971 + 998 ms | 374 + 659 ms |
| **合计** | **4427~4756 ms** | **2276~2342 ms** |

关键实测：同一条 `cmd wifi status`，**命令行起 27ms，App 里起 137ms**；
`CmdLine`/`su` 本身都不慢 —— 慢的是「在 App 这个大会话里 fork + su 包装」。
每条候选 ~28 次，于是 3.8 秒全花在起进程上。

### 三处改动
1. **常驻 `su -c sh` 会话**（`audit/ShellSession.java`）：哨兵分帧
   （`__WPF_END__<seq>__$?`）、超时杀整棵会话、连续 3 次失败就退回一次性 `su`。
   实测 88ms/次 → **20ms/次**；`轮均 137ms → 34ms`。
   会话里连打 30 条实测 609ms vs 30 次独立 su 2656ms。
2. **日志服务端预过滤**：一轮原始 293 行 / 33KB，判定真正要用的只有 5 行 / 0.6KB。
   `WpaLogReader.read` 改成 `logcat -d -s wpa_supplicant | grep -iE 'SHELL_FILTER'`。
   不变式：过滤式必须覆盖全部 7 个判定模式 —— `WpaFilterTest` 逐条钉住。
3. **`forget` 的 `sleep 0.3` → 0.15**：实测 0.05 就已经读不到旧配置（5/5），
   取 0.15 留余量；万一读回了旧配置，校验会让它多跑一轮（只慢，不误判）。

### 一个被实测否掉的「优化」（记下来别重犯）
把一轮里的 `status` + `logcat` 合并成一条命令（哨兵分隔）想省一次往返 ——
**反而更慢：79ms/轮 vs 分开 44ms/轮**。33KB 的日志和 status 抢同一个管道。
已回退。**教训：合并命令只在两段输出都很小的时候才有收益。**

### 顺带修掉的队列数据丢失链（当天真实发生）
队列 16 个任务（含 5 条已爆破口令）被写成 `[]`。链条：
`queue.json` 读不到（如 SELinux 标签不对）→ `load()` 静默返回空 → 调用方回写空 → 队列永久消失。
现在 `load` 失败留痕、**拒绝接下来的空写入**，并在写空前备份 `queue.json.bak`。
`QueueGuardTest` 钉住边界（用户主动清空不受影响）。

## 三、没做完的（下一轮直接从这里开始）

### A. 速度：已做到 2.4 秒/条；剩下这些**还没做**（都量过）
当前 2.4 秒的结构（实测，见上面那张表）：
```
前置 400ms（6 次会话命令 + forget 的 0.15s 等待）
netId 200ms（发令后 200ms 窗口轮询「本条新建的 netId」）
轮询 375ms + 读日志 659ms（约 10 轮 × (37+64)ms）
等待 约 600ms（M1 静默判据窗口，判定链参数，别动）
```
还能榨的（按性价比排）：
1. **前置 6 次命令合并成 3 次**（~250ms）。要动的是「删净回读 + 断开确认」这道
   假阳性闸门，**必须 A/B + 跑一轮真机看有没有假阳性**，别图快。
2. **netId 探测 200ms → 一次即时探测**（~170ms）。这个值在判定链里
   **只写进证据串、从不作否决**（`SuccessRule` 的入参里没有它），压掉只影响诊断信息。
3. 每轮 logcat 读仍占 64ms（预过滤后传输只有 277 字节，剩下的是 `logcat -d` 自身
   扫环缓冲区）。想再快得换思路（常驻 logcat 落盘 + 每轮读增量），复杂度高一档。

⚠️ **改前 A/B、改后回归门禁 + 不假阳性验证**；判定链（四条件同行）一行不能动。

### B. 「进其他界面就回连其他 WiFi」
**机制已查清**：每条 `forget` 目标 → 候选池出现真空 →
framework 在 **11 毫秒内**就去连 B212/B214（该环境 38 个保存网络在抢）。
**方向（按风险从低到高）**：
1. **锁定 BSSID**：`ConnectSpec.buildConnect` 已支持 `-b`，
   `Prefs.pinBssid` 已有开关但**默认关**，且日志里显示 `-b 未指定（由系统选 AP）`。
   实证必要：2.4G-3 空口上有 8 个 BSS，系统会挑花眼。
2. 破解期间临时压制其他保存网络，结束恢复（需谨慎，别弄丢用户配置）。

### C. 其他
- 本轮 commit **已推远端**（用户 2026-10-23 明确说「先 commit」）
- **`v1.9.23` 已于 2026-10-23 发布**（tag `v1.9.23` → `c77cc05`；正文取仓库根目录
  `RELEASE.md`；资产 `WPAForge-1.9.23.apk` 2,947,896 B / sha256 `a50b12af…` ——
  已下载回来核对，**与装机那版字节一致**）。
  页面：`https://github.com/wlmosv-png/wpaforge/releases/tag/v1.9.23`
- **下一版基线 = `v1.9.23`**：再改代码时版本号按铁律「线上已发布 + 1」→ **`1.9.24 / 94`**，
  唯一真相是 `1-aapt2.sh` 的 `WP_VERSION_CODE` / `WP_VERSION_NAME`
  （APK 清单不写 versionName，`Ver.java` 读 PackageManager，所以只改这一处）。
- **发版动作照这次来**：正文用 `RELEASE.md`（标题写在文件首行注释里）+ 附
  `WPAForge-<版本>.apk`（历次 Release 都附）。发完顺手核：tag 指向 HEAD、
  下载回来 sha256 与装机包一致。

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

### 队列读写（**别再用 `sed -i` 改 App 私有文件**）
```bash
sh /data/local/tmp/eta/wp-queue.sh out    # 取出到 /data/local/tmp/q.work.json
#   （Linux 侧用 python3 改 JSON，写成 /data/local/tmp/q.new.json）
sh /data/local/tmp/eta/wp-queue.sh in     # 写回（cat 覆写保 inode + chown + restorecon）
```
`sed -i` / `mv` 会产生**新 inode 并丢掉 SELinux 的 per-app 类别标签**
（`:c89,c258,c512,c768`），App 就读不到自己的队列 —— 而 `load()` 读不到时按空队列处理，
一次误触发就把整个队列写没了（2026-10-23 真发生过）。

### 门禁（**只留在本地，不写进公开更新日志**）
- `wpf-test.sh` 曾把 javac 输出管给 `tail`，**退出码丢了** —— 实测编译报 10 个错，
  汇总照样输出「870 通过 / 0 失败」（假绿）。新脚本非零直接判红并打印错误行。
- 另一类假绿：早退/广播两类输出的是 `pass=N fail=N`，旧脚本只认「N 通过 / N 失败」
  → 这两类恒记 0/0，**连失败也一起漏判**。`/workspace/wpf-gate.sh` 两种格式都认，
  且**解析不到结果就判红**。
- 当前：41 类 / 1003 例 / 0 失败。

### 性能测量四件套（在 `/data/local/tmp/eta/`）
- `wp-cost.sh` —— 逐项量 shell 调用成本（**App 在跑时也量一遍**，对比现场值）
- `wp-profile.sh` —— 复刻单条候选路径并分段计时（前置 / 口令→M1 / 静默判定）
- `wp-cpu.sh` —— 采样 App 的 utime+stime：**CPU 占比低＝在等，高＝在算**（判方向用）
- `wp-sess-probe.sh` —— 验证常驻会话可行性 + 量化收益（独立 su vs 一个会话）
- 看分析的入口：任务日志里的 `近 20 条 · 均 …（前置 … · netId … · 轮询 …）` 汇总行

### 测速三件套（上一轮写的，在 `/data/local/tmp/eta/`）
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
8. **别用 `sed -i` / `mv` 改 App 私有文件** —— 换 inode 会丢 SELinux per-app 标签，
   App 读不到；配合「读失败静默当空队列」就变成一次静默清库。用 `wp-queue.sh`。
9. **合并 shell 命令不一定更快**：一轮里合并 status+logcat 反而慢（79ms vs 44ms），
   因为 33KB 的输出会和前一条命令抢管道。**只在两段输出都小的时候才合并。**
10. **App 里起一次 `su` 是 137ms，命令行只要 27ms** —— 差距在「大 JVM 里 fork」。
    凡是「每条候选要调几十次 shell」的活，先想常驻会话，别急着优化命令本身。

---

## 六、红线（任何情况都不能碰）

1. **判定链**：成功唯一判据 = **同一行 `WifiInfo` 四条件同时成立**
   （`SSID == 目标` + `Supplicant state: COMPLETED` + 有效 IP + `Net ID` = 本条新建的 netId）。
   本轮所有改动**一行都没动它**。
2. **宁可漏判，绝不误判**（不假阳性优先于不漏判）
3. **不重启设备**（用户明确要求）
4. 用户没同意的重大操作不做
