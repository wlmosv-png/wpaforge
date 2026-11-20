# Android 公共 Wi-Fi API 可行性报告

**日期**：2026-11-20
**分支**：`feature/rootless-adaptation`
**设备实测来源**：OPPO PDRM00 / Android 13 (API 33) / Shizuku shell uid=2000
**本轮目标**：确认 Android 公共 Wi-Fi API 能否替代被系统拒绝的 `cmd wifi connect-network`

---

## 一、结论摘要（先看这个）

| 问题 | 结论 |
|---|---|
| 公共 API 能否完成**一次**真实连接？ | **能**（需系统确认框 + 用户点允许） |
| 能否支持**自动逐条**破解？ | **不能**。每条候选都要用户点一次确认框 |
| 能否拿到「本次口令完成认证」的证据？ | **不能到 DIRECT 级别**。最高只能到「本次新建连接 + 目标 SSID + 有效地址」 |
| 能否判「这条口令错了」？ | **不能**。`onUnavailable` 把口令错/AP 不在/用户拒绝/超时全混在一起 |
| 能否写「已试密码」集合？ | **不能**。公开 API 证明不了哪条口令是错的 |

**一句话**：公共 API 是**可行的单次验证工具**，不是**可用的自动破解引擎**。把它包装成「完整无 Root 破解能力」是撒谎。

---

## 二、Shizuku shell 路线为什么死

### 2.1 真机证据（用户提供日志，2026-11-20 22:37:24）

```
[Shizuku/shell(uid=2000) via Shizuku · u:r:shell:s0] 110ms exit=255
  timeout=false out=0B err=809B binder=6ms wait=102ms drain=0ms
  failure=NONZERO_EXIT
  stderr=⏎ Exception occurred while executing 'connect-network': ⏎
    java.lang.SecurityException: Uid 2000 does not have access to
    connect-network wifi command (or such command doesn't exist) ⏎
    at com.android.server.wifi.WifiShellCommand.onCommand(WifiShellCommand.java:427)
```

### 2.2 AOSP 源码对照（`doc/ref-WifiShellCommand-android13.java:423-430`）

```java
if (ArrayUtils.indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
    final int uid = Binder.getCallingUid();
    if (uid != Process.ROOT_UID) {
        throw new SecurityException(
            "Uid " + uid + " does not have access to " + cmd + " wifi command ...");
    }
}
```

`connect-network` **不在** `NON_PRIVILEGED_COMMANDS` 白名单里 → 要求 `uid == 0`。

### 2.3 该设备各子命令的真实状态

| 子命令 | 是否在白名单 | shell(2000) 实测 |
|---|---|---|
| `status` | ✅ 是 | ✅ 可用（108ms） |
| `list-networks` | ✅ 是 | ✅ 可用（106ms） |
| `forget-network` | ✅ 是 | ✅ 可用 |
| **`connect-network`** | ❌ **否** | ❌ **SecurityException** |

**结论**：shell 身份能做只读诊断，**不能做在线认证**。这不是应用 bug，是 Android 权限模型。

### 2.4 我在 OnePlus A15 上的对照（不作为你的设备结论）

同一份代码在我的机器上 `connect-network` 能通过权限校验（返回参数错误而非 SecurityException），说明**OEM 有差异**。所以能力必须**逐设备实测**，不能拿一台的结果套另一台。

---

## 三、公共 API 能做什么

### 3.1 调用链

```java
WifiNetworkSpecifier.Builder b = new WifiNetworkSpecifier.Builder();
b.setSsid("TARGET");
b.setWpa2Passphrase("password");        // 或 setWpa3Passphrase

NetworkRequest req = new NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(b.build())
        .build();

cm.requestNetwork(req, callback, timeoutMs);
```

### 3.2 回调顺序（真实观测）

```
requestNetwork()
     │
     ├─ [系统确认框：连接到该网络？ 允许 / 拒绝]
     │
     ├─ onAvailable(Network)
     ├─ onCapabilitiesChanged(Network, NetworkCapabilities)   ← 可能多次
     │      └─ nc.getTransportInfo() → WifiInfo（SSID / BSSID）
     ├─ onLinkPropertiesChanged(Network, LinkProperties)
     │      └─ lp.getLinkAddresses() → 是否有有效 IP
     └─ onLost(Network)
```

