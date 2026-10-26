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
| 版本号 | `94` / `1.9.24`（唯一来源是 `1-aapt2.sh` 的 `WP_VERSION_CODE` / `WP_VERSION_NAME`；APK 清单不写 versionName，`Ver.java` 一律读 PackageManager，所以只改这一处） |
| 已装机 | `/data/local/tmp/wp.apk`（2,837,304 B；sha256 `c2759527…`；**94/1.9.24**） |
| 门禁 | 40 个测试类 / **990 例全过**（`/workspace/wpf-gate.sh`，含站点链接门禁） |

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

---

## 二·三、1.9.24：**Shizuku 与免 Root 通道彻底移除**（用户 2026-10-23 要求）

公开口径一直是「需要 Root」，README 从 1.8.x 就写着 Shizuku / 免 Root 已移除 ——
但代码里那条管线**没删干净**（界面还留着可点的「改用 Shizuku」、`Prefs.autoMode()` 还会
兜底到 SHIZUKU/NO_ROOT）。这轮连根拔掉：

| 删掉的东西 | 说明 |
| --- | --- |
| `audit/ShizukuRunner` | 通道本体 |
| `shizuku/SafeShizukuProvider` | 清单里的 ContentProvider（binder 入口） |
| `audit/SpecifierProber`、`audit/SuggestionProber`、`audit/ConnectionProber` | 免 Root 三条实现 |
| `app/libs/shizuku-{api,provider,aidl}.jar` | **本应用不再依赖任何第三方库** |
| AndroidManifest | Shizuku 权限 / 包可见性 `<queries>` / V3 meta-data / Provider 全撤 |
| 界面残留 | 失败弹窗的模式切换、设置页授权引导、队列行模式标签、`REQ_SHIZUKU` 回调 |
| `Prefs` | `MODE_NO_ROOT` / `MODE_SHIZUKU` 常量、`autoMode()`、`warmUp()`、`setMode()` |
| `CrackEngine` | `tryOnePrecise`（requestNetwork 回调判定）、`submitBatchToSystem` 等批量接口、binder 直调分支、`cm` 字段 |
| `App` | Sui 关闭、binder 早注册、Shizuku 探针与启动自检段 |
| `RootProber` | `useShizuku` 分支、`checkShizuku`、`execAsync` 的 Shizuku 分支 |
| 测试 | `SpecifierTest`（免 Root 路径的用例，随功能删除） |

**兼容**：队列里老任务存的旧模式值（1=免 Root / 2=Shizuku）一律按 Root 处理 ——
`Prefs.mode()` 现在恒返回 `MODE_ROOT`，不会因为一个历史值落到一条已不存在的通道上。

**验收证据（都可复现）**：

- 包内 **shizuku 字样 0 处**：`unzip -l` 0 命中、`classes.dex` 0 命中、`aapt2 dump xmltree AndroidManifest.xml` 0 命中
- 体积 **2,947,896 → 2,837,304 B（−110,592）**，正是那三个 jar 的大小
- 应用自检（`startup-last.txt`）：`版本: 1.9.24` / `Root 通道可用: 是` /
  `结论: 已接入`（6 项全过，`cmd wifi` 实测 78ms）
- 门禁 40 类 / 990 例 / 0 失败

⚠️ 遗留待办：`CrackEngine.verifyHit()`（命中复核）**没有任何调用方**，是更早那轮
「去掉复验一票否决」留下的死代码；`RootProber` 里那几个 `MIN_WAIT_MS/MAX_WAIT_MS/POLL_MS`
常量与它的旧 `tryOne` 循环同理。本轮**没动**（超出「删 Shizuku」范围），下轮可一并清理。

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
- **下一版基线 = `v1.9.24`**（本版尚未发 Release）：再改代码按铁律「线上已发布 + 1」，
  但 `1.9.24` 还没发，所以**先把它发出去**再谈 95；
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
WP_FORGE_KS_PASS="<在本地未入库的 env 文件里设置，勿写进仓库>" \
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
- **站点链接门禁** `tools/check-site-links.py`（已接进 `/workspace/wpf-gate.sh`）：
  ① 构建脚本版本是唯一真相；② README 两份的出包示例必须等于它；
  ③ 页面/README **不许出现版本钉死的 Release 地址**（`/releases/tag/vX`、`/releases/download/vX/…`），
     一律用 `/releases/latest`；④ 落地页必须保留永久直链
     `…/releases/latest/download/WPAForge.apk`；⑤ `RELEASE.md` 的 tag 必须等于本版。
  加 `--online` 可联网核对 Release 与两个资产是否都在。
