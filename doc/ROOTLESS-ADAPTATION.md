# WPAForge 无 Root 适配 —— 交付文档

> 基线：`baseline-before-rootless-20261118` → `db97138d46dce649f1900fcf7daee4fe1bde00d4`
> 分支：`feature/rootless-adaptation`
> 版本：**1.9.61 (157)** —— 按要求**未修改版本号**（本次不是正式发布）
> 日期：2026-11-18

---

## 0. 一句话结论

无 Root 模式**不是**「勉强能打开」：字典、离线分析、任务与记录、周边扫描、局域网探测照常工作；
逐条自动关联、WPS PIN 执行、supplicant 日志取证、读取系统已保存网络**在本机确实做不到**，
这四项被明确标记为「本机不支持」并给出替代路径，**一条命令都不发、一个失败结论都不产**。

---

## 1. 基线（现场核实，不引用历史报告）

| 项 | 值 |
|---|---|
| 分支 / HEAD | `main` / `db97138d46dce649f1900fcf7daee4fe1bde00d4`（2026-11-16 06:34:48 +0000） |
| 版本 | versionName 1.9.61 / versionCode 157（`1-aapt2.sh` 默认值 + 已装包 dumpsys 双向核对） |
| 工作区 | **不干净**：2 个已跟踪文件被改（`audit/ConfigRestorer.java` 45 行、`audit/SavedWifiReader.java` 98 行）、1 个未跟踪（`ConfigStoreCompatTest.java`）；无 stash |
| remote | `origin` = https://github.com/wlmosv-png/wpaforge.git，本地与 `origin/main` 同点（ahead/behind 0/0，未 fetch） |
| 门禁基线 | **8332 通过 / 0 失败 / 221 个测试类** |
| 已装 APK | 1.9.61 (157)，firstInstallTime 2026-10-10 22:57:05（沿用旧签名，可覆盖安装） |

### 保护动作

1. `git tag -a baseline-before-rootless-20261118` → 指向 `db97138`，**annotated tag，不会随分支移动**。
2. 新建分支 `feature/rootless-adaptation`（从基线直接开）。
3. 原有的未提交改动**一字未动**：单独备份到 `/workspace/rootless-backup-20261118/`
   （`worktree-dirty.patch` + `ConfigStoreCompatTest.java`），并随分支保留（已在第一个 commit 里提交）。
4. `main` 全程未提交任何内容，仍停在 `db97138`。

### 精确回滚

```sh
# 回到本次任务开始前的源码状态（含工作区原样）
git -C /workspace/promo-wpaforge checkout baseline-before-rootless-20261118

# 或只丢弃本分支的改动、保留分支结构
git -C /workspace/promo-wpaforge checkout feature/rootless-adaptation
git -C /workspace/promo-wpaforge reset --hard baseline-before-rootless-20261118
```

---

## 2. Root 依赖审计（结论 + 证据）

全仓显式 `{"su", ...}` 构造共 **21 处**，另有 `WpsHelper` 内部 `sh -c`。**没有隐式 su**：
`ShellExec` / `ShellSession` 只是通用进程包装器，su 由调用方放进 argv。

### 2.1 按功能分类

| 类别 | 命令 | 位置 |
|---|---|---|
| 环境自检 | `su -c id`、`cmd wifi help`、`cmd wifi list-networks` | `audit/RootProber.java:78,105,127` |
| 连接 / 判定 | `cmd wifi status`、`list-networks`、`forget-network <id>`、`connect-network` | `audit/CrackEngine.java:1238,1346,1358,2090`；`audit/ConnectSpec.java:83` |
| 配置读写 | `cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`（及 `/data/misc/wifi/`） | `audit/ConfigRestorer.java:423`、`audit/SavedWifiReader.java:64,92` |
| 保活 | `appops set <pkg> SYSTEM_ALERT_WINDOW allow`、`dumpsys deviceidle whitelist +<pkg>`、`settings put global lspdoze...` | `CrackService.java:747,845,849`、`MainActivity.java:13582-13590` |
| 日志取证 | `logcat -d -s wpa_supplicant -s <marker>` | `audit/WpaLogReader.java:291`、`audit/LogcatCmd.java:20` |
| 特权扫描 | `cmd wifi list-scan-results`（`ProcessBuilder("su",...)` 独立实现） | `scan/PrivScanReader.java:100` |
| WPS | `su -c "CLASSPATH=<apk> app_process /system/bin <HELPER> ..."` | `wps/WpsController.java:574-586` |
| 局域网 | **不使用 su**：`ip route` / `ping` / `/proc/net/arp` / `Socket` | `scan/LanProbeExecutor.java:87,209,223,340` |

