# WiFi 工具箱真实源码对照（2026-11-20，Apache 2.0）

> 仓库：https://github.com/bszapp/android-wifi-pojie
> 分支 `v3.x` / 提交 `56ae0bc83aeecfd08342a45be0f0c5b6fc1fe369`（2026-10-24）
> LICENSE：**Apache License 2.0**（允许借鉴，需保留声明）
> 方法：`git clone` 后直接读 `.kt` 源码，**不是** DEX 字符串或博客

## 1. 核心发现：它根本不走 `cmd wifi` shell

`ShizukuUtil.kt` 的实际做法：

```kotlin
private fun asInterface(className: String, original: IBinder): Any {
    return Class.forName("$className\$Stub").run {
        getMethod("asInterface", IBinder::class.java).invoke(
            null, ShizukuBinderWrapper(original))!!
    }
}

fun connectToWifi(ssid: String, password: String): Int {
    val wifiService = asInterface("android.net.wifi.IWifiManager",
        SystemServiceHelper.getSystemService(Context.WIFI_SERVICE))
    val addMethod = getWifiMethod(wifiService, "addOrUpdateNetwork")
    ...
    enableNetwork(netId)
    return netId
}
```

**结论**：用 `ShizukuBinderWrapper` 包住 `IWifiManager` binder，
反射直调 `addOrUpdateNetwork` / `enableNetwork` / `forget` / `getConnectionInfo`。
**没有 shell 进程、没有管道、没有 AOSP 的 `countDownLatch.await(500ms)`。**

这解释了为什么它不会出现我们那种「每条 11 秒」——
我们的 11 秒 = shell 进程 + 管道 + 500ms 等待 + join 阻塞。

## 2. 身份判定：按 `Shizuku.getUid()` 动态分级

`ShizukuUtil.kt:572-576`：

```kotlin
val user = when (Shizuku.getUid()) {
    0 -> "root"
    1000 -> "system"
    else -> "shell"
}
```

**它不写死 shell，而是按真实 uid 选冒充身份。**
这是 WPAForge 之前缺的（我们只区分 `S_READY` / `S_READY_NOT_SHELL`，
但能力探测没用上）。

## 3. 三分支执行模式

`ConnectWorker.forgetNetwork`：

```kotlin
val netId = when (settings.connectMode) {
    1 -> ShizukuUtil.getNetIdBySsid(ssid)          // Shizuku 直调
    2 -> AidlServiceHelper.getNetIdBySsid(app, ssid) // Root AIDL (libsu)
    3 -> ApiUtil.getNetIdBySsid(service, ssid)      // 公开 API
    else -> -1
}
```

Root AIDL 用 **libsu `RootService`**（`com.topjohnwu.superuser.ipc.RootService`），
`AidlService.kt` 里 `expectedUid` 校验调用者，`Process.myUid()` 返回真实身份。

## 4. 对照表

| 功能 | WiFi 工具箱实现 | WPAForge 现状 | 差异 | 是否采用 |
|---|---|---|---|---|
| 连接 | `IWifiManager.addOrUpdateNetwork` + `enableNetwork` | `cmd wifi connect-network` | 直调 vs shell | **已采用直调** |
| 删配置 | `IWifiManager.forget` / `removeNetwork` | `cmd wifi forget-network` | 同上 | **已采用直调** |
| 读已保存 | `getConfiguredNetworks` / `getPrivilegedConfiguredNetworks` | `cmd wifi list-networks` | 同上 | **已采用直调** |
| 读连接 | `IWifiManager.getConnectionInfo` | `cmd wifi status` 解析文本 | 结构化 vs 文本 | **已采用直调** |
| 身份判定 | `when (Shizuku.getUid())` 三级 | 只有 `S_READY`/`S_READY_NOT_SHELL` 两态且能力探测未用 | 缺分级 | **已采用** |
| Root AIDL | libsu `RootService` + 自定义 AIDL | 无（只有 `su -c`） | 缺持久服务 | **未采用**（见 §5） |
| 日志 | `WifiLogcatService` + `WifiBroadcastService` 双通道 | 只有 logcat | 缺广播通道 | **未采用**（见 §5） |
| 判定 | `handshakeCount` / `handshakeUseTime` vs 阈值 | AT3 + Adjudicator + EvidenceGrade | 我们更严 | **不采用** |

## 5. 不采用的部分及理由

**① libsu Root AIDL Service**

它的价值是「持久 root 服务，避免每条命令起一次 su」。
但 WPAForge 的 `RootChannel` 已有 `ShellSession` 常驻会话（实测 137ms → 20ms/次），
效果等价。引入 libsu 会增加一个第三方依赖 + 一个 AIDL 文件 + 服务生命周期管理，
**收益不足以抵消复杂度**。若将来 su 启动成本成为瓶颈再评估。

**② 广播日志通道**

`WifiBroadcastService` 监听 `NETWORK_STATE_CHANGED_ACTION` 等广播。
但 Android 12+ 已废弃广播里的 BSSID/SSID extra，拿不到归属信息。
**对 AT3 归因没有帮助**，不采用。

**③ 它的判定逻辑**

`checkHandshakeFailure` 只看握手次数/耗时是否超阈值：
```kotlin
PojieConfig.getFailureFlag()
WifiLogData.getHandshakeCount() vs getMaxHandshakeCount()
WifiLogData.getHandshakeUseTime() vs getTimeout()
```
**没有 netId 绑定、没有净检回读、没有前置断开**。
握手重试可能是 AP 限流，不代表密码错 —— 比我们的 AT3 宽松，**不采用**。

## 6. 许可证义务

采用其技术路线（方法签名映射表）时：
- Apache 2.0 要求保留版权声明与许可文本
- 本项目的 `WifiServiceBridge` 是**独立实现**（Java，非 Kotlin 复制），
  仅在注释中标注「版本签名映射参考 WiFi 工具箱（Apache 2.0）」
