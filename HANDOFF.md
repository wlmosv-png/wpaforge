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
| 版本号 | `124` / `1.9.28`（唯一来源是 `1-aapt2.sh` 的 `WP_VERSION_CODE` / `WP_VERSION_NAME`；APK 清单不写 versionName，`Ver.java` 一律读 PackageManager，所以只改这一处） |
| 已装机 | `1.9.28` (124)：`/storage/emulated/0/Download/WPAForge-1.9.28-124.apk`（1,260,754 B；sha256 `2e1acbfd…7af02`） |
| 已发布 | **`v1.9.28`（2026-10-27）**：tag → `c9a347f`，附件 `WPAForge.apk`；1.9.27 未单独发过 Release |
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

---

## 六、2026-10-26 第四轮：清空线索无效（用户报的 bug）

### 现象
用户：「我清空了之后，还是点开会出现以前输入的线索」

### 根因（两个 bug 叠加，设备上取到直接证据）
设备实况：
```
files/profile/_default.seeds   59 B   12:09   ← 迁移生成的默认模板（含用户那 4 项）
files/profile/<bssid>.seeds    不存在          ← 目标档案从没写成过
files/tried/                   不存在
```
1. **保存与清空用了不同的 key**：保存时 key 为空会回退成用 `targetNet` 算的键，
   清空却直接用 `Prefs.targetKey`（可能是空）→ 去删 `unknown.seeds` → **文件根本没删掉**。
2. **即使删掉也没用**：`pick()` 会自动从默认模板继承，而用户那 4 项
   在首次打开时被自动迁移成了 `_default.seeds` → 又原样冒出来。

### 改法
1. `MainActivity.currentTargetKey()`：**全应用唯一入口**，算出来就写回 Prefs（自愈）。
2. `ProfileStore.pick()` **不再自动继承模板**；改为界面给一个显式的
   「从默认模板填充（N 项）」按钮（`canFillFromTemplate()`）。
   用户的原话是对的：每个网不一样，默认就该是空的。
3. `clearTarget` 改成**写空档案**而不是删文件：
   `没有档案`=从没处理过；`空档案`=用户明确说"这个网不用线索"。
   `read()` 相应把空文件读成空模型而不是 null。
4. `NetDialog.addItemKeepOpen()`：填模板这种"就地改表单"的动作不能关弹窗。

### 回归测试（把踩过的坑钉住）
`ProfileStore.decide(own, tpl)` 抽成纯函数，钉四条：
- 没处理过 + 有模板 → **内容为空**（不自动带），但给填充入口
- **清空过 + 有模板 → 打开时字段仍为空**（用户报的正是这条）
- 有本目标档案 → 用自己的，模板不插手
- 模板内容里的旧口令永远是空的

`TriedSetTest` 53 例；门禁 **2366 通过 / 0 失败**。

### 遗留（用户可能想要）
没有"删除默认模板"的入口。临时办法：把表单清空 → 点「设为默认模板」→ 模板变空，
填充按钮随之消失。

---

## 七、2026-10-26 第五轮：用户反馈四条 + 全面审查

### 用户原话与处理
1. 「没选字典不希望直接出现 wifi 名字在上面」→ 弹窗顶部那行去掉，目标名挪到底部小灰字。
2. 「默认模块（模板）没必要存在，每个 wifi 都不一样」→ **删掉默认模板**。事后同意：
   它的唯一好处（同家多 AP 不用重填）远小于它造成的困惑（自动填进新目标 → "清空"看起来失效）。
   已有 `_default.seeds` 在下次打开画像时自动删除；老全局槽改为迁到**当前目标**档案。
3. 「字典页好像跳过过滤掉了」→ 字典页**不做过滤**，只显示计数；过滤只在两处
   （CrackService 任务开始前 / deriveFromTarget 落盘前）。但顺带查出下面第 4 条的 bug。
4. 「字典融合功能现在无法使用」→ **真 bug，根因是这个**：
   ```java
   final View.OnLongClickListener longPress = v -> { toggleSelect(e); return true; };  // 缺 refresh()
   box.setOnClickListener(v -> toggleSelect(e));                                       // 同样缺
   ```
   `refresh()` 负责重绘行底色 + 显示「已选 N 本 · 合并」→ 不调用则**选中零反馈、合并入口永不出现**；
   加上多选只能长按（新人不知道），于是「用不了」。
   修：① 选完立即 refresh ② 每行左边加**可点的选择圈**（自绘空心圆→实心带勾）
   ③ 修正指向不存在的「选择」按钮的旧提示 ④ 合并完成报告"共 N 条 / 其中 K 条跑过"

### 让"跳过"对不看日志的人也可见
| 位置 | 显示 |
|---|---|
| 破解页字典状态行 | 当前字典: 画像派生 · 53 条（其中 12 条本目标跑过，将自动跳过） |
| 点开始破解时 | **Toast**（不只是日志）：这本字典有 12 条你已经跑过，会自动跳过 |
| 字典页顶部 | 目标 X 已试过 133 条 —— 跑字典时会自动跳过 |
| 合并完成 Toast | 共 12034 条；其中 1044 条本目标跑过，开始时会自动跳过 |

### 审查中自查出的 3 个隐患（用户没提）
1. **写入 O(n²)**：`PoolPipelineTest` 打出 323ms/12000 条才发现 —— 每次 add 都触发全量排序。
   改两段式（已排序前缀 `[0,sortedN)` + 尾巴，每 256 条整理）→ **40 ms**。
2. **合并后统计把整份文件读进内存**：50 万行会吃几十 MB → 改流式 `TriedSet.countIn`。
3. **换网络后"将跳过 N 条"仍是旧目标的数字** → `refreshSkippedForCurrentDict()`。

### 实测数字（PoolPipelineTest，12000 条规模）
```
写入 40 ms · 落盘 3 ms(93KB) · 读回 2 ms · 过滤 3 ms
```
过滤一次 3ms（相对 3.6 秒/条可忽略）；落盘每 32 条一次；强杀最多丢 32 条（约 2 分钟）记账。

### 记账规则（为什么不会误杀）
```java
boolean definite = a.isSuccess() || a.verdict == CrackEngine.Verdict.AUTH_FAIL;
```
只记"明确失败/命中"；超时、无结论、通道出错**不记**，下次还会再试。

### 门禁
**2387 通过 / 0 失败**（TriedSetTest 41 + PoolPipelineTest 33 为新加）。
装机器 101 / 1.9.31。

---

## 八、2026-10-26 第六轮：字典页重设计（用户批准方案后实施）

### 现状诊断（对着代码量的）
| 问题 | 依据 |
|---|---|
| 一屏只看 4~5 本 | 旧 `row()` 约 **130dp/行** |
| 每本 4 个按钮 | 32 本 = 128 个可点目标；「删除」与「查看」同权重 |
| 主视觉被次要功能占走 | 「搜索」是铺满整行的实心琥珀；真正的主操作（新建/导入/下载）藏在右下 FAB 里 |
| 卡片有一行是重复的 | 标题「05 内置号码变形」+ meta「05 内置号码变形.txt」 |
| 标题居中 | 居中是对话框的语言，列表该左对齐 |
| 无排序/筛选 | 32 本 7MB 无法按大小/名称/跑过筛选 |