### 2.2 WPS 到底依赖什么（明确回答）

- 依赖 **vendor supplicant unix dgram 控制口**：`/data/vendor/wifi/wpa/sockets/wlan0`，
  在其上发 `WPS_PIN` / `STATUS` / `BSS` / `ATTACH`（`wps/WpsHelper.java:24,652,1057`）。
- 还需要 `magiskpolicy --live` 注入 SELinux 规则（`WpsHelper.java:110`），因为 app 域无权写该 socket。
- **不使用** monitor 模式、**不使用**原始 802.11 帧注入、**不使用** hostapd / wpa_cli
  （`rg 'monitor|NL80211|pcap|tcpdump|aireplay|reaver|wpa_cli|hostapd'` 全仓 0 命中，仅 `injectSelinux` 函数名）。
- 结论：无 Root 下 **不可实现**，且没有「降级实现」的可能。

### 2.3 被错误绑定到 Root 的功能

审计结论：**离线链路本来就没有 root 前置**（`DictPlan` / `OfflineImport` / `OfflineVerdict` /
`Hc22000` / `TaskQueue` / `RunLog` / `HistStore` 全部 0 处 root 引用）。

真正被错误压制的是 **局域网探测**：`ui/LanProbeActivity.java:280` 有 `if (!isRoot()) → 直接中止`，
而其执行器 `LanProbeExecutor` 全程不用 su。本次已移除该前置（改为如实提示 ping/ARP 可能读不到）。

### 2.4 UI 层把 Root 当运行条件的地方（改造前）

| 位置 | 行为 |
|---|---|
| `MainActivity.startConnectAfterGuards():12367` | `RootProber.check()` 不 ok → **整个破解流程不准入队** |
| `MainActivity.refreshModeReady():14268` | 只问 root，红/绿两色，无细节 |
| `MainActivity.fillModeInfo():14229` | 无条件写「当前: Root 模式（推荐）」 |
| `Prefs.mode():274` | 恒 `return MODE_ROOT`（设置项被当成能力真相） |
| `Prefs.modeLabel():388` | 恒 "Root 模式" |
| `audit/SavedWifiReader.checkAccess():61` | 非 root 直接返回错误串 |
| `audit/EnvDiagnostics:83` | su 不可用 → `failCritical()` |
| `app/App.java:212` | 启动自检用匿名 su 通道 |

---

## 3. 新架构

```
              ┌──────────────────────────────┐
   业务层 ───▶│  cap/Capability（五档状态）   │
（引擎/UI/WPS）│  AVAILABLE / NEEDS_GRANT      │
              │  UNSUPPORTED / UNKNOWN        │
              │  DEGRADED                     │
              └──────────────┬───────────────┘
                             │
              ┌──────────────┴───────────────┐
              │  cap/ConnectChannel（语义接口）│
              │  open/close/name              │
              │  associate()  forgetTarget()   │
              │  readState()  listSaved()      │
              │  readSupplicantLog()           │
              │  canAssociate() canForget()    │
              │  state()                      │
              └───────┬──────────────┬────────┘
                      │              │
        ┌─────────────┴───┐   ┌──────┴──────────────┐
        │ cap/RootChannel │   │ cap/RootlessChannel │
        │ su + 常驻会话   │   │ 全部关联能力=false   │
        │ + 熔断（原样）  │   │ 一条命令都不发       │
        └─────────────────┘   └─────────────────────┘

        cap/CapabilityProbe（纯逻辑解释）
        cap/CapabilityDetector（实测 + 缓存，按模式分开）
        cap/RunMode / cap/RuntimeMode（模式真相来源，进程级单例）
```

