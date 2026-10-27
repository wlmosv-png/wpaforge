# WPAForge 技术说明

> 面向代码审阅者。本文只讲**为什么这么做**，不讲怎么用。
> 
> **部分内容已过时（2026-10-22 注）**：第 2 节「三种通道」写于只有多通道的年代，
> Shizuku 与免 Root 通道已在 1.8.x 从界面与引擎中移除，现在只有 Root 一条通道；
> 第 5 节的测试数量也已落后（当前 475 项）。判定链设计、踩坑记录、构建链三节仍然有效。
> 使用说明见 `README.md`。

---

## 1. 一句话架构

```
                    ┌──────────────────┐
   三种执行模式 ───→ │   CrackEngine    │ ←── 唯一判定入口
   (Root/Shizuku/   │  （引擎，判定）   │
    免root)         └────────┬─────────┘
                             │ 只负责"把一条候选发出去"
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ShellChannel   ShellChannel   requestNetwork
        (Root/su)      (Shizuku)      (系统精确连接)
```

**核心设计原则：通道只发请求，判定全在引擎。**

这条原则是被三个真实 bug 逼出来的 —— 见第 4 节。

---

## 2. 三种通道的取舍

### 2.1 Root / Shizuku：shell 通道

走 `su -c` 或 Shizuku 的 shell 身份执行 `cmd wifi`。

```java
// 每条之前必须删净旧配置，否则见 4.2
for (int round = 0; round < 3; round++) {
    String list = exec("cmd wifi list-networks");
    if (!stillContains(list, ssid)) break;     // 确认删净
    exec("cmd wifi forget-network " + netId);  // 还在就再删
}
exec("cmd wifi connect-network '" + ssid + "' " + security + " '" + pw + "'");
```

**为什么用 `cmd wifi` 而不是直接反射 `IWifiManager`**：

`IWifiManager.addOrUpdateNetwork` 是更干净的做法（有则更新，不会复用旧凭据），
但在本机 Color OS 15 / Android 15 上反射失败：

```
Wi-Fi Binder 通道: 找不到 addOrUpdateNetwork
```

排查过程：

| 检查项 | 结果 |
|---|---|
| `service list \| grep wifi` | `514 wifi: [android.net.wifi.IWifiManager]` ✅ 服务存在 |
| `IWifiManager$Stub.asInterface(binder)` | 返回非 null ✅ |
| 遍历 Proxy 类 `getMethods()` | ❌ 无该方法 |
| 遍历其实现接口 + `IWifiManager` 接口 | ❌ 无该方法 |
| `/apex/com.android.wifi/javalib/framework-wifi.jar` | 解包后是空壳，不含方法定义 |

**结论**：该 ROM 上 AIDL 形态与 AOSP 不同，尚未定位。已列为待办，
当前用 `cmd wifi` + 删净校验兜底。

### 2.2 免 root：`WifiNetworkSpecifier` + `requestNetwork`

```java
WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .setWpa2Passphrase(password)
        .build();

NetworkRequest req = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)   // 必须去掉
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .setNetworkSpecifier(spec)
        .build();

cm.requestNetwork(req, callback);
```

**语义**（这是全部意义所在）：

| 回调 | 结论 |
|---|---|
| `onAvailable` | 系统确认连上 → 密码正确 |
| `onUnavailable` | 系统尝试后失败 → 密码错误 |
| 无回调 | 超时 → **不判成功** |

**为什么不用 `WifiNetworkSuggestion`**：它是白名单 —— 提交后由系统自己挑时间连，
应用**无法知道它到底试没试**。判定只能靠轮询 `getConnectionInfo()` 猜，
而该 API 在 Android 10+ 已被限制、返回过期值。实测结果是大量误判。

---

## 3. 多信号综合判定

**任何单一信号都不可靠**，这是误判的根源：