### 实现（P0+P1+P2 一次做完）
1. **行高 130dp → ~62dp**：左选择圈（普通态隐藏）+ 左对齐名称 + 一行副信息（大小·条数·上次）
2. **点整行 = 查看内容**；**长按 = 进多选**（`HapticFeedbackConstants.LONG_PRESS` 轻振一次）
   —— 注意：**普通点击仍然无振动**，尊重用户此前"不需要按压振动"的要求
3. **4 个按钮 → 一个「⋯」**：查看/编辑/统计/重命名/复制一份/删除（危险操作在菜单里）
4. **顶部主次分明**：「+ 添加字典」(BTN_PRIMARY) + 「搜索」(BTN_SECONDARY)；
   「+ 添加字典」**只放新增类动作**（旧版一打开就是 12 项大杂烩，含"清空全部字典"）
5. **筛选 + 排序**：全部/跑过/未跑 · 大小/名称/最近（默认大小 ↑，因为大小=耗时）
6. **每行显示跑过状态**：`没跑过` / `✓ 跑过`(绿) / `◐ 1.2 万/3.1 万`(琥珀)
   —— 数据**来自任务队列**（`Task.dictPath + offset/dictSize`），零新增存储
7. **多选是模式**：顶部换成"已选 N 本"，底部升起操作条（合并/删除/取消），返回键退出
8. **条数**用 `DictStore.countLines` 流式数 + `lineCountCache`（key=路径+大小）缓存，
   否则每次 refresh 都会重扫一遍所有文件
9. **进度读取节流**：3 秒内不重复读任务队列；`onResume` 强制重读（跑完回来徽标立刻变）

### 新增
- `DictIcons.MORE`（三个点）、`DictIcons.PICK/PICKED`（选择圈）
- `DictStore.countLines(File)` 流式行数

### 门禁
**2387 通过 / 0 失败**。装机 102 / 1.9.32。

---

## 九、2026-10-26 第七轮：跑完的"结算卡" + 收尾态（用户要的"新颖思路"）

### 用户的问题
「有的用户不知道字典破解的道理，跑完了还没出来，就觉得垃圾」+「那个 UI 也有点问题，全跑完跑条因为有问题」

### 诊断（截图里就是证据）
进度卡写着「运行中 100.0% / 剩 0 秒」，下面还挂着「当前候选」——
**状态机没走完**：跑完了但界面还在说"我在跑"，用户盯着等一个永远不来的结果。

### 核心洞察
**"没中"才是破解的常态结果。** 一个工具最重要的状态就是它最常出现的状态。
旧版把"没中"做成了"什么都没发生"（命中反而有专门的卡片）。

### 实现
1. **收尾态**：`finishingNow`（候选跑完但服务还在恢复网络配置）→ 状态行显示
   「收尾中 · 正在恢复原来的网络配置」，不再停在"运行中 100%"。
2. **进度条收尾**：DONE_MISS 分支原来**不存在** → 跑完仍留着"100% + 流光动画"。
   现在收掉进度条，并把候选区标题改成「最后试的候选」。
3. **结算卡**（新卡，替代空状态）：
   - 结论：`这本字典里没有` / `跑完了，但有 N 条没结论` / `试到第 N 条停了` / `这本字典的候选都跑过了`
   - 副行：试了 N 条 · 共 M 条 · 用了 X
   - **为什么**：说人话；无结论的**明确写"不算被排除，下次会重试"**
   - **本机战绩**：`本机记录：跑过 131 次，命中 107 次 —— 工具是能中的，只是这次方向不对`
     （数据来自 `HistStore.load`，比任何安慰话都有说服力）
   - **累计已排除 N 条**（`TriedSet.size()`）——没中≠白跑
   - 三个动作：`填线索再试` / `换更大的字典` / **`先算了`**（允许放弃，否则用户只会卸载）
4. 开跑时 `hideResultCard()`，避免旧结论停在屏幕上。

### 门禁
**2387 通过 / 0 失败**。装机 103 / 1.9.33。

---

## 十、2026-10-26 第八轮：收尾要等 15 秒（用户报"跑完了进度条还在动"）

### 根因（代码级）
`ConfigRestorer.restore()`：
```
removeTarget(ssid)                          → shell，快
readd(ssid, ...)                            → sh(cmd, 20000) 超时 20s
if (readd.issued) waitConnected(ssid, READD_WAIT_MS=15000)   ← **最多等 15 秒**
verify()                                    → status 5s + list-networks 6s
needsCleanupRetry → 再来一遍 remove + verify
```
`restoreAfterRun` 在 `onTaskDone` **之前**调用 → UI 整个收尾期都停在"收尾中"。
最坏路径（readd 超时 + wait 超时 + 重试）可达 60~70 秒，典型 ≈ 15 秒 + 若干 shell 往返。

### 为什么用户觉得是卡死
1. 状态行只说"收尾中"，**不说在等什么、还要等多久**；
2. 进度条**还在放流动效**（100% 了但动画没停）；
3. 文案被截断成"正在恢复原来的…"。

### 改法（全部在 UI 侧，不动判定链）
1. **进入收尾立刻收掉进度条** —— 它已经没信息可给，留着只会继续动画
   （`StagedProgress.onVisibilityChanged(GONE)` 会立即 `animating = false`，已核对）
2. **加一行计时**：`正在把你原来的网络配置写回去 · 已等 6 秒（最多约 15 秒）`
   超过 16 秒改口：`已等 N 秒 · 比预期久，仍在读回确认（细节见攻击流）`
3. 状态行缩短成「收尾中」（详情交给下面那行，不再被截断）

### 顺带的旁证（用户截图里就有）
字典卡写「中文口令 TOP 100 · 77 条」，进度卡写「总数 55」→
**22 条被已试集合过滤掉了**，跳过功能在生产路径上确认生效。

### 门禁
2387 通过 / 0 失败。装机 104 / 1.9.34。

---

## 十一、2026-10-26 第九轮：结果卡 NPE（线上抓到）+ 进度条视觉重做

### 用户报的三件事
1. 「收尾完成的提示呢」「跑完了怎么显示收尾中呢」
2. 「进度条有点暗，颜色重新设计；特效也设计一下」
3. 「日志给我抛出了异常」

### 根因（第 1、3 条是同一个 bug）
```
队列执行异常:
java.lang.NullPointerException: Attempt to write to field
'int android.view.ViewGroup$MarginLayoutParams.leftMargin'
on a null object reference in method 'MainActivity.showResultCard'
```
`AmberTheme.action()` 造的 Button **没有 layoutParams**，我直接强转去写 leftMargin → NPE。
后果链：`showResultCard` 抛异常 → `onTaskDone` 里后面的 `renderProgressCardState()`
没执行 → **界面永远停在"收尾中"** → 结果卡从未出现过。

修法：
1. 新增 `actionButton(label, kind, leftMarginDp, listener)` 工厂：永远新建 LP 再挂。
2. `onTaskDone` 里把 `showResultCard` 包 try/catch 并在攻击流报一行 ——
   界面异常不许把状态机留在中间态。

### 收尾提示
- `UiCallback` 加 `default void onRestoreDone(String summary)`（default 实现，老实现不用改）
- 结算卡多一行：`收尾完成 · 原来的网络已写回（已恢复 : OK）`
- 收尾期文案改人话：`破解已经跑完 ✓ 现在把你原来连的那个网写回去，最长等 15 秒（已等 3 秒）`