### 关键设计点

1. **为什么在 `ShellChannel` 之外再加 `ConnectChannel`**
   `exec(String)` 假定「给命令就有效果」。非 root 下这条假设不成立（实测：应用 UID 执行
   `cmd wifi status` 返回空输出），再往里传命令只会得到空白，而空白会被判定链读成「没连上」。
   所以能力必须在**语义层**开口子：不是「能不能执行这条命令」，而是「能不能逐条试口令」。

2. **为什么不给无 Root 提供「伪通道」把命令吞掉**
   那样判定链会拿到空输出 → notConnected → 「这条密码不对」。这是**伪造结论**，
   比直接说「本机做不到」坏得多。

3. **模式三态**：用户意愿（`Prefs.requestedMode` = ROOT/ROOTLESS/AUTO）与
   实际生效（`RuntimeMode`）分离；不一致时 `RunMode.notice()` 必须明说，不许默默降级。
   历史队列值 `1`(免 Root) / `2`(Shizuku) 仍解释为 Root；新标记值 `9` 才是无 Root。

---

## 4. 能力矩阵（Android 15 / OnePlus PJX110 实测）

| 功能 | Root 模式 | 无 Root 模式 | 依据 / 处理 |
|---|---|---|---|
| 应用启动 | 支持 | 支持 | 共用 |
| 字典管理（内置/派生/导入/计划） | 支持 | **支持** | 共用业务层，无 root 前置 |
| 离线文件导入 | 支持 | **支持** | SAF / 私有目录，共用 |
| 离线分析（hc22000 / PMKID / EAPOL / 校验） | 支持 | **支持** | 共用业务层，纯本地计算 |
| 任务管理 / 记录 / 导出 | 支持 | **支持** | 共用 |
| 普通 Wi-Fi 扫描 | 支持 | **支持** | `startScan`+`getScanResults`（需 NEARBY_WIFI_DEVICES 或定位） |
| 当前连接读取 | 支持 | **支持** | WifiInfo / ConnectivityManager |
| 局域网探测 | 支持 | **降级可用** | ping/ARP 受 SELinux 限制 → 状态标 DEGRADED，结果缺列如实呈现 |
| WPS 能力识别 | 支持 | **降级可用** | 只剩本地 IE 通道 → `rootChannelAbsent=true`、来源 `ABSENT` |
| 逐条自动关联 | 支持 | **不支持** | 应用 UID 拿不到 `cmd wifi`；`WifiNetworkSpecifier` 每条弹确认框 |
| 净检（删旧配置） | 支持 | **不支持** | 需系统权限 |
| supplicant 日志取证 | 支持 | **不支持** | 本应用 UID 的 logcat 读不到该 tag（实测空输出） |
| 特权扫描兜底 | 支持 | **不支持** | 依赖 `cmd wifi list-scan-results` |
| WPS PIN 执行 | 支持 | **不支持** | 需写 supplicant socket + magiskpolicy |
| 读取系统已保存网络 | 支持 | **不支持** | `/data/misc` 不可读 |
| 后台保活加固 | 支持 | **不支持** | `appops` / `deviceidle whitelist` 需 su（可用系统设置手动豁免） |
| 日志解析 / 证据判定 / 结果展示 | 支持 | 支持 | 共用（无证据时保持 UNKNOWN/INCOMPLETE） |

### 支撑实测（2026-11-18，本机）

```
as uid=0      : cmd wifi status → 正常输出（含 WifiInfo 行）
as uid=2000   : cmd wifi status → 正常；cmd wifi list-networks → 正常
as uid=10381  : cmd wifi status → **空输出**（应用 UID，含装在 /data/app 的情形）
                dumpsys wifi → Permission Denial: can't dump WifiService from uid=10381
                logcat -d -s wpa_supplicant → 空
```