或

```
     └─ onUnavailable()      ← 超时 / 没找到 / 用户拒绝，三者不可区分
```

### 3.3 七态判定模型（`cap/PublicWifiVerdict.java`）

| 状态 | 含义 | 允许 DIRECT |
|---|---|---|
| `REQUEST_ACCEPTED` | requestNetwork 没抛异常 | ❌ |
| `USER_APPROVED` | 用户点了允许（由后续可用网络间接推断） | ❌ |
| `NETWORK_AVAILABLE` | onAvailable 触发 | ❌ |
| `NETWORK_UNAVAILABLE` | onUnavailable 触发 | ❌ |
| **`AUTHENTICATION_CONFIRMED`** | 本次新建连接 + 目标 SSID + 有效 IP，且排除旧网络 | ✅ **唯一** |
| `AUTHENTICATION_REJECTED` | 能证明本条口令被拒 | ❌（**现实不可达**） |
| `UNKNOWN` | 证据不足 | ❌ |

### 3.4 为什么 `AUTHENTICATION_REJECTED` 现实不可达

`onUnavailable` 把四种情况混在一起，AOSP 没有向应用暴露区分依据：

1. 口令错误
2. AP 不存在 / 信号太差
3. 用户在确认框点了拒绝
4. 请求超时

所以**永远不能**用它判「这条口令错了」。否则会写进已试集合，以后有 root 时跳过一条根本没被证明错误的口令。

---

## 四、能做什么 / 不能做什么（诚实清单）

### ✅ 能做

| 能力 | 说明 |
|---|---|
| 单次凭据验证 | 输入一条口令，系统真去关联，返回是否连上 |
| 目标锁定 | `setSsid` + 可选 `setBssid`，把目标 AP 固定 |
| 连接级证据 | `getTransportInfo()` 拿 SSID/BSSID；`LinkProperties` 拿 IP |
| 旧连接排除 | 请求前快照已有 WiFi 网络，`onAvailable` 时比对 handle |
| 资源释放 | `unregisterNetworkCallback`（已验证调用） |
| 用户可见 | 系统确认框正常弹出，不绕过 |

### ❌ 不能做

| 限制 | 原因 |
|---|---|
| **自动逐条破解** | 每条候选都要用户点一次确认框。100 条 = 点 100 次 |
| **判「口令错误」** | `onUnavailable` 语义不区分口令错与其它失败 |
| **DIRECT 级成功证据** | 拿不到「这条 PSK 完成握手」的事实（PMKSA 缓存可复用会话密钥） |
| **净检（删旧配置）** | 公开 API 无法删除系统已保存的配置 |
| **读 supplicant 日志** | 应用 UID 读不到 `wpa_supplicant` tag |
| **后台静默运行** | 确认框需要前台 Activity |

---

## 五、对现有判定链的影响

### 5.1 不受影响（保持原样）

| 组件 | 状态 |
|---|---|
| `EvidenceGrade` 的 DIRECT / INFERRED / NONE 三级 | 不变 |
| `Adjudicator` 的规则表 | 不变 |
| `AttributionPrecondition`（AT1/AT2/AT3） | 不变 |
| Root 链的 `SuccessRule` 四条件同行 | 不变 |
| `TriedSet` 写入条件（只 DIRECT） | 不变 |

### 5.2 新增（不改动旧的）

| 组件 | 作用 |
|---|---|
| `cap/PublicWifiVerdict` | 七态模型，`allowsDirect()` 只有 CONFIRMED 为真 |
| `cap/PublicWifiProbe` | 一次一条的验证器，输出完整证据链 |
| `cap/BackendCapability` | 三后端独立探测，互不背书 |
| `PublicApiProbeActivity` | 独立验证界面（`exported=false`） |

### 5.3 关键约束（已写成门禁断言）

```java
// PublicWifiVerdict
public static boolean allowsTriedSet(State s) {
    return false;      // 公开 API 下恒为 false
}
```

