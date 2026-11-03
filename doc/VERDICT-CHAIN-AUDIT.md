# 判定链审计报告

**基线**：`8df845e`（1.9.32/128 + P0-3）
**方式**：两路并行只读审计 + 主审逐条核实关键行
**本轮未改任何生产代码**

---

## A. 完整判定链路图

```
候选密码
  │
  ├─[前置闸门①] forgetTargetByShell           CrackEngine:718
  │     └─ 失败 → UNKNOWN (:1075)
  ├─[净检] netIdOfTarget 回读                  :1091
  │     └─ 仍有残留 → UNKNOWN (:1093)
  ├─[前置闸门②] 若连着目标 → disconnectAndVerify  :790
  │     └─ 失败 → UNKNOWN (:1130)
  ├─[窗口] AttemptMarker.writeCommand          :1157
  ├─ commandAt = now                           :1163
  ├─ authWatch.clear()                         :1174
  ├─ deadline = commandAt + timeoutMs          :1207
  ├─ 发 connect-network（独立线程，fire-and-forget，无返回值校验） :1192
  │
  └─ while(now < deadline):
       ├─ 读 logcat → wlogSlice（标记后切片）  :1355
       ├─ 读 cmd wifi status → SupplicantParser.parse（只取第一条 WifiInfo） :1400
       │
       ├─【SUCCESS-1】SuccessRule.assess → SUCCESS          :1473
       │     SSID相等 + COMPLETED + IP有效 + netId无冲突
       │     归因 100% 依赖闸门①②
       │
       ├─【SUCCESS-2】wpalog: tried && sawNegotiatedFor      :1574
       │     切片里有目标associate + 目标BSSID的negotiated/CONNECTED
       │
       ├─【FAIL-1】failover: 系统连上别的网                   :1512  [INFERRED]
       ├─【FAIL-2】authcast: 认证失败广播                     :1616  [DIRECT/INFERRED]
       ├─【FAIL-3】m1retry: M1重发≥2 + 120ms复核              :1680  [INFERRED]
       ├─【FAIL-4】wpalog: TEMP-DISABLED / HandshakeFailed    :1716  [DIRECT]
       │
       └─ 到期 → classifyDeadline → ERROR / UNKNOWN / TIMEOUT  :493
             （已无 AUTH_FAIL 出口）

  → CrackService.onAttemptResult
       ├─ persistable(DIRECT) → triedSet.add（永久）        CrackService:1083
       ├─ conclusive(INFERRED) → inferredThisRun（内存）     :1088
       └─ VerdictTally.add(verdict)  ← **只看 verdict，不看 grade**  :1087

  → 收尾 finishTask → DONE_HIT / DONE_MISS / PAUSED / STOPPED
```

---

## B. 判定入口清单

### SUCCESS（2 处，全部经核实）

| # | 位置 | 判据 | 证据强度 | 归属来源 |
|---|---|---|---|---|
| S1 | `CrackEngine:1473` | SSID + COMPLETED + IP | **协议层事实** | 依赖闸门①② |
| S2 | `CrackEngine:1574` | 目标 associate + 目标 BSSID 协商完成 | **协议层事实** | 仅 BSSID 匹配 |

另有 **WPS 独立成功判定**（不产生 Verdict）：`WpsHelper:856`，判据 `curId != baseId && COMPLETED && key_mgmt != WPS`。

### AUTH_FAIL（4 处，全部经核实）

| # | 位置 | confirmedBy | grade | 实际强度 |
|---|---|---|---|---|
| F1 | `:1512` | failover | INFERRED | 纯推断（别人连上了 ≠ 目标失败） |
| F2 | `:1616` | authcast | DIRECT/INFERRED | 取决于广播时能否读到 SSID |
| F3 | `:1680` | m1retry | INFERRED | 纯推断（丢包/AP慢/残留都能造成） |
| F4 | `:1716` | wpalog | **写死 DIRECT** | 前一支可归因，后一支**不可** |

### 其他

- `UNKNOWN`：`:1076/:1093/:1130/:1461` + `classifyDeadline` 四档
- `ERROR`：`:1019/:1066` + `classifyDeadline` not-attempted
- `TIMEOUT`：`classifyDeadline` 最后一档
- `ABORTED`：`:1306`

---

## C. 证据等级的实际使用情况

| 位置 | 用法 | 评价 |
|---|---|---|
| `Attempt` 构造 → `defaultOf(v)` | SUCCESS→DIRECT，AUTH_FAIL→INFERRED | ✅ 保守 |
| `:1517` failover → INFERRED | 显式 | ✅ |
| `:1621` authcast → DIRECT/INFERRED | 按 SSID 匹配 | ⚠️ SSID 取值时机不可靠 |
| `:1685` m1retry → INFERRED | 显式 | ✅ |
| `:1720` wpalog → **DIRECT** | 写死 | ❌ 后一支无归属却同样 DIRECT |
| `CrackService:1083` persistable | triedSet | ✅ 唯一持久化入口 |
| `CrackService:1088` conclusive | inferredThisRun | ✅ |
| `CrackEngine:2090` conclusive | 推进 cursor | ⚠️ **INFERRED 也推进**（本轮内不可挽回） |
| `VerdictTally.add(verdict)` | 统计「已证实失败」 | ❌ **不看 grade**，INFERRED 也算「已证实」 |