结论：binder 按**调用者 UID** 拦截，与安装分区、SELinux 域无关；这不是「配置问题」，是系统边界。

---

## 5. 功能与判定链的实现改动

| 文件 | 改动 |
|---|---|
| `cap/Capability.java`（新） | 五档状态；`evidenceGrade()` 只有 AVAILABLE 为真 |
| `cap/ConnectChannel.java`（新） | 语义级通道接口 |
| `cap/RootChannel.java`（新） | Root 通道：原样搬运 su/常驻会话/熔断 |
| `cap/RootlessChannel.java`（新） | 无 Root 通道：`associate/forget` 恒 false 且**一条命令都不发** |
| `cap/CapabilityProbe.java`（新） | 纯逻辑解释：用法表问不到 ≠ 本机没有 |
| `cap/CapabilityDetector.java`（新） | 实测 + 按模式分开的缓存 + `resolve()` 决定实际模式 |
| `cap/RunMode.java`（新） | 模式常量、存储值兼容、降级提示 |
| `cap/RuntimeMode.java`（新） | 进程级单一真相 + 显式失效 |
| `audit/AttemptState.java` | Gate 新增 `NOT_CAPABLE` |
| `audit/Adjudicator.java` | 规则 0a：`NOT_CAPABLE → NOT_EXECUTED / NONE / not-capable` |
| `audit/CrackEngine.java` | `Verdict` 新增 `NOT_EXECUTED`；`setConnectChannel` / `canAttemptAssociation` / `inabilityReason` / `modeLabelForLog`；`tryOneShell` 首段能力闸；`run()` 开跑前能力闸；结论文案含「未执行」 |
| `audit/VerdictTally.java` | `notExecuted` 单列，不计入 executed / inconclusive |
| `audit/TriedSet.java` | `targetKey(bssid,ssid,scope)` + `scopePrefix`（root 为空串） |
| `audit/AttemptLedger.java` | 键同源（天然隔离） |
| `CrackService.java` | 按实际模式装通道；键带模式；日志首行不再写死 Root |
| `Prefs.java` | `mode()` 转发实际能力；新增 `requestedMode` / `setRequestedMode`；`modeLabel` 同源 |
| `MainActivity.java` | 闸门改为能力判定；模式三按钮 + 分模式说明卡；状态行输出能力矩阵；`wpsCapabilityOf` 按能力决定通道 A |
| `wps/WpsCapabilityManager.java` | `rootChannelAbsent` 入参 + `SRC_ABSENT` |
| `wps/WpsUiKit.java` | `ABSENT` 文案 |
| `ui/LanProbeActivity.java` | 去掉 root 前置，改为提示 |
| `ui/VerdictView.java` | `NOT_EXECUTED` 全套口径（⌀ / 未执行 / 不算试过 / 需要注意） |
| `audit/EnvDiagnostics.java` | su 缺失只在 Root 模式下 critical；输出能力矩阵 |

### 判定链不变式（专项门禁，全部通过）

1. 没有无线证据 → 不判密码错（`Adjudicator` 规则 6/9 + `NOT_CAPABLE` 0a）。
2. 权限不足 ≠ 目标认证失败：无 Root 走 `NOT_EXECUTED`，不是 `AUTH_FAIL`/`NETWORK_AUTH_FAIL`。
3. 连接成功不能冒充 WPS PIN 成功：WPS 链独立，`terminalSuccessKnown()` 恒 false，`level()` 只到 `L_PENDING`。
4. 不完整日志 → 不产 DIRECT：`evidenceGrade()` 与 `persistable()` 只在 DIRECT 为真。
5. 不错误写 TriedSet：只有 `AUTH_FAIL ∧ DIRECT` 写（`CrackService.java:1378-1393`）。
6. 无证据不推进游标：只有 `advancesCursor(DIRECT)`。
7. 两种模式不互相污染：`TriedSet`/账本键带模式前缀（root 前缀为空串 → 老文件照读）。