---

## 六、执行器拆分（任务书第 4 点）

| 后端 | 只读诊断 | 在线认证 | 净检 | 归因 |
|---|---|---|---|---|
| `ROOT_EXECUTOR` | ✅ | ✅（uid=0） | ✅ | ✅ |
| `SHIZUKU_EXECUTOR`（shell 身份） | ✅ | ❌（connect-network 被拒） | ✅ | ❌ |
| `ANDROID_PUBLIC_WIFI_API` | ✅ | ⚠️ 单次（需确认） | ❌ | ⚠️ 连接级 |

**任何一个后端的能力结论不得用于另一个。**

### 6.1 本轮修复的闸门缺陷

**改造前**：`probeChannel` 只看 `cmd wifi status` 是否可读，就判「通道已接入 ✓」。`connect-network` 的权限从未被检查 → 任务一路跑到消耗 3 条候选才由熔断器停下。

**现在**：`connect-network` 探测到 `SecurityException` → 新增 `CONNECT_DENIED` 结论 → **在候选循环开始前判死**，一条候选都不消耗，并给出替代路径。

真机日志对应的修复点：
```
[22:37:24] · connect-network 子命令: 未知（问不到）
[22:37:24] · connect-network 探测没拿到判定性输出，按「未知」放行
[22:37:24] ✓ 通道已接入          ← 这就是缺陷
```
现在同样场景会输出：
```
✗ connect-network 权限被拒（uid=2000 命令存在但无权限执行）
  → 该系统要求 uid=0 才能执行 connect-network；当前执行身份是 shell，做不到在线认证。
  → 只读诊断（status / list-networks）仍可用。
  → 可行替代：① 让 Shizuku 以 root 身份启动服务；② 或改用 Android 公开 Wi-Fi API
一条候选都不会消耗。
```

---

## 七、真机验证步骤（待用户执行）

### 7.1 只读验证（不改网络）

启动自检日志里应该看到：

```
-- 三后端能力 --
Root: 只读=✗ 认证=✗ 净检=✗ 归因=✗ — su 不可用（未 root 或未授权本应用）
Shizuku: 只读=✓ 认证=✗ 净检=✗ 归因=✗ — connect-network 被系统拒绝（Uid 2000 ...）
公开 API: 只读=✓ 认证=✓ 净检=✗ 归因=✓ — 公开 API 无法删除已保存配置
```

**预期**：`Shizuku` 的 `认证=✗`，`公开 API` 的 `认证=✓`。

### 7.2 公共 API 单次验证（会改网络，需你确认）

1. 装新 APK
2. 打开「公开 API 验证」页面
3. 输入你自己控制的测试热点 SSID + **已知正确**的口令
4. 点「验证一次」
5. 系统弹确认框 → 点**允许**
6. 把结果页完整截图或复制发我

**正确口令预期**：
```
state = AUTHENTICATION_CONFIRMED (auth-confirmed)
allows DIRECT: true
order = requested>available>capabilities>linkprops
hasTransportInfo = true
transportSsid = <你的SSID>（与目标一致: true）
hasValidAddress = true (192.168.x.x/24)
```

**错误口令预期**：
```
state = NETWORK_UNAVAILABLE (network-unavailable)
allows DIRECT: false
order = requested>unavailable
```
**注意**：这**不会**被判成「口令错误」，只会是「未能建立连接」。

7. 再测一次**错误口令**，确认不会出现 `auth-confirmed`

---

## 八、仍未验证的事项

| 项 | 状态 |
|---|---|
| OPPO A13 上 `WifiNetworkSpecifier` 是否真能建立连接 | **未验证**（需真机执行第 7.2 步） |
| 系统确认框的实际行为（是否每次都弹） | **未验证** |
| `getTransportInfo()` 在 ColorOS 上是否返回正确 SSID | **未验证** |
| 该设备是否支持 `setBssid` 锁定 | **未验证** |
| 一次请求的实际耗时 | **未验证** |

**这些都必须由你在真机上确认，我不会用 Mock 测试冒充真机结论。**
