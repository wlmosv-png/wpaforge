# WiFi 工具箱执行控制流研究（2026-11-20，源码级还原）

> 对象：`com.wifi.toolbox.debug` v3.0.0_Alpha-06（versionCode 5，minSdk 24，targetSdk 28）
> 方法：apk-index 索引（37425 类，未加固）+ smali outline + 调用引用还原
> 证据分级：**A**=仅 DEX 字符串；**B**=完整调用路径已还原；**C**=生产代码实际使用；**D**=目标设备实测

## 1. 结论摘要

| 项 | 发现 | 等级 |
|---|---|---|
| 连接方式分两套 | API 29+ 走 `WifiNetworkSpecifier`；否则走 Shizuku/AIDL | **B** |
| 清理旧配置 | `forgetNetwork()` 三分支：ApiUtil / AidlServiceHelper / ShizukuUtil | **B** |
| 判定依赖日志 | `checkHandshakeFailure` + `checkLogDataSync` 读 `WifiLogData` | **B** |
| 日志来源 | `WifiLogcatService`（logcat）+ `WifiBroadcastService`（广播）双通道 | **B** |
| 它比我们宽松 | 只看握手次数/耗时，**没有 netId 绑定、没有净检回读** | **B** |

**结论：WiFi 工具箱的判定标准比 WPAForge 的 AT3 宽松，不采用其判定逻辑。**
但它的**双日志通道**与**按 API 版本分流**值得借鉴。

## 2. 核心类结构（已还原）

```
com.wifi.toolbox.services.PojieService      (Service, 14 方法)
  └─ com.wifi.toolbox.services.pojie.ConnectWorker  (19 方法)
       ├─ connectToWifiApi29(String, String, Function1, Continuation)
       │    → ApiUtil.connectToWifiApi29(Context, String, String, Function1)
       │    → 返回 ConnectivityManager$NetworkCallback
       │    → 存进字段 connectWifiApi29Callback
       ├─ forgetNetwork(PojieSettings, String): boolean
       │    → 按 PojieSettings.getConnectMode() 三分支：
       │       ApiUtil.getNetIdBySsid / AidlServiceHelper.getNetIdBySsid
       │       / ShizukuUtil.getNetIdBySsid
       │    → 对应 forgetNetwork(netId)
       ├─ cleanConnection(PojieSettings)
       │    → ShizukuUtil.disconnectWifi() 或 ApiUtil.disconnectWifi(Context)
       │    → ApiUtil.cancelWifiRequest(Context, NetworkCallback)
       │    → 清空 connectWifiApi29Callback
       ├─ initLogServices(PojieSettings)
       │    → new WifiBroadcastService(Context)
       │    → new WifiLogcatService(Context, PojieSettings)
       │    → 按 getReadLogMode() 选广播 / logcat / 空
       ├─ checkLogDataSync(WifiLogData, ToolboxApp, SinglePojieTask, long, int): int
       │    → 读 readLogMode
       │    → 比对 WifiLogData.eventStartTime / event
       │    → 调 checkHandshakeFailure(...)
       └─ checkHandshakeFailure(WifiLogData, PojieConfig): boolean
            → PojieConfig.getFailureFlag()
            → WifiLogData.getHandshakeCount() vs PojieConfig.getMaxHandshakeCount()
            → WifiLogData.getHandshakeUseTime() vs PojieConfig.getTimeout()
```

## 3. 它的判定逻辑（关键差异）

```java
// checkHandshakeFailure 的全部输入（smali outline 还原）
PojieConfig.getFailureFlag()
WifiLogData.getHandshakeCount()  vs  PojieConfig.getMaxHandshakeCount()
WifiLogData.getHandshakeUseTime() vs  PojieConfig.getTimeout()
```

**它判断「密码错」的依据是：握手次数超限 或 握手耗时超时。**

**WPAForge 不采用，理由：**

| 维度 | WiFi 工具箱 | WPAForge |
|---|---|---|
| 归属绑定 | 无 netId 绑定 | netId 冲突检查 + attemptId 严格相等 |
| 净检 | 无回读确认 | forget + list-networks 回读 |
| 前置断开 | 无 | baselineDecision + disconnectAndVerify |
| 证据等级 | 单一（日志文本） | DIRECT / INFERRED / NONE 三级 |
| 错误密码误判为成功 | 未验证 | 已用 17 条真实 Adjudicator 测试封堵 |

**它的判据在「握手次数超限 → 判错」这一点上其实更宽松：**
握手重试可能是 AP 侧限流，不代表密码错。WPAForge 只在拿到
`AUTH_FAIL + DIRECT`（归属到本次配置的直接否认）时才写永久排除。

## 4. 值得借鉴的两点

### 4.1 按 API 版本分流

它有 `connectToWifiApi29` 这个方法名，说明**明确按 API 29 分流**：
- API 29+ → `WifiNetworkSpecifier`（`ApiUtil.connectToWifiApi29`）
- 更低 → shell / AIDL

**WPAForge 的做法一致**：`SpecifierChannel` 只在 `SDK_INT >= 29` 且
`ConnectivityManager != null` 时可用（`probeSupport()` 判定）。
**已对齐，无需改动。**

### 4.2 双日志通道

它同时有 `WifiLogcatService`（logcat）和 `WifiBroadcastService`（广播），
按 `getReadLogMode()` 选。这是为了应对「某些 ROM 不输出 logcat」的情况。

**WPAForge 的现状**：只用 logcat（`readSupplicantLog`），
本机实测 ColorOS 15 该 tag 为 0 行 → 日志判据整体失效。
**但 WPAForge 的判定不依赖日志**（AT3 靠 status 四条件同行），
所以这条缺失只影响「失败加速」，不影响正确性。

**可借鉴但优先级低**：广播通道在 Android 12+ 已废弃 BSSID/SSID extra，
拿不到归属信息，价值有限。

## 5. 未能还原的部分（如实声明）

| 项 | 状态 |
|---|---|
| `ApiUtil.connectToWifiApi29` 内部实现 | **未还原**（需 jadx） |
| `ShizukuUtil` 的 `newProcess` 调用方式 | **未还原**（仅知存在） |
| `PojieTaskManager.startLoop` 的调度逻辑 | **未还原** |
| 它在真实无 Root 设备上的实测行为 | **未做**（不擅自操作他人应用） |

**因此不能声称「已完全理解它的实现」。** 上表 1 中的「B」级结论
只覆盖到「调用关系已还原」这一层，不覆盖内部算法。