---

## 6. 测试结果

### 6.1 单元 / 门禁（Linux，全量）

| 阶段 | 通过 | 失败 | 测试类 |
|---|---|---|---|
| 基线（改造前） | 8332 | 0 | 221 |
| 阶段 B-1（能力层骨架） | 8465 | 0 | 222 |
| 阶段 B-2（引擎闸 + 隔离） | 8536 | 0 | 223 |
| 阶段 E（UI） | 8574 | 0 | 224 |
| 阶段 D（WPS） | 8574 | 0 | 224 |
| 真机修补 | **8581** | **0** | 224 |

新增测试类：`CapabilityLayerTest`(133)、`RootlessVerdictChainTest`(72)、`RootlessUiConsistencyTest`(37)。

**测试修改说明（如实记录）**：`AdjudicatorMatrixTest`(192→240 格)、`P2KernelReauditTest`⑧(3→4 格)、
`VerdictDownstreamTest`(闭合条件)、`DelayedSuccessTest`(1 条口径)。
均为**语义扩充**：I1~I6「不得采信 / grade 必须 NONE / 不得是终局失败」逐条保留，无一条被放宽或删除。

### 6.2 真机（OnePlus PJX110 / Android 15 / ColorOS）

| 项 | 结果 |
|---|---|
| 安装（`pm install -r`，签名一致） | ✅ Success，firstInstallTime 未变（覆盖安装） |
| 启动 | ✅ 无崩溃 |
| 设置页模式切换 Root / 无 Root | ✅ 即时生效，整块重建 |
| 无 Root 能力矩阵展示 | ✅ 逐项列出「本机不支持 + 原因 + 替代」 |
| 破解页闸门 | ✅ 被能力层拦下并给出原因 + 替代路径 |
| 诊断日志能力矩阵 | ✅ 12 项逐条输出（见 `WPAForge-log-20261118-223718.txt`） |

### 6.3 真机发现并已修复的问题（重要）

1. **一条命令都没发却显示「跑过一轮」** —— 336 条在 9 秒内「跑完」，
   界面报「已执行 336 条 · 325 条无结论」。根因：`run()` 开跑前没有能力闸。
   已修：`run()` 开头、通道自检之前挡一次 → `onBlocked` 明说「一条命令都没发出」。
2. **「没执行」被并进「无结论」** —— 新增 `Verdict.NOT_EXECUTED` + `VerdictTally.notExecuted` 单列。
3. **日志首行写死「引擎模式: Root」** —— 改为按实际模式输出。
4. **`RuntimeMode` 未探测时回退到恒 Root** —— 改为回退到用户意愿。

### 6.3.1 Shizuku 通道五个真 bug（2026-11-20 收尾）

上一轮把「类加载 OK」当成通了，实际离能用还差五处。全部有 logcat 或对照实验作证：

| # | 缺陷 | 现象 | 证据 |
|---|---|---|---|
| 1 | `ShizukuProvider` 不在 dex 里 | 装上后**一启动就闪退** | `Unable to get provider rikka.shizuku.ShizukuProvider: ClassNotFoundException` |
| 2 | `newProcess` 事务码写成 7 | 命令永远跑不起来 | `IShizukuService.Stub` 实际是 `newProcess=8`；7 命中 `setSystemProperty` |
| 3 | `authorities` 用了 `${applicationId}` | Provider 注册成字面量 | aapt2 link 不替换该占位符，`dumpsys` 里就是 `${applicationId}.shizuku` |
| 4 | `ParcelFileDescriptor` 读法错 | `exit=0` 但输出恒空 | AIDL 用 `readInt()!=0` + `CREATOR.createFromParcel`；`readFileDescriptor()` 是另一套编码 |
| 5 | 先 `waitFor` 再读流 | 输出超 64KB 即死锁 | 子进程阻塞在 write，waitFor 永不返回 |

