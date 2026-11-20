# AT3 证据链重新证明（2026-11-20，源码 + 实测）

## A. AOSP 源码证据（android13-release，packages/modules/Wifi）

文件：service/java/com/android/server/wifi/WifiShellCommand.java

### A1. 权限模型（第 423-431 行）

```java
if (ArrayUtils.indexOf(NON_PRIVILEGED_COMMANDS, cmd) == -1) {
    final int uid = Binder.getCallingUid();
    if (uid != Process.ROOT_UID) {
        throw new SecurityException("Uid " + uid + " does not have access to " + cmd + ...);
    }
}
```

NON_PRIVILEGED_COMMANDS 白名单**不含** `connect-network` / `add-network`，
但**包含** `forget-network` / `status` / `list-networks` / `list-scan-results`。

→ AOSP 语义：`connect-network` 需要 root UID；`forget-network` 不需要。

### A2. connect-network 的实现（第 863-884 行）

```java
case "connect-network": {
    CountDownLatch countDownLatch = new CountDownLatch(1);
    IActionListener.Stub actionListener = new IActionListener.Stub() {
        public void onSuccess() { pw.println("Connection initiated "); latch.countDown(); }
        public void onFailure(int i) { pw.println("Connection failed"); latch.countDown(); }
    };
    WifiConfiguration config = buildWifiConfiguration(pw);
    mWifiService.connect(config, -1, actionListener, SHELL_PACKAGE_NAME);
    countDownLatch.await(500, TimeUnit.MILLISECONDS);   // ← 只等 500ms
    setAutoJoin(pw, config.SSID, config.allowAutojoin);
    return 0;                                            // ← 恒定返回 0
}
```

**关键事实：**
1. `mWifiService.connect(...)` 是**异步**发起，`onSuccess` 只表示「Connection initiated」（请求已发起）
2. `await(500ms)` 超时后**静默继续**，不打印任何内容
3. `return 0` **与连接成败无关** —— 命令分发成功就返回 0
4. 因此 **`cmd wifi connect-network` 的返回码和输出都不能作为成功证据**

### A3. forget-network 的实现（第 907-923 行）

```java
case "forget-network": {
    String networkId = getNextArgRequired();
    ...
    public void onSuccess() { pw.println("Forget successful"); }
    public void onFailure(int i) { pw.println("Forget failed"); }
    mWifiService.forget(Integer.parseInt(networkId), actionListener);
    // 按 netId 删除（不是按 SSID）
}
```

**关键事实：**
- `forget-network <netId>` 按 **netId** 删除，不是按 SSID
- 因此同 SSID 多条配置（wpa2-psk + wpa3-sae 共用同一 netId）会被一起删掉

## B. 本机实测（PJX110 / Android 15 / ColorOS 15）

### B1. shell 身份（uid 2000 + u:r:shell:s0）执行 connect-network

```
$ su 2000 -c 'runcon u:r:shell:s0 sh -c "cmd wifi connect-network NOEXIST_TEST open"'
rc=0（无输出）

# 2 秒后查 list-networks：
102  NoSuchSSID_XYZ  open
102  NoSuchSSID_XYZ  owe^      ← 配置确实被创建了
```

**结论：本机 ColorOS 15 上，shell 身份执行 connect-network 是成功的** ——
与 AOSP 的「必须 root UID」不同，说明 ColorOS 放宽了这个检查
（或 Shizuku 提供的 shell 在 OEM 实现里被特殊处理）。

**这是 OEM 行为差异，不能推广到所有设备。**

### B2. forget-network 按 netId 删除（实测）

```
$ cmd wifi forget-network 102
Forget successful
# 之后 list-networks 里 NoSuchSSID_XYZ 消失（0 行）
```

**结论：净检（按 netId 删 + 回读确认）可靠。**

## C. 对 AT3 的影响（严格结论）

### C1. SuccessRule 的真实判据（生产代码）

```java
// SuccessRule.isSuccess —— 三条硬条件
targetSsid.equals(statusSsid)
&& "COMPLETED".equalsIgnoreCase(supplicantState)
&& ip 非空且非 0.0.0.0

// SuccessRule.assess —— 加一道冲突否决
if (statusNetId > 0 && targetNetId > 0 && statusNetId != targetNetId)
    → UNKNOWN（netid-conflict）
```

**netId 不是必要条件，只在「两侧都有效且不等」时否决。**

### C2. 归因依赖的两道前置闸门

1. `forgetTargetByShell(ssid)` + `netIdOfTarget(ssid) < 0` 回读确认
2. 命令前 `baselineDecision` + `disconnectAndVerify`

### C3. 剩下的归因缺口（必须承认）

**即使有上述闸门，「COMPLETED + IP」仍不能 100% 证明是本条候选的 PSK 完成的认证**，因为：

- **PMKSA 缓存**：WPA2 有 PMKSA 缓存，系统可能在不需要重新四次握手的情况下恢复连接
- **系统自动重连**：删除配置后，系统可能仍在重连周期内
- **`connect-network` 是异步的**：500ms 内没结果就返回，之后系统自己继续尝试

**因此 AT3 是「强证据」而不是「数学证明」。** 这一点必须在文档和 UI 里如实说明，
不能宣称「绝对证明」。

## D. 与 WiFi 工具箱的对比（证据分级）

| 项 | 证据等级 | 说明 |
|---|---|---|
| 执行 `cmd wifi connect-network <ssid> <sec> <pwd>` | **A（DEX 字符串）** | 仅见字符串常量 |
| 用 `logcat -s wpa_supplicant:D` 抓日志 | **A** | 仅见命令行字符串 |
| 靠 `WPA: RX message 1 of 4-Way Handshake from` 判定 | **A** | 仅见字符串 |
| 靠 `CTRL-EVENT-ASSOC-REJECT` 判定拒绝 | **A** | 仅见字符串 |
| 完整调用路径 | **未还原** | 未做反编译级追踪 |
| 生产代码实际使用 | **未证明** | — |
| 目标设备实测 | **未做** | — |

**不能把 A 写成 C 或 D。** WiFi 工具箱的 4-Way Handshake 判据比我们的 AT3 **更宽松**
（它只看日志文本，没有 netId 绑定与净检），因此**不采用**。

## E. 结论

| 问题 | 回答 | 证据等级 |
|---|---|---|
| connect-network 创建配置还是复用？ | **创建**（同名同安全类型会覆盖，netId 可能复用） | AOSP 源码 + 实测 |
| forget-network 能可靠删除？ | **能**（按 netId，同 SSID 多条一起删） | AOSP 源码 + 实测 |
| 删除会失败吗？ | 会（网络正连接时），代码已用 2 轮重试 + 回读确认 | 生产代码 |
| list-networks 能确认是新建的？ | **不能**确认「新建」，只能确认「存在且 netId 是多少」 | 实测 |
| netId 会复用吗？ | **会**（同 SSID 重连常复用旧 netId） | 实测观察 |
| status 四字段同源？ | **是**（同一行 WifiInfo） | 实测原始输出 |
| 系统自动连接能污染？ | 能，靠「删净 + 断开确认」两道闸门挡 | 生产代码 |
| 两次尝试间污染？ | 靠 attemptId 严格相等 + 每轮删净挡 | 生产代码 + 测试 |
| 同 SSID 多 BSSID？ | 靠 BSSID 参数 + netId 冲突检查挡 | 生产代码 |
| ROM 差异？ | **实测已见**（ColorOS 放宽了 connect-network 的 UID 检查） | 实测 |