### 进度条视觉重做
渲染两版对比后定稿：
- 旧外层起点 `0xFF9A6A00` → 满进度是**暗棕**，看着"没跑满"（用户说"有点暗"）
- 新外层起点 `0xFFC68A00`；三层 = 金 / 亮金 / 淡金
- 填充改**横向渐变**、高光带改**软边渐变**、前端加**呼吸光点**、
  顶部 1.2dp 微高光、轨道加极淡内描边
- 预览图：`/sdcard/Download/wpaforge-progress-preview.png`

### 门禁
2387 通过 / 0 失败。装机 105 / 1.9.35。

---

## 十二、2026-10-26 第十轮：收尾"卡住"的第二个真因 —— 跨线程碰 View

### 用户第二张日志
```
配置收尾结论: 已恢复 : OK
队列执行异常: android.view.ViewRootImpl$CalledFromWrongThreadException:
  Only the original thread that created a view hierarchy can touch its views.
  Expected: main  Calling: wpaforge-crack
队列已结束
```

### 根因（我第 7 轮插错了位置）
`onTaskDone` 里我把这几行写在了 `ui.post(...)` **外面**：
```java
finishingNow = false;
ui.removeCallbacks(finishingTick);
if (finishNote != null) finishNote.setVisibility(View.GONE);   // ← 工作线程碰 View
showResultCard(task, outcome);                                  // ← 同上
ui.post(() -> { ... });
```
`onTaskDone` 是**服务的工作线程**（wpaforge-crack）调进来的 → 直接抛
CalledFromWrongThreadException → 后面的 `renderProgressCardState()` 没执行
→ 界面留在「收尾中」。**这是"收尾还是有问题"的第二个（也是真正的）原因。**

### 修法
1. 全部挪进 `ui.post`，并在方法头写明"这个回调在工作线程，一个字都不许碰 View"。
2. `showResultCard` **自带主线程守卫**（不在主线程就自己转投过去）——不依赖调用方小心。

### 新增门禁：`UiThreadContractTest`（12 例）
源码级检查：`svcCallback` 里 7 个回调方法体内，任何
`setText/setVisibility/appendStream/showResultCard/renderQueue/Toast…`
**必须出现在 `ui.post(` 之后**；外加两条反向保护（主线程守卫存在、
不再强转 `getLayoutParams`）。

**并且验证了这个门禁真的有效**：拿 HEAD（有 bug 的那版）跑 → 2 处失败，
报出 `.setVisibility(View.GONE); 出现在 ui.post 之前`；修好后 12/12。
拿 `/tmp/lintproj` 复制一份旧源码就能复现这个验证。

### 门禁
**2399 通过 / 0 失败**。装机 106 / 1.9.36。

---

## 十三、2026-10-26 第十一轮：状态判定错源 + 进度条返工 + 同类问题

### 用户三条反馈
1. 「点别的字典、返回没选择，就变成还是运行中」+「不管用户怎么样只要没跑，
   跑完了是什么就是什么」
2. 「进度条变得不好看了，加了个框之后」
3. 「后面的百分数字会被盖住」

### 根因与修法
**① 状态判定用错了源（最重要）**
```java
boolean running = CrackService.isRunning();          // ← 错
} else if (running || st == TaskQueue.State.RUNNING) { ... }
```
`CrackService.isRunning()` = 「服务实例还活着」，而服务里的 `running` 标志
**跑完正常结束不会置回 false**（只有 STOPPED 才置）。于是任务已 DONE_MISS，
界面却一直显示「运行中 100%」；用户切去看字典再回来，还是「运行中」。

修：**状态一律以队列为准**（`st == RUNNING` 才叫运行中/收尾中）；
`serviceRunning` 只用来决定提示行文案。并补一条同类矛盾：
提示行原来也用服务存活判，于是状态行写「已结束 · 未命中」、提示行写「任务进行中」
——同一张卡里两句话打脸。现在按队列判；服务侧本来就有自我保护
（`startQueue()` 见 worker 还活着会拒绝）。

**② 进度条的框**：上一版我加的轨道描边（`pEdge`）—— 用户说"加了个框之后不好看"。
去掉描边与填充顶部的微高光条。

**③ 百分数被盖住**：标签原来画在**条内右下角**（`w - lw, top + barH`），被填充压住。
改成先量标签宽度，从条宽里让出来，标签画在**条外右侧**并垂直居中。

**④ 顺带的文案重复**（我发现、用户没提）：
进度条脚注写「56 / 60 · 17 条/分」，卡片下面又写一遍「17 条/分 … 剩 13 秒」。
现在：进度条只留「还剩多久」大字，脚注置空；卡片右下角运行期间也留空。

**⑤ 保险**：`startConnect` 里复位 `finishingNow`，防上一轮异常残留导致一直显示"收尾中"。

### 自查
新视觉渲染出来看过：标签在条外、无框、三层金色渐变分明。
预览图 `/sdcard/Download/wpaforge-progress-preview.png`。
门禁 **2399 通过 / 0 失败**。装机 107 / 1.9.37。

---

## 十四、2026-10-26 第十二轮：状态不刷新（渲染顺序）+ 命名过时 + 数据驱动建议

### 用户反馈
1. 「当我点『先算了』的时候，那里还是运行中」
2. 「没命中的是啥原因？这影响破解吗？」

### ① 根因：渲染顺序反了 + 权威变更没有重画
`onTaskDone` 里：
```java
renderProgressCardState();   // ← 此刻 queueCache 还是旧的 RUNNING
refreshQueue();              // ← 更新了 queueCache，却**没有重画状态行**
```
于是任务早已 DONE_MISS，状态行永远停在「运行中 100%」；
点「先算了」把结果卡收起来之后，剩下那张进度卡还挂着"运行中"。

修法（从根上，不再靠调用顺序）：
- `refreshQueue()` 与 `onQueueChanged` 里都补 `renderProgressCardState()`
  —— **队列是权威，权威一变就必须重画**
- `onResume` 也调一次 `refreshQueue()`（后台跑完/别处改过，回来时状态才是新的）

### ② 顺带修：字典名与内容不符
用户那张图里字典叫「SSID 派生 · 2.4G-3 · 60 条」，但内容其实是
「画像 + 通用弱口令」，而 SSID 派生默认还是关的 → 改成「**定向派生 ·**」。
审计页按钮「按 SSID 派生（选目标）」→「按目标派生（选目标）」。

### ③ 新增：数据驱动的"换哪本更划算"
`topHitDictName()`：从历史记录里数出**本机命中最多的字典**，写进结算卡。
（用户这台：`01 内置弱口令常用（57 次）`——占全部命中的 53%）

### 用户第二问的答案（用他自己机器的数据）
```
总记录 134，命中 107
命中来源：01 内置弱口令常用 57 · xato TOP 10000 14 · 02 纯数字 10
          2025 TOP199 8 · 05 号码变形 6 · 中文高频 4
命中长度：8 位 83 · 9 位 20 · 10 位 4
```
- 日志里每条都是明确的「密码错误」→ **判定链正常工作，不是故障**
- 60 条全不中 = 这 60 条里确实没有 → 覆盖不够，不是程序坏
- **不影响判定正确性，只影响命中概率**
- 他自己设备上命中最多的是「01 内置弱口令常用」（2493 条，约 2.4 小时；
  但 104/107 的命中发生在第 50 条以内，所以通常几分钟内就中）