- **发布时记得给新 Release 也传一份版本无关的别名资产 `WPAForge.apk`**，
  永久直链才不会断（v1.9.23 已加：Release 里同时有 `WPAForge-1.9.23.apk` 与 `WPAForge.apk`）。
- 踩过的坑：`io.open(path, "w", newline=<非法值>)` 会**先截断文件再抛异常** ——
  我因此把 `wpf-gate.sh` 写空过一次。改脚本要么用真编辑器，要么先拼好内容再写。

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

## 安全：签名口令曾在公开历史里（2026-10-24 最终审计 P0-4）

**结论（有据可查）**：keystore 口令曾以明文写进仓库并**已公开** ——
`HANDOFF.md` 第 107 行 `WP_FORGE_KS_PASS="…"`，所在提交 `3b488fc` **在 `origin/main` 上**。
另有一处更早的本地提交 `9aaa21e`（0.5.0，`2-build-dex.sh` 内联）**未推到远端**。

**泄露范围**：
- 泄的是**口令**，不是密钥。仓库从未提交过 `.jks`（只有 `signing.env.example` 模板），
  所以要伪造本应用的更新包，攻击者还需要那份 keystore 文件本身。
- 含义：一旦 keystore 文件外泄，口令即已知；且该口令同时是 store 与 key 的口令。

**已做的处置**：
- 仓库内：`7f7c1e4` 起源码/文档里已无该口令（`.gitignore` 覆盖 `*.jks` / `signing.env`）。
- 本机非仓库副本：19 个文件（含构建脚本、skill 文档、我的 diff 快照）已改为从环境变量读
  （`TGAS_KS_PASS` / `WP_FORGE_KS_PASS`），不再内联。
- Eta 记忆：5 处已改写为「口令不写进记忆 + 待轮换」（复查残留 0）。
- **未做且不建议做**：用字节替换去抹 SQLite 副本里的口令 —— 实测会破坏数据库结构
  （同一份副本：替换前 `PRAGMA quick_check` = ok，替换后 = corrupt；已逐字节还原）。

**待你决定（需要一次显式操作，不能替你选新口令）**：
- 轮换 keystore 口令（**不改密钥，签名指纹不变，老用户仍可覆盖安装**）：
  `keytool -storepasswd -keystore release.jks` 与 `keytool -keypasswd -alias wlmosv -keystore release.jks`
- 轮换后把新口令写进本机未入库的 env 文件；本仓库与记忆都不再记录口令。

## 收尾策略更新：命中保留（R11-9，2026-10-24）

**旧行为**：任务结束一律走标准恢复（删候选残留 → 按原口令重建）。目标本来没存过
（`hadPsk=false`）时，判据把「目标不在列表里」当成正确结果 —— 于是**命中那一下刚连上的网，
跑完立刻被删**：手机掉线、不自动重连，下次还得再破一遍。
现场证据：历史里 `B212` / `hdhd` 有命中记录，`cmd wifi list-networks` 那 78 条里却没有它们。

**新行为**：收尾按本轮结果分流 ——

| 本轮结果 | 收尾动作 | journal |
|---|---|---|
| 命中（`DONE_HIT`） | `KEEP_HIT_CONFIG`：不删，只读回确认配置在列表里 | 写 `STAGE_KEPT` 并清记录 |
| 未命中 / 暂停 / 取消 / 异常 / 启动补做 | 标准恢复：删候选残留 → 原口令重建 → 读回 | 成功清、失败留 `FAILED` |

**代码位置**：`RestorePlan.steps(e, keepHit)` / `judgeKept`、`ConfigRestorer.keepHit` / `settle(hit, e)`、
`RestoreJournal.STAGE_KEPT` + `Entry.settled()`、`RestoreRunner.applyStage`（终态判 `resolved()`，
清记录失败就写终态落盘）、`CrackService.restoreAfterRun(taskId, outcome, hit)`。
**hit 只由任务状态给出（`ft.state == TaskQueue.State.DONE_HIT`），`CrackEngine` 一行未动。**

**两个必须记住的坑**：
1. 保留**必须**把 journal 结掉（`settled()` 认 `KEPT`）。只保留不清记录 → 下次启动的补做会把刚留下的正确配置删掉；
2. 保留路径里**不许出现任何删除动作**（`RestoreKeepHitTest` 有源码级守卫）；
   保留不成立（回读不到配置）时必须退回标准恢复，不许留「既没保留也没清理」的中间态。

