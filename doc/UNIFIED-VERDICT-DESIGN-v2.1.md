# WPAForge 统一裁决核心 —— v2.1 修订（P0-A..D + 迁移契约）

> 状态：**方案稿 v2.1**（待审）。本轮**不改生产代码**。
> 取代 v2 的：归属契约、SUCCESS 证明、时间体系、AP-3 分级、下游清单。

---

## P0-A Attempt 事件归属契约

### A.1 生产时间线（已核实源码）

```
T0  Attempt 创建           = 命令发出前                    [单调 clock()]
T1  请求提交               = shell.exec(connect-network)   [单调]
T2  事件发生               = logcat 行时间戳               [见 A.2]
T3  事件采集               = WpaLogReader.readEx 返回时刻   [单调]
T4  观察期限结束           = deadline                      [单调]
T5  底层生命周期终止       = ConnectionActivity.phase==TERMINATED（**独立事件，非 T4**）
```

### A.2 时间源修正（关键，已实测）

**现状**：`logcat -d -s wpa_supplicant -s WPF_PROBE | grep ...` —— **无 `-v`** → 输出**壁钟**
（`MM-DD HH:MM:SS.mmm`），与单调 `clock()` **不同源、不可直接比较**。

**实测结论（本机 Android 15）**：
```
$ logcat -d -v monotonic -t 3
143167.815 31013 31013 I AndroidRuntime: ...
$ cat /proc/uptime
174854.81
```
→ **`logcat -v monotonic` 直接输出 CLOCK_MONOTONIC（秒.毫秒）**，与 `SystemClock.uptimeMillis()` **同源**。

**修正方案**：
> **改用 `logcat -d -v monotonic`**；事件时间 = 单调时间戳，**可直接与 `clock()` 比较**。
> **ClockBridge 降级为"格式适配"**（`<秒>.<毫秒>` → ms），**不再需要壁钟换算**。
> 时间锚点 `-t` 改为传**单调值**（或改用 marker 下界，见 A.4）。

### A.3 每条证据的四个时间字段（新）

| 字段 | 定义 | 可信度 |
|---|---|---|
| `occurredMonotonicMs` | 来自 `logcat -v monotonic` | **高**（同源） |
| `observedMonotonicMs` | 采集到该行的 `clock()` | 高 |
| `ingestSeq` | 入库序号（单调递增） | 高 |
| `epochWallMs` | 壁钟（仅用于显示） | 低（不参与归属） |

**归属只用单调字段**（A.4）。

### A.4 无 Attempt ID 的 Android 事件如何关联

**系统事件不携带 WPAForge 的 attemptId**（除应用自己写的 marker 行）。关联规则：

```
归属判定（按序）：
  R1 事件 occurredMonotonic ∈ [T0, T5]？   否 → 丢弃（不可归属）
  R2 该区间内有且仅有一个 Attempt？      是 → 归它
  R3 区间重叠 → 用 netId（若事件带）区分；netId 也不足以区分 → **未归属（不分配）**
  R4 marker 行存在 → 以 marker 为下界辅助（但不是充分条件）
```

### A.5 netId 缺失/复用/相同/跨 Attempt 延迟

| 情况 | 处理 |
|---|---|
| 缺失 | 归属最高 AT-2（**禁止 AT-3**） |
| 复用（与 preNetId 相同） | 视为**旧连接**，不改 AT |
| 相同（A/B 都缺或同值） | **无法区分 → 事件保持未归属** |
| 跨 Attempt 延迟 | 按 `occurredMonotonic` 划归**发生时刻**所属 Attempt；落不进任何区间 → 丢弃 |

### A.6 多个 Attempt 区间重叠

```
若 Attempt 有重叠（正常不应发生，但 INCOMPLETE + 新 Attempt 可能）：
  · 先按"请求是否仍在活动"区隔（LC-ACTIVE 的 Attempt 独占其区间）
  · 仍不唯一 → 事件标 UNATTRIBUTED，**不参与任何裁决**
```

### A.7 INCOMPLETE 后的延迟 SUCCESS

```
INCOMPLETE 是【快照】非【冻结】：
  · 原 Attempt 的 id 与其资源标记保留（供重新裁决）
  · 延迟 SUCCESS 到达 → 按 occurredMonotonic 找回原 Attempt → 重新裁决
  · 重新裁决【幂等】：同一 (attemptId, evidenceId) 只入库一次；
    持久化写入以 attemptId 去重（防重复写 triedSet / 重复推 cursor）
  · 绝不把延迟 SUCCESS 记到"新 Attempt"头上
```

### A.8 P0-A 测试（6 项，全部经生产入口）

| # | 场景 | 期望 |
|---|---|---|
| A-1 | A 超时后 B 开始，A 的 SUCCESS 迟到 | 归 A，不污染 B |
| A-2 | A、B netId 相同 | 事件未归属（不误判） |
| A-3 | A、B netId 均缺失 | 事件未归属 |
| A-4 | A、B 事件时间区间重叠 | 未归属 |
| A-5 | A 的旧失败迟到，B 进行中 | 不污染 B |
| A-6 | A 的延迟 SUCCESS 重新裁决 | 不重复写持久化（幂等） |