### 门禁
**2399 通过 / 0 失败**。装机 108 / 1.9.38。

---

## 十五、2026-10-26 第十三轮：结束态词汇 + 「过滤基准」做成页面可查

### 用户两条
1. 「这里在结束后也会显示完成?或者完成也不太好 想个词汇」（圈的是候选区标题）
2. 「跑了很长的字典换下一个字典，我要确保顺利过滤掉上个字典跑的。
   由于字典大我无法测试，我就想知道这个过滤的基准」

### ① 词汇（不用「完成」——没找到不算完成）
候选区标题原来是「当前候选」，跑完还挂着 → 让人以为还在跑。
现在两个词：
| 状态 | 词汇 |
|---|---|
| 运行中 | **正在尝试**（主动语态，说明"现在在干什么"） |
| 已结束（不论命中/未命中/停止） | **最后尝试**（陈说事实） |

并且修掉一个**打架**：`hideResultCard()` 原来会把标题改回「当前候选」，
于是用户点「先算了」之后，一张写着"已结束·未命中"的卡下面挂着"当前候选"。
现在标题只由 `startConnect`（开跑）和 `showResultCard`（结束）两处决定。

### ② 过滤基准（做成页面内可查，不用跑大字典验证）
字典页顶部那行「本网络已试过 N 条 …」现在**可点**，弹出：

```
本网络已试过 60 条
记录文件 480 B（每条 8 字节，条数 = 字节数 ÷ 8）· 最近写入 10-26 13:50

▸ 判据：候选口令本身的 64 位哈希。
  不看字典文件、不看第几行、不看顺序。
▸ 记账时机：每条候选跑完、且有**明确结论**（密码错误/命中）时才记。
  超时、无结论的不记 —— 那些下次会重新试。
▸ 唯一会重复的三种情况：
  · 强杀进程 → 最后最多 32 条（约 2 分钟）丢失
  · 没结论的候选 → 故意不记
  · 换到别的网络 → 另一套记录（故意不共享）
▸ 上限：每个网络 100 万条（8 MB）
```

**设备实测（用户机器）**：5 个目标 5 个文件，各 480 B(=60 条)、126 条等，
与"每目标独立、8 字节/条"完全对得上。

### 门禁
`CrackPageLayoutTest` 里钉的是旧词「当前候选」→ 更新为新词汇（顺便钉住新词，
防止有人改回去）。**2399 通过 / 0 失败**。装机 109 / 1.9.39。

---

## 十六、2026-10-26 第十四轮：破解页「?」说明入口（用户批准方案后实施）

### 用户拍板
1 图标放①卡顶行最右 ✓ 2 不自动弹 ✓ 3 要「现实约束」那节 ✓ 4 要动态本机数字 ✓

### 实现
- `DictIcons.HELP`：圆 + 问号。形体渲染比过三版：
  A 上弧+两段钩（22dp 下钩糊掉）/ C 弧开角过大（臃肿）/
  **B 上弧 + 一笔斜线落到中心下方**（小尺寸下弧与点被连起来，最像问号）→ 选 B
- 落点：① 网络信息卡顶行最右（`netSecChip` 之后），20dp 弱灰，点击打开
- `showCrackHelp()`：七节
  ① 它在干什么 ② 为什么不用几十万条大字典（83 小时的算术）
  ③ 候选从哪来 + **社会工程学的判断基准**（人改动极小 → 一个手机号只出 3 种形态；
     靠维度互拼扩展，不靠对单值反复变形）
  ④ 判断依据（三条同源证据；宁可报不确定也不报假成功）
  ⑤ 没中是怎么回事（三种没中分开 + **本机真实数字**动态取）
  ⑥ 现实里会让它失效的三件事（WPA3/PMF、不在候选里、信号弱→大量无结论）
  ⑦ 边界（自有/已授权）
  末尾两个跳转：去填线索 / 怎么选字典（形成闭环）
- 空状态引导补一句「右上角「?」看它是怎么破解的」

### 门禁
**2407 通过 / 0 失败**。装机 110 / 1.9.40。

---

## 十七、2026-10-26 第十五轮：「?」图标看不见 + 点开卡一下

### ① 图标只剩几个像素（用户："那个点很小很小 根本看不到"）
根因：**`DictIcons` 没有实现 `getIntrinsicWidth/Height`**。
ImageView 用 `WRAP_CONTENT` 时会问 Drawable 要固有尺寸，拿不到（-1）就按 0 测量
—— 于是 20dp 的图标塌成几个像素。字典页那几处因为写了显式 LayoutParams 才没露馅。

修：
- `DictIcons` 补 `getIntrinsicWidth/Height`（返回 bounds 尺寸）—— **根治**，任何
  WRAP_CONTENT 场景都不会再塌
- 该处改为显式 `px(38) × px(38)` + `CARD_3` 淡底圆角 + **琥珀色 20dp 图标**
  （原来用弱灰 TEXT_DIM，即使尺寸对了也不够显眼）
- 渲染预览确认：对比图 `/sdcard/Download/wpaforge-help-row-preview.png`

### ② 点开卡一下
根因：`HistStore.Record.fromLine` 对**每条命中记录**都调 `SecretBox.open()`
—— 走 Android Keystore 解密。用户 107 条命中 = 107 次 Keystore。
而我调了**两次** `load()`（`localTrackRecord` + `topHitDictName`）→ 200+ 次。

修：
- `HistStore.summary(ctx)`：新路径，**只读前 8 列、完全不解密**
- `foldLine()/build()` 抽成纯函数，`HistorySummaryTest`（10 例）钉住
  「行里塞无法解密的假密文，统计结果照样正确」——即证明它没去解密
- MainActivity 合并成一次调用 + **30 秒缓存**；跑完一轮让缓存失效
- 同类问题一并修：**破解记录页**也在主线程解密 → 挪到后台线程

### 门禁
**2417 通过 / 0 失败**（新增 HistorySummaryTest 10 例）。装机 111 / 1.9.41。

---

## 十八、2026-10-26 第十六轮：日志默认「全部」+ 派生内容的社会工程学审查

### ① 日志默认级别
`streamKeyOnly` 由 `true` 改 `false`（关键 → 全部）。
理由：关键级别会把逐条候选的结果藏起来，用户看到的是"跑了半天什么都没发生"。

### ② 社会工程学审查（用户点名的重点）—— 抓到 3 处"无脑延伸"
把实际产出全量打出来逐条过（`/tmp/Audit.java`，5 个场景）：

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| 1 | **长拼音被闸门误杀** | 自由词 `laopoxingwang`（13 位）**根本没出现在产出里** | 混排上限 12 → **24** |
| 2 | **宽带账号被闸门误杀** | `051288889999`（12 位纯数字）同样不见 | 纯数字上限 11 → **16** |
| 3 | **旧口令被当词根堆砌** | `zhangwei2023` 的产出里有 `zhangwei20231234`、`zhangwei20231314` | 改成「**换尾**」：`zhangwei2024/2025/2026`（换年份）、`zhangwei520`（换尾数） |
| 4 | **组合层超长堆砌** | `0512888899990101`（宽带账号 12 + 月日 = 16 位纯数字） | 组合层加长度政策：**纯数字 ≤13、混排 ≤20** |