| 信号 | 单独使用的问题 |
|---|---|
| `cmd wifi status` 文本 | Shizuku 通道可能被截断；格式随 ROM 变化 |
| `onAvailable` 回调 | 某些 ROM 会因为**别的网络**触发 |
| `WifiInfo.getSSID()` | Android 10+ 受限，更新有延迟 |

引擎同时看三个，任一确认即成功：

```java
private String connectedSignal(String targetSsid, String statusOut) {
    String cur = currentSsid();
    if (targetSsid.equals(cur)) return "WifiInfo";          // 信号 1
    if (statusOut != null
            && statusOut.contains("connected to \"" + targetSsid + "\""))
        return "cmd-status";                                 // 信号 2
    return null;                                             // 都不确认
}
```

返回的 `confirmedBy` 会写进结果，日志里能看到**是哪条信号确认的** ——
这直接区分了"假阳性"和"真实成功"。

`onAvailable` 之后仍要**二次复核 SSID**：

```java
public void onAvailable(Network n) {
    String by = null;
    for (int i = 0; i < 12 && by == null; i++) {   // 最多等 1.8 秒
        by = connectedSignal(target.ssid, null);
        if (by == null) Thread.sleep(150);          // 系统更新 WifiInfo 有延迟
    }
    outcome.set(by != null ? 1 : 2);                // 复核不过 → 判失败
    latch.countDown();
}
```

---

## 4. 踩过的坑（这部分是本文重点）

### 4.1 命中被覆盖成未命中

```java
// 错误写法
if (verdict == SUCCESS) {
    l.onFinished(NORMAL, tested, cursor, password);   // 带密码报一次
    return;
}
} finally {
    l.onFinished(oc, tested, cursor, null);           // return 之后照样执行
}
```

`finally` 在 `return` 后仍会执行，服务端**收到两次收尾**，
第二次密码是 `null`，把 `DONE_HIT` 覆盖成 `DONE_MISS`。

**日志表现**：

```
✓ 成功 12345678 (937ms 已连上)
★ 命中！ 211 = 12345678
任务完成，未命中（试了 1 条）      ← 矛盾
```

**修复**：加 `finished` 标志，`finally` 里判断。

**教训**：这个 bug 在 `RootProber` 和 `SuggestionProber` 里**各有一份** ——
因为判定逻辑有两份代码。这是引入统一引擎的直接动因。

### 4.2 配置复用导致假阳性

`cmd wifi connect-network` 对**同名网络会复用已保存的配置**。
如果目标之前连过（或上一次尝试留下了配置），则不论传什么密码，
系统都拿旧配置去连 → **连上了，报成功**。

**实测**：密码填 `00000000` 也报"命中"。

**修复**：每条之前删配置，并且**删完回读校验**：

```java
for (int round = 0; round < 3; round++) {
    String list = exec("cmd wifi list-networks");
    if (!stillContains(list, ssid)) break;          // 确认真删了
    exec("cmd wifi forget-network " + netId);
    sleep(500);
}
```

**额外的基线防护**：如果设备**本来就连着目标**，任何密码都会判成功。
所以开跑前先断开：

```java
if (connectedByLocalApi(target.ssid)) {
    l.onLog("当前已连着目标网络，先断开以便从干净状态开始。");
    disconnectTarget();
}
```

### 4.3 判定太快，正确密码被误杀

原实现 400ms 就判负。实测时序（真机）：

| 场景 | 时序 |
|---|---|
| 正确密码 | **T+1 秒** 连上 |
| 错误密码 | T+1~6 秒 `not connected`，**T+7 秒**回退到别的 AP |

**400ms 判负 = 正确密码连反应时间都没有。**

**修复**：最短等待 3.5 秒内**绝不判负**，最长等 10~15 秒。

### 4.4 候选根本没送进引擎

`dictPath` 为空时，服务端把候选列表当成空：

```java
if (task.dictPath != null && !task.dictPath.isEmpty()) {
    base = DictStore.load(...);
} else {
    base = new ArrayList<>();     // ← 内置生成器的候选在这里被丢掉
}
```