**测试**：`RestoreKeepHitTest`（63 项）= 纯逻辑（步骤计划 / 判据 / journal 终态）
+ 源码接线守卫 + **shell 层**（给 `ConfigRestorer` 加了 `Shell` 注入口，假壳真跑
`keepHit` / `settle`，断言「保留路径一次 `forget-network` 都没发」）；门禁 **2243 通过 / 0 失败**。

**已知边界（未修）**：`cmd wifi list-networks` 会把个别 SSID 打成带引号的 `"B208"`，
而 `ListNetworksParser` 只认不带引号的形式 → 这类目标保留判不出来，退回标准恢复
（＝改动前行为）。该解析器同时是判定链的假阳性防线，按「不动判定链」保持原样。

## 恢复机制的真机验证方案（2026-10-24，最终审计 P1；已实跑 A/B 两例）

**脚本（可重复）**：`/data/local/tmp/eta/restore-selftest.sh A|B`（Android root；文件操作走 `nsenter -t 1 -m --`，因为 Eta 自己的 NS 看不到别的包）

**为什么这个方案安全**：目标用**虚构 SSID**（`WPF-SELFTEST-NOPE`）—— 它不是任何已保存网络，
记录里 `originalNetId=-1`；收尾的「删候选残留」对不存在的 netId 是空操作，并会回读确认目标不在列表里。
全程不碰真实网络配置、不跑候选口令。

| 例 | 盘上造什么 | 期望 | 实测（2026-10-24 11:12） |
|---|---|---|---|
| A | `stage=ARMED`、`hadPsk=0` | 判「原本就无需还原」并**清掉**记录 | `WPAForgeRestore: recovery(app-start): 已恢复：OK`，记录被清除 ✓ |
| B | `stage=ARMED`、`hadPsk=1`、密文为空 | 判 `NO-SECRET`，记录**留在盘上** | 记录保留：`stage=FAILED` / `failStep=READD_ORIGINAL` / `failDetail=NO-SECRET · 原口令未能安全保存…`；日志 `未能还原原配置，但已清除候选残留：NO-SECRET（…）` ✓ |

两例都在 `am force-stop` 之后的**冷启动**里由 `RestoreRunner.requestStartupRecovery("app-start")` 完成，
同时覆盖「进程不在、记录在盘上 → 下次启动补做」；跑完删除测试记录，设备恢复原状（复查无残留）。

**仍未验证（保留 NOT RUN，不用单测冒充）**：
- 「用**原口令重建**」分支（`hadPsk=1` + 真密文 + 真实已保存网络）——需要改动真实网络配置，执行边界禁止；
- 恢复**执行过程中**进程被杀（本方案覆盖的是"记录在盘上 + 进程不在"这一等价前提）。

---

## 三、2026-10-26：线索驱动派生（P0+P1 一次做完）

### 起因
酷安有用户评论「自带的密码有点不接地气」。查代码后确认问题比「词表老」更具体：
内置词表是英文数据集 + `[a-z]+日期` 全枚举，而派生链 9 个桶**全部以 SSID 为原料**，
通用弱口令还写在循环末尾当兜底 —— 国内真实分布（手机号、生日、拼音+数字）
反而排在最后或干脆不存在。

### 这轮加了什么
| 项 | 位置 |
|---|---|
| 线索模型（手机号×4 / 固话 / 生日 / 拼音 / 门牌 / 自由词×8） | `audit/SeedModel.java`（新） |
| 线索展开器（含拼音音节切分取首字母 zhangwei→zw） | `audit/SeedDeriver.java`（新） |
| 中文高频表 561 条（谐音数字/拼音词根/姓氏/家庭场景） | `assets/builtin/cn_top.txt`（新）+ `audit/CnHot.java`（新） |
| 桶序：J 用户线索 → K 中文高频 → L 通用弱口令 → A~I 原样 | `audit/TargetDictBuilder.java` |
| 排序加分：线索精确 +1000 / 片段 +500 / USER_SEED +300 / 中文高频 +260 | `audit/SmartSorter.java` |
| 线索随任务落盘（`seedsJson`），续跑靠它重建同一份候选 | `audit/TaskQueue.java` |
| 服务侧按任务里的线索重建候选；日志掩码 | `CrackService.java` |
| 破解页「字典来源」加「已知线索」弹窗；落盘文件名带指纹 | `MainActivity.java` |
| 内置字典释放清单 + `RELEASE_VERSION=3` | `audit/BuiltinDicts.java` |
| 65 例 | `SeedDeriveTest.java`（新） |