闸门放宽的**依据**：原上限来自"本机 107 次命中最长 10 位"，但那是**数字口令**的分布，
不是混排口令的分布。放宽不会凭空造垃圾 —— 能进池的都是各桶的显式规则，
闸门只负责"别放进离谱长度"。

### 审查后的产出（逐条过"有人会这么设吗"）
| 场景 | 条数 | 评价 |
|---|---|---|
| 只有手机号 | 33 | ✓ 原值 3 条 + 通用弱口令 |
| 完整画像 | 74 | ✓ 全部可读；含宽带账号、两名字拼接、拼音+后缀 |
| 画像+旧口令 | 58 | ✓ 旧口令排第一 + 换年份/换尾数 |
| 自由词 | 43 | ✓ `laopoxingwang` 排第一 + 后缀变体 |
| SSID 派生（默认关） | 115 | 有 BSSID 尾号补零这类低价值条目，但**默认关**且排最后 |

### 回归测试
`SeedDeriveTest` 加 5 条（13 位拼音保留 / 12 位宽带保留 / 16 位纯数字组合被挡 /
旧口令换年份生效 / 旧口令不再被堆砌），并把两条**旧断言**（12 位纯数字被丢、
13 位混排被丢）更新为新契约 + 补上 17 位/25 位的新边界。

### 门禁
**2428 通过 / 0 失败**。装机 112 / 1.9.42。

---

## 十九、2026-10-26 第十七轮：SSID 派生砍掉臆造族（社会工程学审查 · 第二批）

用户原话：「派生 ssid 确实出了很多垃圾的条目，你是用户你也觉得这种密码离谱」。

第十六轮只审了**画像**派生的产出，SSID 派生挂着"低价值但默认关"就过了。
这一轮把它逐条过完，砍掉 5 类纯属矩阵副产品的串：

| 砍掉的族 | 例子 | 为什么它不该存在 |
|---|---|---|
| BSSID 尾号 + 后缀/数字 | `e9721100` `e972111` `e97211520` | 出厂默认用 MAC 是真实的，但形态是**完整 MAC 原样**；6 位尾号本就够不到 8 位下限，能进榜全靠补后缀 = 为凑长度造串。本机 107 次真实命中里**一次都没有**来自这一族 |
| 符号位置矩阵 | `fast@2026` `2026@fast` `fast#2026` | `admin@123` 这一类真实存在，但**年份配符号很罕见**，来回换顺序与符号纯属堆砌 |
| 写死的 wifi / wlan 后缀 | `FASTE972wifi` | SSID 已经叫 FAST_E972，密码再把 SSID 写一遍，没人这么设 |
| 夹心数字 | `97288972` | 把 SSID 里的数字夹在重复数字中间 |
| 数字碎片 + 字母碎片 | `972faste` | 两块 SSID 碎片直接粘起来，形态上不像任何人写的密码 |
| 厂商词元 + @年份 | `tplink@2026` | 与符号矩阵同源 |

**保留**：完整 12 位 MAC 无分隔（小写/大写）、SSID + 数字后缀（`fast_e9720`）、
SSID 字母部分 + 数字（`faste123`）、片段组合、厂商名 + 年份（`tplink2026`）。

复查（同输入）：符号矩阵 12 → **0**、`wifi/wlan` 3 → **0**、
BSSID 尾号族 12 → **2**（仅完整 MAC），候选总数 **127 → 112**。

### 回归
`TargetDictTest` 新增 14 条「已删除」断言（含 3 条「仍须保留」的正例），
2 条旧断言从「含」改「已删除」并改用测试自身 MAC（不依赖别处的常量）。
每条断言都注明「为什么它不该存在」，避免以后有人当成漏掉的功能又加回来。

### 门禁
**2443 通过 / 0 失败**；站点链接门禁全绿。

### 附带：`docs/index.html` 站点链接门禁修复
跳转页补 `og:type/site_name/title/description/url/image` +
`twitter:card/title/image`（`og:image` 用现成 `docs/og.png` 的绝对 URL），
并加「下载最新版」「更新日志」两个直链。门禁 **5 处红 → 全部通过**。

---

## 二十、2026-10-26 第十八轮：字典瘦身 + 按本机战绩排序（1.9.26 / 113）

用户原话：「目前内置生成器怎么有这种条目…整体检查一下我们自己生成的条目」
「内置字典只保留最有用的…从各个地方去找找好用的字典内置进去…做就做出特色…自己测好再出包」。

### 起因：内置字典是别人家工具的资源，逐字节照搬

对比幻影 WiFi（`com.zyx.wifi` 3.1.0，已装在机上）的 `assets/1..8.txt`：

| 幻影 | 我们 | 内容 | 大小 |
|---|---|---|---|
| 1.txt | 01 内置弱口令常用 | 2,494 条 | 27 KB |
| 5.txt | 03 内置字母数字混合 | 15,146 条（含 `62696e676f` 这种十六进制脏数据） | 162 KB |
| 6.txt | 02 内置纯数字弱口令 | 2,188 条 | 25 KB |
| **7.txt** | **05 内置号码变形** | **483,522 条 = 26 字母 × 18,597 个 8 位日期** | **5.3 MB** |
| **8.txt** | **04 内置英文单词** | **168,068 条英文词典**（aardvark…） | **2.2 MB** |
| 2/3/4.txt | 90/91/92 规则 | 区间穷举模板（1 亿条 / 90 亿条） | 各 53 B |

**8 个文件 MD5 全等**。幻影的「高级字典」就是填「起始/末尾」跑数字区间：
8 位全区间 1 亿条，按本机实测 6 秒/条要跑 **4,167 天**。

### 判据来自本机历史（18 个目标 / 107 次命中）

| 来源 | 记录 | 命中 | 命中时已试（中位） |
|---|---|---|---|
| 01 内置弱口令常用 | 73 | 57 | 2 |
| xato TOP 10000 | 14 | 14 | 11 |
| 02 内置纯数字 | 11 | 10 | 1 |
| 2025 年度 TOP199 | 13 | 8 | 16 |
| 05 号码变形（48 万条） | 6 | 6 | **2** |
| 03 字母数字混合 | 2 | 1 | 102 |
| 04 英文单词 | **0** | 0 | 从未跑过 |
| SSID 派生 · 2.4G-3 | 4 | 0 | — |

**95% 的命中在前 20 条以内，中位第 2 条，最深 120 条，0 次来自 1000 条以后。**
命中长度 8 位 83 / 9 位 20 / 10 位 4（无 11 位）。
→ 决定成败的是「前 20 条排得准不准」，不是表有多大。

公开统计（CERNET 48,330 条真实 SSID+密码，公安部三所）：8~11 位占 76%；
8 位密码里纯数字 73%；11 位纯数字（手机号）占全部 13.9%；
字母+数字占 35.9%，其中「3 字母 + 8 数字」占 31%，字母七成是姓名首字母；
TOP10 弱口令合计只占 10.355%。

### 落地