内置生成器只在内存里产生候选，从不落盘 → `dictPath` 是空串 →
服务端拿空列表 → 日志打「候选为空，跳过」→ 三种模式全都秒结束。

**修复**：入队前把生成的候选**落地成文件**，`dictPath` 永远有值。
另有兜底：`dictPath` 为空但 `dictSize > 0` 时用同样参数重建。

### 4.5 目录探测不完整

探测只验了"是目录"和"能写"，没验"能列目录"。
在无 root 机的 scoped storage 下会出现：

- `exists()` 返回 true → 内置字典被判定"已存在，跳过"
- `listFiles()` 被拒绝返回 null → 界面列表一个都没有

**表现**：「释放 0 个，跳过 8 个」+「目录里还没有字典」并存。

**修复**：探测加第三项 —— 必须 `listFiles()` 成功。

### 4.6 队列清空后进度条还挂着

删除所有任务后，顶部进度条没复位，看起来像"还在跑但不动了"。
**修复**：`renderQueue` 的空分支里一并复位进度条。

---

### 4.7 一条失败记录把整个应用锁死（1.9.28）

收尾恢复要求「回读确认连上目标」。可目标不在覆盖范围内时，这条判据**永远**
不成立 —— 一次普通的收尾被记成 `FAILED`，而当时的「还要不要动手」= 「阶段不是
RESTORED/KEPT」，`FAILED` 因此被算成未完成的待办；闸门又拿它挡掉**每一个**新任务。
杀后台没用（记录在磁盘上），每次冷启动还会再补做一次、再失败一次。

修的时候把两件事拆开：

| 层面 | 判据 | 落点 |
|---|---|---|
| 配置 | 原配置是否写回系统并被**独立回读**确认 | `writeBackConfirmed`，决定成败 |
| 连接 | 此刻是否连上、有没有 IP | `connObserved` / `ipObserved`，只是观察值 |

配套改了三处：记录按 `taskId` 分槽（新任务写自己的槽，不覆盖别人的）；
判据收窄为「同目标 + 未解决风险」；生命周期补上归档出口（重试间隔 10 分钟、上限 3 次）。

第二轮审计又补了两个真问题：`cmd wifi list-networks` 读失败与「列表里没有目标」
以前都表现为 `listPresent=false`，判定层于是把「未确认」当成「没有残留」、甚至当成
「无需还原」—— 现在单列 `UNVERIFIED`（不算成功、不算无残留、保留可重试）；
另外对已终态记录走 `restore()` 会命中「没有可执行步骤」分支而凭空报「已还原」并清掉
记录，现在返回 `NO_STEPS`（什么都不声明）。

## 5. 测试

**141 项，全部通过。**

| 套件 | 数量 | 覆盖 |
|---|---|---|
| SelfTest | 36 | SHA-1 / HMAC-SHA1 / PBKDF2（RFC 3174 / 2202 / 6070 向量） |
| PrivScanTest | 21 | `cmd wifi list-scan-results` 输出解析（中文 SSID、含空格、隐藏） |
| RuleTest | 25 | 规则字典解析与展开 |
| SortTest | 14 | 智能排序 |
| TargetDictTest | 17 | 目标派生 |
| E2ETest | 10 | 握手比对端到端 |
| PersistTest | 5 | 生成 → 落盘 → 读回一致性 |
| SpecifierTest | 13 | **判定语义**（超时/SSID 不匹配**绝不**判成功） |

`SpecifierTest` 是防回归的关键 —— 它把判定语义**写死成测试**：

```java
check("TIMEOUT **不**算成功（关键：超时不能判成功）", !isSuccess(TIMEOUT));
check("onAvailable 但 SSID 不匹配 → 不算成功", !isSuccess(ssidMismatchVerdict()));
check("无回调 → TIMEOUT（不是 SUCCESS）", noCallbackVerdict() == TIMEOUT);
```

---

## 6. 通道自检