---

## P0-B SUCCESS 证明（netId 复用验证）

### B.1 netId 是否可复用？—— 可以

系统在**删除配置**后**回收** netId；同一值可被后续配置复用。因此
> **`netId != preNetId` 不足以证明"本次新建连接"**（可能只是回收后重分配）。

### B.2 SP-0..SP-3 的真实生产条件 + 反例 + 降级

| 等级 | 证据条件 | 反例 | 降级 |
|---|---|---|---|
| `SP-0` | status SSID == 目标 | 设备原本已连该 SSID | 不判 SUCCESS |
| `SP-1` | SP-0 + COMPLETED + IP | 旧连接仍在（未断开成功） | 不判 SUCCESS |
| `SP-2` | SP-1 + netId 属本次（!= preNetId） | **netId 复用**导致误配 | 见 B.3 |
| `SP-3` | SP-2 + **前置闸门均成功**（forgetOk + disconnectOk）+ **命令前后 SSID 变化** | — | — |

### B.3 逐项覆盖（用户点名）

| 场景 | 处理 |
|---|---|
| 删除配置后重建 | netId 可能复用 → 需 + 前置闸门 + BSSID |
| 同配置多次连接 | 每次是新 Attempt；用 occurredMonotonic 区隔 |
| 断开后重连 | 同 SSID，netId 可能同 → **不得靠 netId 单独判定** |
| 同 SSID 多配置 | SSID 归属不可靠 → AT ≤2 |
| 同 BSSID 不同 Attempt | 单调时间窗区隔 |
| 命令前已连目标 | preNetId 记录；成功需 `netId != preNetId` **且** 闸门成功 |
| 删除配置失败 | `forgetOk=false` → SP ≤1 → SUCCESS 降 **UNKNOWN** |
| 断开失败 | `disconnectOk=false` → 同上 |
| netId 不可得 | 无 SP-2 → 最高 SP-1 → **UNKNOWN** |
| **漫游/网络切换** | 系统可能自行切换 → 需 LC-ACTIVE 检查；不满足 → UNKNOWN |

### B.4 明确结论

> **netId 变化不得单独视为 Attempt 唯一标识。**
> **SP-3 需要"三要素同时成立"**：① netId 属本次（尽力）② 前置闸门成功 ③ 单调时间窗内。
> **缺任一 → SUCCESS 降 UNKNOWN**（残余风险：真机 netId 常缺失 → 命中可能降级）。

---

## P0-C ClockBridge 修正（已被 A.2 简化）

| 问题 | v2 方案 | v2.1 修正 |
|---|---|---|
| 壁钟↔单调 | ClockBridge 换算 | **改用 `-v monotonic`** → 无需换算 |
| 锚点采集 | 壁钟字符串 | 单调值 / marker 下界 |
| 误差 | 批次内壁钟差 | **仅格式解析误差（0）** |
| 校时/跨日/重启 | 需处理 | **单调源天然免疫**（重启后回到 0 → 需记录 bootId） |
| 区间重叠 | 未定义 | A.6 规则 |
| 不确定时间 | 未定义 | 事件标 UNATTRIBUTED |

**唯一新增要求**：记录 `bootId`（`/proc/sys/kernel/random/boot_id` 或 `SystemClock.elapsedRealtime()` 基数），
**跨重启的事件不得与重启前的 Attempt 关联**。

---

## P0-D AP-3 生产可用性（逐项）

| 项 | 内容 |
|---|---|
| **原始事件** | `CTRL-EVENT-SSID-TEMP-DISABLED id=N ssid="X" auth_failures=k duration=D reason=WRONG_KEY` |
| **生产采集位置** | `WpaLogReader.readEx`（logcat）→ `parse` → `P_WRONG_KEY`（`:210`）→ `KIND_WRONG_KEY`（`:189`, `:332`） |
| **解析方法** | 正则 `CTRL-EVENT-SSID-TEMP-DISABLED[^\n]*ssid="([^"]+)"` + `reason=WRONG_KEY` |
| **安全模式适用范围** | **仅 WPA/WPA2-PSK**；**SAE/WPA3 不产生**（wpa_supplicant `could_be_psk_mismatch` 显式排除） |
| **真实限制** | ① 需 ColorOS 输出该行（日志配额可能丢）② 需 `reason=WRONG_KEY`（`AUTH_FAILED` 是 802.1X，不是密码错） |

### AP-3 是否真表示"密钥不符"？
**是**（有源码依据）：它由 `wpas_auth_failed(reason="WRONG_KEY")` 产生，而该调用来自
`could_be_psk_mismatch()`（4-way 握手失败 + PSK + 非 SAE）。
**但**：**`reason=AUTH_FAILED` 不是密码错**（802.1X/EAP）→ 解析必须**只认 WRONG_KEY**。