**结论**：等级在「是否永久记账」上贯彻了，但在 **UI 统计** 与 **游标推进** 上没有贯彻。

---

## D. 已确认缺陷

### D1【严重】闸门②在 status 读不到时静默跳过 — 假阳性主通道

`CrackEngine:1120-1135` + `currentSsid():605-643`

status 超时/空 → shell 分支跳过 → `wifi.getConnectionInfo()` 缺定位权限返回 `<unknown ssid>` → **null** → `linkedBySsid=false`；若 netId 也读不到 → **`if` 整块跳过，闸门②从未执行**。

→ 系统仍连着目标时，命令后四条件齐全 → **SUCCESS**，记到任意候选头上。

**这就是「12345678 误报成功」最可能的机制。**

### D2【严重】wpalog SUCCESS 只按 BSSID 匹配 — 旧配置回连即命中

`CrackEngine:1574` + `WpaLogReader:502`

`tried`（见过目标 associate）与 `sawNegotiatedFor`（目标 BSSID 协商完成）**无时序绑定、无 netId 绑定**。系统用旧配置/PMKSA 回连同一 AP → 两条件同时满足 → SUCCESS。

且 `CTRL-EVENT-CONNECTED` 被算作成功证据（`isSuccessEvidenceKind`），**绕过 4-Way 握手语义**；该行已解析出 `[id=N]` 却未被使用。

### D3【严重】WPS 成功判定 baseId 读失败即放行

`WpsHelper:788` + `:856`

`baseId = parseInt(field(before,"id"), -1)`，读失败为 **-1**；判定 `curId != baseId` → **任何 curId 都满足** → 误报 SUCCESS。

且 `hitConfirm`（600ms 二次确认）**已采集但不参与判定**，只写文件。

### D4【高】m1retry 120ms 复核远短于真机握手时间

`:1670-1685`。真机正确密码 670~950ms，慢 AP 5~9s。120ms 内成功判据只跑 1~2 轮 → 正确密码被判 AUTH_FAIL。

后果：grade=INFERRED 不永久化 ✅，但 `conclusive(INFERRED)=true` → **游标推进**，本轮内该候选被越过。

### D5【高】wpalog 的 `tried && sawHandshakeFailure` 支无归属却判 DIRECT

`:1709-1710`。`sawHandshakeFailure`/`sawAssocReject` 不带 SSID/BSSID，只靠 `tried` 兜底 → 别的网络的握手失败被算到本条 → **永久进 triedSet**。

### D6【高】`sawWrongKeyForAttempt` 不校验时序

`WpaLogReader:458-487`。四个布尔量分别置位，**不检查先后**。旧配置产生的失败行只要出现在同一切片即成立。

`auth_failures=N` 已解析（`LogEvent.authFailures`）但**判定中未使用**。

### D7【中】netId 冲突检测的两个后门

`SuccessRule:94`：`bothValid = statusNetId > 0 && targetNetId > 0`

- `targetNetId == -1`（命令后未建出配置）→ 放行
- `statusNetId == 0`（AOSP 第一条配置的合法值）→ 放行

### D8【中】failover 无「目标失败迹象」要求

`FailoverRule:66-83` 七条前提全在证明「别人连上了」，**没有一条证明目标失败**。`leftBaseline` 在开机未连网时首轮即为真。

### D9【中】「已证实失败 N 条」口径失真

`VerdictTally:39-46` 只看 verdict → INFERRED 的 AUTH_FAIL 也被计入「已证实失败」，UI 文案虚高。

### D10【中】authcast 的 SSID 取值时机

`WifiAuthFailureWatcher:78-86` 用「广播到达那一刻的 getConnectionInfo()」，认证失败瞬间通常已 `<unknown ssid>` → `ssidOwnedByTarget` 返回 false → **该失败信号被完全丢弃**；即使读到也可能是旧连接的 SSID → 误升 DIRECT。

> 注：主审已核实 `authWatch` 在 `CrackEngine:257` 生产构造中赋值，**不是死路径**（子审计报告的该结论有误，已排除）。

---

## E. 真实反例与复现步骤