修法要点：
- `app/libs/shizuku-api.jar` 31 → **35 类**，把 `prov-13.1.5.aar` 的
  `ShizukuProvider{,$1}` 与 `moe/shizuku/api/BinderContainer{,$1}` 合进来
  （`ShizukuProvider` 属于**另一个 aar**，不在 api aar 里 —— 这是最初踩空的地方）。
- manifest 补 `moe.shizuku.client.V3_SUPPORT=true`（官方 aar 自带，不声明则按旧协议）。
- 读流改为：两个 daemon 线程边读边攒，主线程只轮询 `waitForTimeout`。

**真机前后对照**（OnePlus PJX110 / Android 15 / Shizuku 13.6.0）：

```
修复前  Shizuku 状态: 1（服务未运行）        启动即崩
中间态  Shizuku 状态: 2（未授权）→ 4（以 root 运行）  可用: 是
        但 实测 cmd wifi: exit=0 OK 首行=（空）
修复后  实测 cmd wifi: exit=0 OK 首行=Wifi is enabled
```

**方案根基的对照实验**（决定「无 Root 能不能真跑字典」）：

| 执行身份 | `cmd wifi status` | `cmd wifi help` |
|---|---|---|
| `uid 2000`(shell) | 返回真实 WifiInfo（连着 "211"） | 含 `connect-network` / `forget-network` |
| `uid 10601`(应用自身) | **空输出** | — |

即：binder 按调用者 UID 拦，**拿到 shell 身份就能自动连接**，不需要 root。
这条路径现在通道本身已验证可用；剩下的是拿到纯 shell 身份（非 root 启动的
Shizuku）后跑一轮真实字典，那需要一台未 root 设备或把 Shizuku 改成无线调试启动。

### 6.3.2 安卓 13 未 root「点破解没反应」—— 自检把能用的通道自己挡死了（2026-11-20）

用户报障（另一台**安卓 13、未 root** 的设备）：

> 装上了，但没看到要索取权限；点破解后没效果，提示「不支持 cmd」。

**真因不是「不支持 cmd」**。`probeChannel()` 原来**无条件**要求 shell 通道，
然后真跑一条 `cmd wifi status`。未 root 的应用 UID 执行该命令返回**空串**
（binder 按调用者 UID 拦，本机对照实验已确认），于是：

```
命令返回空 → looksLikeWifi=false → 「通道未真正接入」
usageKnown=false → 但 Specifier 分支根本没写在 probeChannel 里
```

真正能用的 `WifiNetworkSpecifier` 通道**从头到尾没被检查过**。
用户看到的那句「本机不支持（缺子命令）」就是这么来的。

#### 三处修复

| # | 缺陷 | 修法 |
|---|---|---|
| 1 | `probeChannel()` 用 shell 判一切 | `specifier != null` → 走 Specifier 自检（`probeSupport()` + 读当前连接），**完全不碰 shell**；且该分支必须排在 `shell == null` 之前 |
| 2 | 无 Root 时「先 su、失败才试 Shizuku」 | 未 root 机器每条命令先白等 su 超时（实测 8s/条 × 几十条 = 几分钟不动）。改为按模式决定顺序：无 Root 且 Shizuku 可用 → 直接走 Shizuku；su 保留兜底 |
| 3 | 启动自检写死「当前模式: Root」 | 改 `RunMode.nameOf(mode)`，并按真实通道挂 shell / Shizuku / Specifier |

真机前后对照（本机）：

```
修复前  当前模式: Root      通道类型: Root      结论: 未接入
修复后  当前模式: rootless  通道类型: Shizuku  结论: 已接入（9 项全绿，耗时 111ms）
```

#### 用户引导（用户明确要求「希望能引导用户做」）

旧实现只往日志区写一行 —— 日志在页面下方、还会被后续行冲掉。
这就是「点击破解后没效果」的观感来源。现在：

- 新增 `CrackService.UiCallback.onBlockedNotice(title, detail)`：
  闸门拦下时界面**弹对话框**，不再只写日志。