### 零回归怎么验的
不填线索时**候选集合必须一模一样**（只允许顺序变化）。做法是拿 HEAD 的旧实现
另编一份，对 4 个 SSID 逐一比对「去重排序后的 SHA-1」：

```
FAST_E972   count=959   set=e365f8db3929   新旧相同
209         count=238   set=fccc16be8ebd   新旧相同
小卖部WiFi5 count=511   set=b068692f6dbd   新旧相同
CMCC-8F2A   count=1441  set=b1237d932fc3   新旧相同
```

顺序变化是预期内的：通用弱口令从「兜底」提到第三桶，所以 `FAST_E972` 的前 6 条
从 `FAST_E972/…` 变成 `12345678/123456789/1234567890/88888888/66668888/888888888`。

### 门禁
Java 侧 **2308 通过 / 0 失败**（本轮 +65）。站点链接门禁 5 处不合格 ——
**改动前就是红的**，原因是站点迁到 `wlmosv.dpdns.org/wpaforge/` 后
`docs/index.html` 变成跳转页，而 `tools/check-site-links.py` 仍在那个文件里
找永久直链与 og 标签。要么让它改看新站源文件，要么把这几项从门禁里摘掉，
**这件事没做，留给下一轮定**。

### 还没做
版本号仍是 **95 / 1.9.25**（`1-aapt2.sh`）。发版时要一次改齐：
`1-aapt2.sh` + `README.md` + `README_EN.md` + `CHANGELOG.md` + `RELEASE.md`
（tag 注释）+ 站点，然后才出包。本轮只提交功能代码。

---

## 四、2026-10-26 下半场：派生推倒重做（画像 v2）

### 用户否决 v1 的理由（成立）
对手机号 `19395408514` 生成 745 条，前 40 条里一堆
`40851440 / 40851400 / 40851488 / 40851411 / 00408514 / 9540851495408514`。
用户原话：**"这种密码谁会去设置？"**
根因：把"线索"当另一个 SSID 做字符串变形 + `add()` 里"不足 8 位就补位"的旧启发。

### 扒主流项目后的结论（本轮所有改动的依据）
| 项目 | ★ | 借鉴点 |
|---|---|---|
| Mebus/cupp | 6.6k | 只问"人的信息"；**长度闸门 wcfrom=5 wcto=12** |
| D4Vinci/elpscrk | 964 | 每维度 2~4 种形态；**手机号只有 全号/前四/后四**；复杂度是档位 |
| hashcat rules | — | 变形只有"追加 1/12/123/年份/符号"这几种原子操作（top10_2025.rule 全文 10 行） |
| lakiw/pcfg_cracker | 372 | 输出按概率排序，评价指标是"平均第几次命中" |
| toxydose/pnwgen | 73 | README：**30%+ 用户拿手机号当密码** |

**中文场景没有可抄的项目**（搜到底只有几个 1~2★ 玩具）—— 这块只能自己建。

### 本机真实命中数据（`files/history.csv`，107 次命中，只读脱敏标签与来源字典）
- 命中位置：**中位第 1 条**，90 分位第 3 条，**104/107 在前 50 条内**
- 长度分布：8 位 83 / 9 位 20 / 10 位 4 —— **没有更长**
- 来源：`01 内置弱口令常用` **57 次(53%)**、xato TOP10000 14、`02 纯数字` 10、`2025 TOP199` 8、`05 号码变形` 6
- 单条耗时中位 **3.6 秒**

### 新架构
```
J 画像·原值  ≤20   手机号全号/后8/1+后8、生日8位、拼音(≥8位)、宽带、旧口令
  (手机后4位/前4位只有 4 字符，单独不可能合法 → 只当组合零件)
L 通用弱口令 ≤40   ← 证据密度最高，排在中文高频之前
K 中文高频   ≤30
A~I SSID 派生        **默认关闭**（SeedModel.useSsid 打开；打开后排最后）
```
- `add()` 闸门：**≥8 位、不做任何补位**；纯数字 8~11 位、混排 8~12 位
- 画像三层：VALUE(原值) → COMBO(互拼 ≤24) → MANGLE(追加 ≤20)
- 预算：`Prefs.deriveBudget` 默认 **120**
- 界面：破解页「字典来源」里 **「已知密码线索」独立板块**（画像/字典 用分隔提示分开）