- **assets 7.9 MB → 747 KB**，APK **2.70 MB → 1.19 MB**（−1.51 MB）。
  新增 `wpa_top62` / `wpa_top4800`（SecLists，MIT），删 `words.txt` / `numbers.txt` /
  `wpa_top447`（⊂ 4800）。`BuiltinDicts` 每个条目带**档位 + 留用理由**。
- **释放时清理旧内置**：按 sha256 只删「与出厂内容完全一致」的。
  实测：`04`、`06` 已清（−2.16 MB）；**`05` 未清** —— 用户那份被改过
  （开头多了 `12345678` / `11223344` 两行），按规则保留。
- **内置生成器 1771 → 336 条**（`DictGenerator.quick()` 是唯一配方，
  界面/服务重建/统计共用，禁止再各写一遍）。删：手机段+假尾号（`130123456`）、
  英文普通词×后缀（`family2018`/`love888`）、厂商词+符号+年份（`tplink@2026`）、
  「双块组合」矩阵（`00001234`）。补：8 位结构数字（重复/阶梯/键盘/镜像/AB 交替）。
  品牌词整族删除，理由：真实出厂默认是**裸词**，而裸品牌词都 <8 位本就进不了闸门。
- **`SourceRank`（新）**：代价 = `(Σ已试条数 + 200) / (命中数 + 1)`
  ——「每命中一次平均要试多少条」，未命中的整本跑完同样计入。
  没跑过的来源用先验 `规模/2 + 200`。字典页默认排序改「命中率」，
  每行显示 `历史命中 57/73 · 平均 2 条中一次`。**只读历史前 8 列，不解密**。
  本机真实排序实测：01 弱口令(9 条/次) → 02 纯数字(23) → xato(23) → … →
  SSID 派生（0/4）垫底。
- **`BirthdayTable`（新）**：替代 5.3 MB 静态表，`dates(y1,y2)` /
  `prefixedBirthday(前缀,…)` 按需生成。时间预估统一到
  `CostMeter.SEC_PER_CANDIDATE = 6`（本机 1530 条 / 8639 秒实测；
  原来是「1.5 条/秒」与「约 3.6 秒」两套口径并存）。
- `DictActivity`：`SortBy.HITS`（默认）、行内档位与战绩、内容页显示留用理由。

### 门禁与验收

- 新增 `SourceRankTest`（汇总公式/排序先验/容错/转义/清单自洽 38 项），
  `SelfTest` 反向钉住「已删族不得复活」。**门禁 2495 通过 / 0 失败**；站点链接全绿。
- 出包 **1.9.26 (113)**：2,698,451 → **1,191,122 B**，sha256 `e6c339bc…`，
  证书 `af5524cd…25c8`（覆盖安装 OK）。`dumpsys` 五查一致（1.9.26 / 113）。
- 装机后启动 `DictActivity` 实测：`.builtin_v4` 写入、`10`/`06` 释放、
  `04`/`06` 清理、无崩溃。

**版本号说明**：线上最新 Release 是 `v1.9.25`，按「线上 + 1」规则本版为
**1.9.26**；versionCode 取 **113**（机上曾装过未发布的 112/1.9.42，取 113 才能覆盖升级）。

---

## 二十一、2026-10-26 第十九轮：派生产物当缓存管（1.9.26 / 114）

用户：「能不能在字典页加一个删除派生字典的功能…那玩意用一次就不用了，
留着看起来很多，还很麻烦」。

**判断**：这不是「缺一个删除按钮」（长按多选删除、行菜单删除、全部删除都有了），
而是**缓存混进了资料列表** —— 派生产物由目标 + 线索随时可重算，本身没有独立价值，
不该以「文件」的身份长期占据用户的字典列表。

**落法（三层，`DerivedDicts` 纯逻辑 + 40 项单测）**

| 层 | 行为 |
|---|---|
| 识别 | 前缀匹配：`定向派生 · X`、旧名 `SSID 派生 · X`、`内置生成器`、历史名 `00 内置生成器.txt`、英文名；用户自己的字典一个都不碰 |
| 滚动 | 写新的派生产物前删掉**同一目标**的旧产物：`跑 N 次攒 2N 份` → `恒定 2 份` |
| 折叠 + 清理 | 字典页归入「临时产物 N 份 · X KB」默认折叠；一键清理排除被任务占用的 |

**两个容易踩的点（都已钉住）**

1. **归组只认「末段长得像指纹」**（`[0-9a-f]{6,12}`）：旧名 `SSID 派生 · 2.4G-3`
   没有指纹，若一律按最后一个 ` · ` 切，会把「SSID 派生」当组名 →
   一次滚动就把别的目标的产物也删了。
2. **保护名单 = PENDING / RUNNING / PAUSED 任务的 `dictPath`**：
   那些路径一消失，续跑的 offset 就对不上。测试里对「被任务占着的一份都不许进
   清理对象」有专门断言。队列读失败时**保守跳过清理**，不硬来。

**顺带**：生成器文件名统一成 `内置生成器`（旧 `00 内置生成器.txt` 由滚动清理带走），
消除目录里两份同样内容的局面。

**验收**：门禁 **2535 通过 / 0 失败**（新增 `DerivedDictsTest` 40 项）。
出包 **1.9.26 (114)**：1,199,314 B，sha256 `77f9b1033…`，覆盖安装成功，
启动字典页无崩溃。

---

## 二十二、2026-10-26 第二十轮：观测页雷达改版（1.9.26 / 115）

用户：「优化一下观测页的雷达」。第二版（10-24）把「档位即刻度」做对了，
但**方位角仍是装饰** —— `Blip(i * 137.508f)`，位置来自按信号排序的序号。
后果：信号一抖点就换位、新 AP 进来其余点全体挪一格、23 个网络谁是谁无从对应。

### 三处「看得见」的修复（用户截图直接指向）

| 问题 | 原因 | 修法 |
|---|---|---|
| `≥-50` 刻度被亮点盖住 | 刻度画在点**之前** | 刻度挪到 onDraw **最后一步**，底片不透明 |
| 黄扇压在右下 1/4 数据上 | `drawArc(..., true)` 画的是**实心扇形**（20% 不透明度） | 换成「指针 + 6 条拖尾」，不填充任何面 |
| 点位置乱跳 | 角度＝按信号排序的序号 | 角度＝**信道**（见下） |

### 方位角＝信道（`scan/ChannelMap`，纯逻辑 33 项单测）

- 上半圆（180°→360°）＝ 2.4G 的 **14 个信道**，每格 180/14 ≈ 12.86°；
- 下半圆（0°→180°）＝ 5G 的 **4 个 UNII 组**（36–48 / 52–64 / 100–144 / 149–165），每组 45°；
- 2.4G 只标 **1 / 6 / 11**（唯一互不重叠的三条）；
- 于是「同一扇区里点越多＝这条信道越挤」，与 `ChannelBars` / 信道建议卡互相印证；
  且信道不变方位不变 → 新 AP 只在自己扇区插空，**不重排全场**。

### 落点几何抽成纯逻辑（`scan/BlipLayout`，18 项单测）

动机：这段几何最容易错、又最难肉眼验证。抽出来后钉住：
角度∈[0,360)、半径比例∈[0.30,1.0]、点必落在自己扇区内、同扇区同档不重合、
**打乱输入顺序后每个 BSSID 的方位完全一致**（位置稳定的硬约束）、120 台不越界。