- 新增 `MainActivity.showShizukuGuide()`：按 Shizuku 四种状态
  （没装 / 装了没启动 / 没授权 / 可用）给**具体到点哪里**的分步说明，
  并带可点按钮（去下载 / 打开 Shizuku / 立即授权）。
- 无 Root 且 Shizuku 未就绪时，拦截对话框直接给「怎么解决」→ 引导。

#### 门禁（新增 14 条，且验证过能抓到旧 bug）

`RootlessVerdictChainTest` 72 → **86** 条：

- `probeMustNotRequireShellInRootless` —— 钉住「Specifier 分支在 shell 判空之前」
  且「Specifier 自检不执行 `cmd wifi`」；
- `shizukuPreferredOverSu` —— 钉住「Shizuku 分支排在 su 会话之前」。

**有效性验证**：把 `CrackEngine` 还原成旧的顺序后重跑，
立刻报 2 条 FAIL（`Specifier 分支在 shell 判空之前` / `Specifier 自检不执行 cmd wifi`）——
说明这条门禁不是摆设。

### 6.3.3 子命令判据：「grep 用法表」被用户一句反问问穿（2026-11-20）

用户在另一台安卓 13 上拿到这份自检结果，然后反问：

> 咋可能没有 没有的话 他拿什么连接的

```
✓ status 子命令: 可用
✗ connect-network 子命令: 本机没有
✓ forget-network 子命令: 可用
```

**他说得对。** `forget-network` 删的正是 `connect-network` 建的配置，
逻辑上不可能一个有一个没有。中间那个「没有」只能是**文本没搜到**。

#### 旧判据为什么不可靠

```java
boolean hasConnect = usage.contains("connect-network");   // usage = cmd wifi help 的输出
```

用法表会被 ROM 裁剪、被终端宽度折行、被 shell 分帧截断 —— 三种情况都会让
某一行「消失」，而另外两行还在。这正是用户看到的形态。

#### 新判据：实际调用，看返回哪一句

本机对照实测（Android 15）：

```
存在的子命令   → Invalid args for connect-network: Argument expected after "connect-network"
不存在的子命令 → Unknown command: no-such-subcmd-xyz
不需要参数的   → 直接执行并返回真实结果（cmd wifi status）
```

新增 `audit/ShellSubcommand`（纯逻辑），五级判据按可靠性排序：

| 级 | 条件 | 结论 |
|---|---|---|
| ① | `Unknown command: <name>`（整词） | **MISSING** |
| ② | `Invalid args for <name>` | AVAILABLE |
| ③ | 无参子命令正常执行（输出含 wifi 语义词） | AVAILABLE |
| ④ | 用法表含该名字（弱证据） | AVAILABLE |
| ⑤ | 其余（空输出 / 超时 / 权限被拒） | UNKNOWN |

**只有 ① 才判死；UNKNOWN 一律放行。** 这条是历史事故换来的：
把「问不到」当成「没有」，会把好机器挡在门外。

#### 接入三处

- `CrackEngine.probeChannel()`：三个子命令各调一次；`MISSING` 才判不支持。
  同时把**原始返回**写进自检详情 —— 判成 UNKNOWN 时必须能看见为什么。
- `CapabilityProbe.explainRoot()`：新增 `Facts` 重载，实际调用优先、用法表只当弱证据；
  并区分「用法表拿到了但没有」→ MISSING 与「用法表根本没拿到」→ UNKNOWN。
- `CapabilityDetector`：真的去跑那三次调用。

#### 门禁：ShellSubcommandTest 19 条（全部用真机输出喂养）

覆盖：存在/不存在的真实句式、空/null/乱码必须 UNKNOWN、
**用户截图里那种截断的用法表不得判 connect-network 不存在**、
无参子命令正常执行要判 AVAILABLE、整词边界（`connect-network-v2` 不算命中）。