### 实测对比（同一输入 `19395408514` + SSID `B209`）
| | v1 | v2 |
|---|---|---|
| 候选数 | 745 | **33** |
| 预计耗时 | 45 分钟 | **2 分钟** |
| 前 12 条 | 19395408514 95408514 **40851440 40851400 40851488 40851411 00408514** 193408514 **9540851495408514** 195408514 12345678 123456789 | 19395408514 95408514 195408514 12345678 123456789 1234567890 88888888 66668888 888888888 11111111 00000000 |

带上拼音/生日/门牌后 53 条：`… 19900101 zhangwei Zhangwei zhangwei8514 Zhangwei8514 95408514zw zhangwei0101 zhangwei802 zhangwei1990 zhangwei1 zhangwei123 zhangwei520 zhangwei1314 …`

### 门禁
**2313 通过 / 0 失败**（SeedDeriveTest 64、TargetDictTest 34 重写）。
站点链接门禁 5 处仍红 —— 与本轮无关，是站点迁移后遗留（见上一节）。

### 版本
装机器 **97 / 1.9.27**（`1-aapt2.sh` 默认值仍是 95/1.9.25，发版时统一改齐）。
commit：功能代码两笔（`e75c4a5` v1、本次 v2），均**未推送**。

---

## 五、2026-10-26 第三轮：线索按目标分档 + 换字典不再重复劳动

### 用户的两个诉求
1. "每个网络都不一样，不能按照同一套词汇吧" → 线索换网络要不要清空
2. "跑完一个字典再换别的字典，跑过的又跑一遍，这是重复劳动" → 字典融合

### 结论：不是"自动清空"，是"按目标分档"
线索里混了三种性质：人的常量（手机号/生日/拼音）、地点常量（门牌/宽带账号）、
**目标专属（旧口令）**。一换网就全清会误伤同一家的多个 AP；全继承又会拿上一家的信息猜这一家。

所以：`files/profile/<bssid>.seeds` 按目标存 + 一份 `_default.seeds` 默认模板。
- 本目标有档案 → 用它，标注「本目标专用」
- 没有 → 从模板继承（**旧口令永不继承**，见 `ProfileStore.inherit`），
  并在弹窗顶部明确写「下面是默认模板带过来的，不是同一家请先清空」
- 三个动作：保存 / 清空本目标 / 把以上内容设为默认模板

### 结论二：已试集合（`TriedSet`）—— 不重复劳动的地基
- **只存 8 字节哈希，不存原文**；文件 `files/tried/<bssid>.tried`（排序 int64 数组）
- **绝不跨目标共享**：同一候选在 A 网失败 ≠ 在 B 网失败
- **不用 Bloom filter**：假阳性 = 跳过一条真没试过的候选 = 可能漏答案
- **丢失/损坏只降级**：读不出来当空集合，最坏重跑一遍，不会改变结论
- 记的时候**只记有明确结论的**（AUTH_FAIL / SUCCESS）；超时、无结论不记
- 落盘时机：每 32 条 / 暂停 / 任务收尾。`triedKey` 用**载入时**的键，
  不能用"界面上当前的目标"（用户可能排着 B209 的队却切到 B211 看 → 会串档）

### 接线点
| 位置 | 行为 |
|---|---|
| `CrackService.executeTask` | 候选建好后按 tried 过滤，日志「已跳过 N 条本目标试过的候选」 |
| 全被过滤时 | 日志「这本字典的候选在本目标上都试过了 —— 换一本，或到字典页清空」 |
| `MainActivity.deriveFromTarget` | 落盘前就剔掉已试，落盘文件本身不含重复 |
| 字典页顶部 | 「目标 X 已试过 N 条 —— 跑字典时会自动跳过」 |
| 字典页合并对话框 | 「跑过的 N 条会在跑的时候自动跳过（按目标分别记）」 |

### 门禁
**2354 通过 / 0 失败**（新增 `TriedSetTest` 41 例）。站点链接门禁 5 处仍红（老问题）。

### 本轮修掉的两个自埋坑
1. 落盘用界面当前目标 → 会串档；改成载入时的 `triedKey`
2. 老单槽线索迁移时写失败仍清旧值 → 手填的线索被抹掉；改成写成功才清