| # | 对应缺陷 | 构造 | 期望 | 当前 |
|---|---|---|---|---|
| E1 | D1 | status 返回空串 + wifi 为 null + 目标已连接 | 应拒绝判定 | **SUCCESS** |
| E2 | D2 | 切片含 `Trying to associate 'T'` + `Key negotiation completed with <T的BSSID>`（来自旧配置） | 应不判成功 | **SUCCESS** |
| E3 | D2 | 切片只含 `CTRL-EVENT-CONNECTED [id=9]`，无任何 4-Way 行 | 应无握手证据 | **SUCCESS** |
| E4 | D3 | WPS：首个 STATUS 返回空，随后 `id=3 COMPLETED WPA2-PSK` | 应不可归因 | **SUCCESS** |
| E5 | D4 | 切片 M1×2，间隔 110ms；配置已建 | 应继续观察 | **AUTH_FAIL** |
| E6 | D5 | 目标 associate + 别的网络的 `4-Way Handshake failed` | 应不归因 | **AUTH_FAIL DIRECT** |
| E7 | D6 | associate → failed → TEMP-DISABLED（后两行属旧配置） | 应校验时序 | **AUTH_FAIL DIRECT** |
| E8 | D7 | `assess(T,T,"COMPLETED","192.168.1.2", 0, 7)` | 应 UNKNOWN | **SUCCESS** |
| E9 | D8 | 目标弱信号，2.6s 时系统回连另一已保存网络 | 应继续等 | **AUTH_FAIL** |
| E10 | D9 | 4 条 m1retry，0 条 DIRECT | 应报「4 条推断」 | **「已证实失败 4 条」** |

---

## F. 缺陷依赖与修复顺序

```
D1/D2/D3（假阳性：判成成功）
   ↓ 一旦发生
Verdict.SUCCESS → grade=DIRECT → triedSet 永久写入 → KEEP_HIT_CONFIG 保留错误口令
   ↓ 放大器
错误口令成为系统配置 + 后续永远跳过

D4/D5/D6（假阴性：判成失败）
   ↓
AUTH_FAIL → D5 走 DIRECT → triedSet 永久写入 → 正确密码被永久跳过
D4 走 INFERRED → 不永久化，但 cursor 推进 → 本轮内不可挽回
```

**修复优先级**（按「错误结论被永久化」的风险）：

1. **D5 + D3 + D1** — 这三条会把错误结论写进 **triedSet / 系统配置**，是唯一不可逆的。
2. **D2** — 假阳性但可通过「要求 netId 或 4-Way 进程」收窄。
3. **D4** — 假阴性但本轮不可挽回（cursor）。
4. **D7/D8/D9/D10** — 口径与后门，不直接造成永久错误。

**依赖关系**：
- D9 依赖 grade 贯穿到 tally（不改 D9 就修不了「已证实失败」的口径）
- D4 的根治依赖「120ms 复核」被替换为「等到 deadline 或出现成功证据」——与 D2 的收窄共用同一处代码，**必须一起改**，否则改一个另一个会恶化。

---

## G. 每个缺陷的测试方案

| 缺陷 | 测试名 | 类型 | 断言 |
|---|---|---|---|
| D1 | `Gate2SilentSkipTest` | 纯逻辑（注入 ShellChannel） | 记录 `disconnectAndVerify` 调用次数，应为 1，实际 0 |
| D2 | `WpaLogBssidOnlyTest` | 纯逻辑（构造切片） | 仅 BSSID 匹配、无 netId 绑定 → 不应 SUCCESS |
| D3 | `WpsBaseIdTest` | 纯逻辑（假 STATUS） | baseId 读失败时不得判 SUCCESS |
| D4 | `M1RetryWindowTest` | 纯逻辑（假时钟推进 110ms） | 不得在成功证据可能到达前结案 |
| D5 | `WrongKeyAttributionStrictTest` | 纯逻辑 | 无归属的 HandshakeFailure 不得升 DIRECT |
| D6 | `WrongKeyOrderingTest` | 纯逻辑 | 失败行必须晚于本次 associate |
| D7 | `NetIdBackdoorTest` | 纯逻辑（SuccessRule.assess） | netId 为 0 / -1 时不得静默放行 |
| D8 | `FailoverNeedsTargetEvidenceTest` | 纯逻辑 | 无目标失败迹象时不得判 AUTH_FAIL |
| D9 | `TallyGradeSplitTest` | 纯逻辑 | INFERRED 不得计入「已证实失败」 |
| D10 | `AuthcastSsidTimingTest` | 纯逻辑 | 广播时刻读不到 SSID 不得丢弃信号/不得误升 DIRECT |

**所有测试真实调用生产类**（`CrackEngine` / `SuccessRule` / `WpaLogReader` / `WpsHelper` / `VerdictTally` / `FailoverRule`），不复制逻辑。

---

## 未证实 / 缺失证据

| 项 | 说明 |
|---|---|
| `cmd wifi forget-network` 后系统用 PMKSA 回连的时延 | 决定 D2 的现实触发率 |
| ColorOS 15 上 `cmd wifi status` 打印几条 `WifiInfo` | 决定 SupplicantParser 取第一条是否正确 |
| `CTRL-EVENT-SSID-TEMP-DISABLED` 是否必带 `reason=WRONG_KEY` | 代码未校验 reason |
| authcast 在真机是否真的被触发过 | `errorCount()>0` 无实测样本 |
| WPS `hitConfirm` 是否被人工核对过 | 只写 `/data/local/tmp/wpaforge-hit.txt` |

---

## 明确可验证 vs 只能推断

**可直接验证（纯逻辑/注入）**：D3、D4、D5、D6、D7、D8、D9、D10
**需要真机或真实日志样本**：D1（status 超时概率）、D2（PMKSA 回连时延）