期间门禁抓到我自己的两个真 bug：
1. `Invalid args for status-x` 里 status 后面是 `-`，`isLetterOrDigit` 判假 → 误命中。
2. 规则③「有输出就算存在」太宽 → 乱码也命中，收紧为要求含 wifi 语义词。

#### 真机前后对照

```
修复前  · status 未知（问不到）/ ✓ connect / ✓ forget
修复后  ✓ status / ✓ connect-network / ✓ forget-network   9 项全绿
```

### 6.4 未做的测试（需要你来做，理由如下）

- **无 Root 真机验收**：本机是已 root 设备。按任务书，不能用「关闭 su 授权 / 隐藏 root」的
  本机冒充非 root 环境，因此**没有**在这台机器上做「无 Root 真机」验收。
  已提供的等效证据：① 应用 UID 执行 `cmd wifi status` 返回空的实测（见 4 节）；
  ② 无 Root 模式下的闸门拦截（UI + 日志）。真正的非 root 设备验收需另找一台未 root 机。
- **Root 模式联网回归（WPS 执行 / 逐条关联成功判定）**：你要求不要连 WiFi，故未做。
  Root 代码路径本身**未被改写**（`RootChannel` 为原样搬运），但「实跑一条命中」未验证。

---

## 7. 交付物

| 项 | 值 |
|---|---|
| 基线 tag | `baseline-before-rootless-20261118` → `db97138d46dce649f1900fcf7daee4fe1bde00d4` |
| 适配分支 HEAD | 见 §8（9 个 commit） |
| APK | `/data/local/tmp/wpf-shz15.apk`（+ 子命令判据改实际调用） |
| 版本 | 1.9.61 (157) —— 未改（非正式发布） |
| SHA-256 | `e6178667670028e965d81c635b8bbf285c439e62100a4da29773c01877820f61`（2026-11-20 子命令判据版） |
| 大小 | 1,879,487 B |
| 签名 | 既有 release key（与已装包一致，可覆盖安装） |

---

## 8. 分阶段提交

```
f2b3828 fix(ui):       模式说明文案与真实能力对齐
88e3eeb fix(rootless): 安卓13 无 root 点破解没反应 —— 自检拿 shell 判 Specifier
e16aa89 fix(shizuku): 命令能跑通但输出永远为空 —— 又两个真 bug
4f88b0c fix(shizuku): Provider 类没进 dex 导致启动即崩 —— 三处真 bug 一并修掉
2590ed8 feat(rootless): 借 Shizuku 拿 shell 身份 —— 无 root 也能自动跑字典
d8b316e fix(run):   真机发现的两个诚实性问题（未执行被算成跑过）
1a201eb feat(wps):  无 Root 下把「本机没有 root 通道」与「数据不完整」分开
cc6ffcd feat(ui):   运行模式选择与能力展示（Root / 无 Root / 自动）
c88051a feat(cap):  引擎接入能力闸 + 按模式隔离已试集合
81295d8 feat(cap):  能力层骨架 —— 状态五档 / 双通道 / 模式真相来源
```

---

## 9. 回滚与后续

- **回滚源码**：`git checkout baseline-before-rootless-20261118`
- **回滚 APK**：重装旧包即可（同签名、同 versionCode，`pm install -r` 直接覆盖）
- **未推送**：本分支与 tag 均**只在本地**，`main` 未动。是否推送由你决定。

### 建议的下一步（需要你决定）

1. 找一台**真正的未 root 设备**做无 Root 验收（本机能给的等效证据已在 §6.4 说明）。
   通道侧已经打通（§6.3.1 的真机前后对照），缺的是「Shizuku 以 uid 2000 启动」的环境。
2. Root 模式回归：在允许联网的时段跑一轮逐条关联 + 一条 WPS PIN，确认 `RootChannel` 搬运无偏差。
3. 无 Root 模式的「关联能力」若未来 Android 开放静默关联接口，只需补一个 `ConnectChannel` 实现，
   业务层与判定链无需改动 —— 这是本次分层的直接收益。