### AP-3 与 AT-3 能否在同一真实 Attempt 同时获得？
**能，但不保证**：
- AP-3 事件自带 `id=N`（**supplicant 的 netId**）+ `ssid="X"` → 若 `N == 本次新建 netId` → **AT-3**；
- **但** `id=` 是 supplicant 内部 netId，与 Android `Net ID` 是否同一体系**需真机验证**（**未证实，列 R-6**）。
- 若两者不同体系 → **AP-3 只能到 AT-1/AT-2** → **永不可永久排除**（严重，须真机确认）。

**不能证明的能力**：AP-3 × AT-3 的可达性 **尚未真机证实** → **不得假设补齐**。

---

## B1→B5 迁移契约（修正）

### B1 必须建立的**全局安全闸门**（不等 B5）

```java
// 在 CrackService 与 CrackEngine 的【所有】持久化/推进点前置：
static boolean allowPersist(Verdict v, EvidenceGrade.Level g) {
    return v == AUTH_FAIL && g == DIRECT && /* 归属检查由 Adjudicator 保证 */ true;
}
```
**B1 起生效的硬约束**：
1. **所有 AUTH_FAIL 出口**不得绕过证据等级 + 归属检查；
2. **旧分支**（未迁移的）**不得绕过永久排除闸门** → 未迁移分支**一律降 INFERRED**（临时）；
3. **支A 的 AT-1 不得 DIRECT**（第一批修）；
4. **INCOMPLETE 不得写 triedSet、不得推 cursor**；
5. **旧数据恢复**不得把不确定结果重新解释为可永久排除（`normalizeInterrupted` 只改 RUNNING→PAUSED，**不得改 grade**）。

### 全量下游位置（已核实，**5 处，不是 3 处**）

| # | 位置 | 作用 |
|---|---|---|
| 1 | `CrackService:1073` | `persistable(a.grade)` → `triedSet.add` |
| 2 | `CrackEngine:638` + **`:2429`** | `advancesCursor(grade)` → `cursor = nextCursor(...)` |
| 3 | `VerdictTally:53/56` | `persistable(g)` → confirmedFail |
| **4** | **`BackoffPolicy:84`** | `countable(v)`：`SUCCESS‖AUTH_FAIL` → 退避样本 |
| **5** | **`R3AuditProbe:781/811/818`** | **另一个构造 `Verdict.AUTH_FAIL` 的地方** → 必须纳入"防绕过"清单 |

### triedSet / cursor 的读写与恢复位置

| 操作 | 位置 |
|---|---|
| 写 | `CrackService:1074`（add）/ `:1199`（save） |
| 读/载入 | `CrackService:918`（load）/ `921`（filterTried） |
| 恢复 | `normalizeInterrupted`（`TaskQueue`）+ `filterTried` |
| cursor 写 | `CrackEngine:2429` → `updateProgress`（TaskQueue） |
| cursor 读 | `TaskQueue.load` → `executeTask(startOffset)` |

---

## 测试四分类（不得混称）

| 类别 | 说明 | 例 |
|---|---|---|
| **纯逻辑** | 只测 `Adjudicator`/`SuccessRule`/`ClockBridge` 等纯函数 | AT×AP 组合矩阵 |
| **生产入口集成** | 经 `tryOne()` / `CrackService` 真实路径 | 延迟成功、ASSOCIATING |
| **FakeShell 模拟** | 注入假 shell + 虚拟时钟 | 现 `AttemptLifecycleTest` |
| **真机验证** | 真实热点（须用户授权） | **尚未做** |

> **模拟测试不得描述为真机验证。**

## 每批交付计划（含 APK 对应）

| 批 | 内容 | 测试 | commit | APK |
|---|---|---|---|---|
| B1 | `stillActive` + INCOMPLETE + 支A 降级 + 全局闸门 | 生产入口（#2-#6/#17）+ 全量 | 独立 | 是（versionCode+1，记 SHA-256） |
| B2 | failover / m1retry 接入 | 同上 | 独立 | 是 |
| B3 | authcast 接入（含测试注入口） | 同上 | 独立 | 是 |
| B4 | 支B 换统一裁决 | 同上 | 独立 | 是 |
| B5 | triedSet/cursor 改独立 `persistable` | 同上 | 独立 | 是 |

**纯文档/测试脚手架的 commit 不出 APK。**

---

## 残余风险（v2.1 更新）

| # | 风险 |
|---|---|
| R-1 | netId 缺失时可能不存在可永久排除的组合（命中率） |
| R-2 | ~~壁钟映射误差~~ → **已由 `-v monotonic` 消除** |
| R-3 | SAE/WPA3 无 AP-3 |
| R-4 | 同 SSID 多配置归属不可靠 |
| R-5 | ColorOS 日志可能缺失 |
| **R-6** | **supplicant `id=` 与 Android `Net ID` 是否同体系未证实** → AP-3×AT-3 可达性未证实 |
| **R-7** | netId 复用确实存在 → SP-2 不可单独成立 |

---

## 本轮不做
不改生产代码；不提交；不改版本；不出 APK；不动队列/UI/恢复；不降断言；不以 UNKNOWN 伪造通过；不把 netId/SSID/BSSID/时间接近性伪装成 Attempt 唯一标识。