用户反映「密码明明对却识别不到」。每次都靠猜，因为界面不提供任何证据。

现在 `CrackEngine.probeChannel()` 在跑之前**实际执行一遍判定链**并逐项报告：

```
── 通道自检 ──
  [1] ✓ WiFi 开关: 开
  [2] ✓ 通道类型: Shizuku
  [3] ✓ 命令返回看起来像 WiFi 输出（耗时 312ms）
  [4] ✓ WifiInfo 信号: B214
  [5] · cmd-status 信号: 当前没连网络，连上后才有
✓ 通道已接入
```

任何一项不过就停下并说明原因，**不再闷头跑完 97 条然后报"都没命中"**。

自检结果同时写进 `startup-last.txt`，也可以从设置页长按状态行查看。

---

## 7. 构建

不依赖 Gradle。两段式手工链 —— 因为 `aapt2` / `d8` 在设备侧是 arm64 原生二进制，
而 `javac` 只在桌面侧可用。

```sh
# 第一段（设备侧）：aapt2 compile + link → base.apk
sh 1-aapt2.sh

# 第二段（桌面侧）：javac → d8 → 组装 → 签名
export WP_FORGE_KS=/path/to/release.jks
export WP_FORGE_KS_PASS=...
export WP_FORGE_KS_ALIAS=...
sh 2-build-dex.sh
```

**依赖的三个库**（`app/libs/`）：

| 文件 | 来源 | 说明 |
|---|---|---|
| `shizuku-api.jar` | `dev.rikka.shizuku:api` | `rikka.shizuku.Shizuku` 等 15 类 |
| `shizuku-provider.jar` | `dev.rikka.shizuku:provider` | `ShizukuProvider`、`BinderContainer` |
| `shizuku-aidl.jar` | **`dev.rikka.shizuku:aidl`** | `moe.shizuku.server.*` 共 16 类 |

**第三个容易漏**。`Shizuku` 类的静态初始化里有：

```java
SHIZUKU_APPLICATION = new IShizukuApplication() { ... };
```

这个接口在 `aidl` 构件里。缺了它，`<clinit>` 直接抛 `NoClassDefFoundError`，
**每一次 `pingBinder()` 都在类加载阶段就失败**，被 catch 后返回 false ——
界面只能显示「服务未运行」，而 Shizuku 应用里开关明明是开的。

---

## 8. 已知限制

| 限制 | 性质 |
|---|---|
| 免 root 模式每条弹一次确认框 | Android 安全设计，应用无法关闭 |
| 免 root 模式比 root/Shizuku 慢 7~15 倍 | 系统排队 + 确认流程 |
| `IWifiManager` 反射在本机 Color OS 15 上未打通 | 待办，已用 `cmd wifi` + 删净校验兜底 |
| 一次只能连一个网络 | Android 限制，无法并行 |

前两条决定了免 root 模式的定位：**没有 root 也没有 Shizuku 时的兜底方案**，
不是主力。真要用请上 Shizuku（一次 ADB 激活，之后和 root 同速）。

---

## 9. 代码规模

```
Java      14,939 行 / 55 个文件
```

| 模块 | 职责 |
|---|---|
| `audit/CrackEngine` | **统一引擎**：通道抽象 + 多信号判定 + 生命周期 |
| `audit/RootProber` | shell 通道实现（`cmd wifi`） |
| `audit/ShizukuRunner` | Shizuku 集成（binder 监听、权限、exec） |
| `audit/SpecifierProber` | 免 root 精确连接 |
| `audit/DictGenerator` / `SmartSorter` / `TargetDictBuilder` | 候选生成与排序 |
| `audit/WpaCore` | 手写 SHA-1 / HMAC-SHA1 / PBKDF2 |
| `CrackService` | 前台服务、队列调度、通知 |
| `MainActivity` | 全部界面（代码构建，无 XML 布局） |

---

## 10. 许可

Apache-2.0。仅限自有网络或已获明确授权的网络使用。