### 记法

- 频段用**填充方式**区分：2.4G 实心、5G 空心环 —— 不占用颜色通道
  （颜色留给信号强度与未加密的红边），色盲用户也分得清。
- 未加密红边 1.4 → **1.7dp**；中心改成「网络数 + 最强 dBm」。
- 视图里删掉了 `DOT_GAP` / `ROW_OFFSET` 两个常量 —— 几何全在 `BlipLayout`，
  两处各留一份迟早漂移。

### 门禁与验收

- 新增 `ChannelMapTest`（33）+ `BlipLayoutTest`（18）；`ThemeConsistencyTest`
  的雷达契约从「均匀铺开」改写成「方位＝信道 + 稳定排序 + 不填充扇形」。
- 门禁 **2593 通过 / 0 失败**。出包 **1.9.26 (115)**：1,207,506 B，
  sha256 `e9ae2a4ed…`，装机启动无崩溃。

---

## 二十三、2026-10-26 第二十一轮：雷达二次修正（1.9.26 / 116）

用户看过 115 后两条反馈：
**「有圆点被信号强度数字压住了」**、**「有点单调，除了雷达旁边加点啥」**。

### ① 文字与点在盘上抢位置 —— 这是设计问题，不是 z 序问题

上一版把 dBm 刻度排到最后一步画（解决"刻度被点压住"），代价变成"点被数字压住"。
盘面本来就窄，两者在同一平面争位置，**怎么调 z 序都是输**。

修法：**刻度整体移出盘面**，做成盘下图例（五档色块 + dBm 区间），
刻度值仍从 `RadarSweep.ringLabels()` 取（不允许另写一份，`ThemeConsistencyTest` 钉住）。
盘上只留 1 / 6 / 11 与 4 个 5G 组标，画在点之上 + 带底色片。

### ② 点仍然会叠：旧公式的系数是错的

`BlipLayout` 里原来 `dotR = min(dotDp*density, spacing/2*1.4f)` —— 系数 **1.4 比 1 还大**，
"明明算过容量"点却仍然叠在一起（用户截图那三四个叠着的点就是这么来的）。

改成严格服从角向间距：`dotR = min(点径上限, spacing/2*0.85)`，
并把下限降到 1.4dp、子环上限提到 4 道。

### ③ 塞不下就聚合，不硬挤（`×N` 标）

一组点超过容量（`perRow × MAX_ROWS`）时不再压缩，改画一个 **「×N」汇聚标**：
几十个点叠一起既看不出数量、也看不出谁是谁，不如直说"这条信道这一档有 N 台"
—— 而"这条信道很挤"本身就是用户要的结论。

### ④ 「单调」的解法：图例 + 关键数字

雷达卡在盘下补两行（`radarLegend()` / `renderRadarStats()`）：

```
由内到外： ● ≤-90  ● -80  ● -70  ● -60  ● ≥-50
● 实心 = 2.4G    ○ 空心 = 5G    圆心 = 信号最强
2.4G 18 台   5G 9 台   最挤 ch 6 · 5 台   未加密 3 台
```

最后一行是"看盘的人一定会问、但盘上读不出来"的三个问题。

### 门禁与验收

- `BlipLayoutTest` 加「像素距离不重叠」与「40 台同信道 → 汇聚标」断言（19 → 23 项）；
  `ThemeConsistencyTest` 补 4 条契约（盘上无 dBm 文字 / 图例取自 RadarSweep /
  卡片有图例与关键数字 / 有汇聚标）。
- 门禁 **2602 通过 / 0 失败**。出包 **1.9.26 (116)**：1,207,506 B，
  dex 内已确认含 `ringLabels` / `aggregated` / `最挤` 等新标记，装机启动无崩溃。

---

## 二十四、2026-10-26 第二十二轮：雷达刷新过渡 + 两处文案（1.9.26 / 117）

用户两条反馈：**「刷新的时候有个特效啥的？看着很生硬一下就变了」**、
**「底下最挤那里文字显示也有点问题」**。

### ① 刷新过渡：`scan/RadarAnimator`（纯逻辑，18 项单测）

原来 `setBlips()` 一换数组，下一帧就是新位置 —— 点瞬移、新网络凭空冒出、
消失的网络凭空消失，像画面被整块替换。

做法是**状态机 + 插值**（按 BSSID 匹配，目标位置仍由 `BlipLayout` 决定，
"位置稳定"的契约没被动画破坏）：

| 情形 | 行为 |
|---|---|
| 同一台 AP 位置变了 | 缓动过去（τ=110ms，单帧步长封顶 0.5） |
| 新出现的 | alpha 0→1 淡入，并从略靠内（×0.88）长到目标位置 |
| 消失的 | alpha→0 淡出后才移除（看得到它退场） |
| 全部到位 | `busy()` 返回 false → **视图停止出帧**（静止一秒都不重绘） |
| 切后台回来 | dt 上限 0.2s + 步长封顶 → 不会一步瞬移 |

中心数字也改成**滚上去**（到位即停），不再"啪"地跳变。

### ② 两处文案问题（都是真 bug）

- **图例顺序写反**：`ringLabels()` 是按「由外到内」排的（下标 0 = 最外环 = 最弱），
  文案却写「由内到外」→ 改成倒序显示，读序与说明一致。
- **「最挤」被省略号吃掉**：四格里那格文字最长（`ch 5 · 1 台` 顶到边），
  → 改成 `ch 5 · 1`（去掉单位）并给这格 1.35 的权重。

### 门禁与验收

- 新增 `RadarAnimatorTest` 18 项（淡入 / 位置连续 / 淡出移除 / busy 收尾 /
  超大 dt 保护 / 空与 null / 汇聚标也能过渡）。
- `ThemeConsistencyTest` 的雷达契约补 3 条（走过渡不瞬移 / 淡入淡出 / 静止不出帧）。
- 门禁 **2623 通过 / 0 失败**。出包 **1.9.26 (117)**：1,211,602 B，
  sha256 `d4c03ea31…`，装机五查一致、dex 内含 `RadarAnimator`、启动无崩溃。

---

## 二十五、2026-10-26 第二十三轮：手动重扫 + 扫掠加厚（1.9.26 / 120）

用户两条：「圈很大 扫描的这个很瘦弱看起来」＋「用户无法主动刷新网络 能不能加个按钮啥的」。

### ① 扫掠加厚加长（盘大，细线撑不住）

| 项 | 旧 | 新 |
|---|---|---|
| 拖尾弧长 | 46° | **78°** |
| 外层线宽 | 11dp | **14dp** |
| 最亮处 alpha | 0.50 | 0.62 |
| 内层柔光 | 无 | **26dp / 0.13**（给"光带"体积感） |
| 尖端亮点 | 2.4dp | 3.0dp |

盘半径是 200dp 量级，"细线 + 大圆"必然显得瘦弱；只有光带（宽而淡的内层 + 窄而亮的外层）才压得住。

### ② 「重新扫描」控件（`scan/ScanGate`，纯逻辑 30 项单测）

Android 9+ 对 `startScan()` 有约 30 秒的频率限制，**超频时系统直接丢掉请求**
（返回 false）。旧代码只往日志写一行、界面一声不吭 —— 用户点了没反应，
只会以为软件坏了。所以按钮的设计要点不是"加一个按钮"，而是**不撒谎**：

| 状态 | 界面 |
|---|---|
| 还没扫到 | `还没扫描到网络` |
| 有结果 | `最后更新 N 秒前`（每秒刷新） |
| 冷却中 | 按钮变暗 + `还需 N 秒` |
| 上次被系统拒 | `系统限制了扫描频率 · N 秒后再试` |

闸门把所有节流判断收在一处（`MIN_INTERVAL_MS = 30s`；被拒后额外冷却 30s）。

### ③ 「新增 2 台 · 消失 1 台」（`scan/ScanDiff`，纯逻辑）

只给一个刷新按钮，点完看到同一张图，用户会怀疑按钮没用。
按 **BSSID** 比对上一次结果（SSID 会重名、隐藏网络没名字），
把差异直接说出来，刷新才有信息量。

### ④ 其它

- 雷达卡头部新增一行（左状态 / 右按钮），雷达高度 232→224dp。
- 图标仍是 Canvas 自绘：`DictIcons.Kind.REFRESH`（开口圆弧 + 箭头尖；
  整圈在小尺寸下会和"进度环"混淆）。
- 1 秒一跳的状态刷新只在观测页可见时跑（切页/离开即停）。
- 踩坑记录：**python heredoc 里写 `\/\/` 不会变成 `//`**（保留字面反斜杠），
  导致 `replace` 静默失配、Java 里出现非法字符。改 `.java` 一律走 edit_file
  或写纯 `//`，并且 `replace` 必须带断言。

### 门禁与验收

- 新增 `ScanGateTest` 30 项（闸门三态 / 向上取整 / 冷却 / 差异比对 / 大小写空白）。
- `ThemeConsistencyTest` 补 4 条（有重扫控件 / 状态行 / 差异 / 节流如实显示）。
- 门禁 **2662 通过 / 0 失败**。出包 **1.9.26 (120)**：1,219,794 B，装机启动无崩溃。

## 三·补、WPS 通道：**回包被 SELinux 拦掉**（2026-10-26 实测定论）

### 症状
WPS 扫描能出 AP 列表（走 `cmd wifi list-scan-results`），但 `BSS_ENUM=0`、
每个目标 `verdict=UNKNOWN`、`wps_device_name` 全空 —— 凡 supplicant 派生的数据全读不到。
原来界面上只显示「能力未知」，用户无从区分「这些 AP 真没能力」和「压根没读到」。

### 根因（内核审计铁证）
```
avc: denied { sendto } comm="wpa_supplicant"
  path=0070726F62655F3330333934        # \0probe_<pid> —— 客户端的抽象 socket
  scontext=u:r:hal_wifi_supplicant_default:s0
  tcontext=u:r:magisk:s0
  tclass=unix_dgram_socket permissive=0
```
helper 经 `su -c ...` 跑在 magisk 域，supplicant 策略不允许往该域回包 →「发得出去、收不回来」。
**三种独立复现一致**：app 自带 `WpsHelper`、`/vendor/bin/wpa_cli`、自写 LocalSocket 探针（4 种寻址全试）。
`Conn.read()` 把超时吞成空串，所以上层只看到 UNKNOWN。

### 判定链上踩过的坑
- **文件系统 inode ≠ socket inode**。`stat -c %i /data/vendor/wifi/wpa/sockets/wlan0` 给的是
  socket **文件**的 inode；fd 里的 `socket:[数字]` 是另一个空间。拿它比对会误判「socket 是僵尸」。
  正解是看 `/proc/net/unix`（`St=03` 即 LISTEN，inode 67118737 属于 supplicant 的 fd）。
- `su -Z u:r:shell:s0` 换域**更差**：shell 域连 bind/connect 都 EACCES。
- 客户端绑**抽象名**才收得到回包；绑 `/data/vendor/wifi/wpa/sockets/wpa_ctrl_*` 文件路径的变体
  即使补了规则也不通（机制未验；不影响结论，app 的 `Conn` 本来就是抽象绑定）。

### 修法（已验证）
```
allow hal_wifi_supplicant_default magisk unix_dgram_socket sendto
```
- 内存注入验证：`magiskpolicy --live "allow hal_wifi_supplicant_default magisk unix_dgram_socket sendto"`
  （**MagiskPolicy 语法不带 `:` 和 `{}`**，带冒号会 Syntax error）。
- 补上后同一个 helper：`BSS_ENUM` 0 → 34、`AP_COUNT` 21 → 23、verdict 从全 UNKNOWN 变
  `PIN_OK` / `NO_PIN`、设备名出 `BroadcomAP` / `fiberhome ONT` / `huawei_ONT` / `E5576-821` /
  `PSDN-TC32-10`。
- **持久化：第一次做错了。** 先按常规建了模块目录 `sepolicy.rule`，
  **用户重启后实测不生效** —— 活策略里查不到该条，`CTRL_OK=0`、`BSS_ENUM=0` 回落；
  `/cache/magisk.log` 里没有该文件的任何记录。**原因未确认，不猜**（已把那个空壳模块目录删掉）。
  改用 **`/data/adb/service.d/wpaforge-wps-policy.sh`**：开机 `magiskpolicy --live` 注入，
  带幂等判断 + 自记日志 `/data/adb/wpaforge-wps-policy.log`。
  机制已证：本机 service.d 另有 8 个在用脚本，重启后 `apk_index_httpd.sh` 起的 8732
  与 `cliproxy-boot.sh` 起的 8317 都在 LISTEN。
- 手工执行该脚本已验证：执行前 `--print-rules | grep -c` = 0 → 执行后出现
  `allow hal_wifi_supplicant_default magisk unix_dgram_socket { sendto }`，日志「注入成功」；
  第二次执行走「已存在，跳过」（幂等）。
- 重启后再次实测（规则经脚本注入）：`CTRL_OK=1`、`BSS_ENUM=39`、`CAP_TOTAL=37`、`AP_COUNT=27`；
  设备名 `BroadcomAP`×22 / `fiberhome ONT`×3 / `E5576-821` / `RalinkAPS_0`。
- 名单命中：这 5 个设备名（含新增 `RalinkAPS_0`）**全部未命中**（名单 164 条）。

### 名单匹配的真实命中率（vulnwsc.txt 164 条 / 3499 B）
本机 23 个 AP、5 个不同设备名（BroadcomAP / fiberhome ONT / huawei_ONT / E5576-821 /
PSDN-TC32-10）**命中 0 个**。阳性对照 5/5 命中、负对照 2/2 未命中。
→ 标记的价值在「碰到名单里的型号能提前说」，不是「每次都有结论」。

### 代码落点
| 文件 | 作用 |
|---|---|
| `wps/VulnWsc.java` | 名单解析 / 归一化 / 匹配（含裸泛称拒判） |
| `wps/VulnWscStore.java` | 按需下载落盘（许可不明不进包），复用字典源多通道探测 |
| `wps/VulnUi.java` | 标记文案 + 状态诊断（纯逻辑，可离线测） |
| `WpsHelper` | 新增 `CTRL_OK=1|0`：扫描时探一次 `STATUS`，把通道结论交给界面 |
| `MainActivity` | 扫描/下载/渲染接线 + `wpsVulnBtn` / `wpsVulnDiag` |
