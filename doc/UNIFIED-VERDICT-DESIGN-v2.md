# WPAForge 统一裁决核心 —— v2 修订方案（P0 修正版）

> 状态：**方案稿 v2**。本轮**不改生产代码**。取代 v1 的 Adjudicator 伪代码与能力分级。
> 本版逐条回应 P0-1..P0-6，所有"生产事实"均已核实到源码行号。

---

## P0-1 修正 Adjudicator 的失败规则

### 1.1 被否决的原规则

```java
// ❌ v1 错误：连接终止就升级 AUTH_FAIL
if (f != null) {
    if (f.attrib == AT_3 && f.proven == PP_3) return AUTH_FAIL;
    if (act.stillActive()) return UNKNOWN;
    return AUTH_FAIL;          // ← 不接受
}
```

**理由**：**"连接已经停止" != "密码错误"**。弱失败证据不能因连接终止而升级。

### 1.2 新规则（证据组合 → Verdict）

| 组合 | Verdict | persistable | 说明 |
|---|---|---|---|
| **AT-3 × AP-3** | `AUTH_FAIL` | **是** | 唯一可永久排除 |
| **AT-2 × AP-3** | `AUTH_FAIL` | 否 | 可判本轮失败（可回退） |
| **任意 × AP-2（拒绝）** | **`NETWORK_REJECTED`** | 否 | 关联被拒 ≠ 密码错 |
| **任意 × AP-1（只证明"没连上"）** | 见 1.3 | 否 | **永不产 AUTH_FAIL** |
| **任意 × AP-0** | `UNKNOWN` | 否 | 无证明力 |

> **AP-\* 是新的"认证失败证明力"分级**（P0-3），**不再复用 PP-3**。

### 1.3 AP-1（只证明"没连上"）的判定

```
若连接仍活动            -> UNKNOWN      (等终局/INCOMPLETE)
若连接已终止且无其它证据 -> TERMINATED_UNKNOWN
若期限到                -> INCOMPLETE / TIMEOUT
```
**任何情况都不产 AUTH_FAIL、都不产 persistable。**

### 1.4 AUTH_FAIL vs persistable

| 概念 | 含义 | 影响 |
|---|---|---|
| `AUTH_FAIL` | **本轮裁决认定认证失败** | 界面显示、重试策略（**可回退**） |
| `persistable` | **此后不必再试** | 写 triedSet + 推 cursor（**不可逆**） |

**下游是否误用 AUTH_FAIL 当永久失败？** —— **当前不会**（已核实）：
| 下游点 | 源码 | 实际依据 |
|---|---|---|
| triedSet 写入 | `CrackService:1073` | **`EvidenceGrade.persistable(a.grade)`** |
| cursor 推进 | `CrackEngine:638` | **`advancesCursor(grade)`** |
| 失败统计 | `VerdictTally:56` | `persistable(g)` |
> 两者当前**都定义为 `g == DIRECT`**。→ **改为独立 `persistable` 后，只需改这 3 处。**

---

## P0-2 AT-3 的现实可获得性与时钟体系

### 2.1 netId 在各阶段能否获取（已核实源码）

| 阶段 | 手段 | 源码 | 实测可靠性 |
|---|---|---|---|
| 请求**前** | `netIdIn(list-networks, ssid)` / `currentNetId()` | `:1336` / `:872` | 高（旧配置若在） |
| 请求**提交后** | `targetNetId` 探测（轮询 status） | `:1442`（初值 **-1**） | **低**（配置未建好即 -1） |
| 失败**后** | `status` 的 `Net ID:` | `:872` | 中（仅已连上时出现） |

**结论**：**netId 不可假定可得**。当前 `targetNetId` 时序敏感，实测常为 -1。

### 2.2 如何确定 netId 属于本次请求

```
本次 netId = 命令后出现、且 != 命令前记录的 preNetId 的那个
若 preNetId 与 postNetId 相同 -> 视为"旧连接"，AT 降级
```

### 2.3 netId 复用 / 缺失

| 情况 | 处理 |
|---|---|
| 复用（系统回收旧值） | 与 preNetId 比对；相同→旧连接（AT≤1） |
| 缺失（-1） | **最高 AT-2**；**禁止 AT-3** |
| 冲突 | 降级 + 记录 |

### 2.4 无法可靠归属时的**替代证据组合**（可否支撑永久排除）

| 组合 | 能否永久排除 | 说明 |
|---|---|---|
| AT-3 × AP-3 | ✅ | netId 绑定 + 密钥不符 |
| AT-2 × AP-3 | ❌ | 缺 netId，可能非本次 |
| **AT-1 × AP-3**（当前支A） | ❌ **必须降级** | **现缺陷** |
| 替代：**AP-3 + 前置闸门成功 + 时间落在区间** | ⚠️ 待评估 | 见 2.5 |

**结论**：**在没有 netId 的现实下，可能不存在"可可靠永久排除"的组合** —— 这必须如实列为**残余风险**，不得用 AT-1 冒充。

### 2.5 时钟体系（必须修正）

**现状（已核实）**：
- logcat 行时间 = **设备壁钟**（`MM-DD HH:MM:SS.mmm`）；
- `windowFromMs = System.currentTimeMillis()`（`:1378`，壁钟）；
- `clock().uptimeMillis()` = **单调**。

**问题**：**壁钟与单调不可直接比较**（NTP 调整、时区、跨日）。

**修正**：
```
① Attempt 边界统一用【单调】(clock().uptimeMillis())
② logcat 事件时间 = 壁钟 → 通过"采样时刻映射"转单调：
     recordedAt = monotonicAt(采集该行时的 uptime)
     occurredAt = recordedAt - (logcatNowWall - eventWall)   // 用同一批次内的壁钟差
③ 若无法映射（跨批次延迟）→ 事件的 occurredAt 记为"不确定"，
   归属判定退化为"仅按采集时间窗"
```
**必须实现**：`ClockBridge`（壁钟↔单调的批次内映射），否则"事件发生时间"不可信。

---

## P0-3 成功与失败的证明能力分开（四套等级）

**不再用 PP-3 描述成功。**

### 3.1 `Attribution`（归属，共用）
`AT-0`（无）/ `AT-1`（SSID）/ `AT-2`（+BSSID）/ `AT-3`（+netId+时间窗）

### 3.2 `AuthProof`（认证失败证明力）
| 等级 | 依据 |
|---|---|
| `AP-0` | 无 |
| `AP-1` | 只能证明"本次没连上"（`4-Way Handshake failed`） |
| `AP-2` | 关联被拒（`ASSOC-REJECT`）→ 归入网络拒绝 |
| `AP-3` | supplicant 明确"密钥不符"（`TEMP-DISABLED reason=WRONG_KEY`；**仅 WPA/WPA2-PSK**） |

### 3.3 `RejectProof`（网络拒绝证明力）
`RP-0` / `RP-1`（`ASSOC-REJECT` 可归属）/ `RP-2`（+status_code 明确）→ 产 **`NETWORK_REJECTED`**

### 3.4 `SuccessProof`（成功证明力）★新增
| 等级 | 依据 | 可否 SUCCESS |
|---|---|---|
| `SP-0` | status 显示连着目标 SSID | ❌ |
| `SP-1` | `COMPLETED` + 有效 IP | ❌（可能旧连接） |
| `SP-2` | SP-1 + **netId 属于本次**（!= preNetId） | ✅ |
| `SP-3` | SP-2 + **前置闸门均成功**（删配置 + 断开确认） | ✅（最强） |

### 3.5 `Lifecycle`（生命周期证据）
`LC-ACTIVE`（连接仍活动）/ `LC-TERMINATED`（确终止）/ `LC-UNKNOWN`

### 3.6 SUCCESS 必须证明"本次建立"

**前置闸门状态必须进入裁决**：
| 闸门 | 失败时 |
|---|---|
| 删配置失败 | `forgetOk=false` → **SP 最高 SP-1** → 成功降级 `UNKNOWN` |
| 断开失败 | `disconnectOk=false` → 同上 |
| netId 不可得 | 无 SP-2 → 最高 SP-1 → `UNKNOWN` |

**特别场景**：
| 场景 | 处理 |
|---|---|
| 命令前已连目标 | 记录 preNetId；成功要求 netId != preNetId；否则 UNKNOWN |
| 删配置失败 | 成功降 UNKNOWN |
| 断开失败 | 成功降 UNKNOWN |
| netId 不可得 | 成功降 UNKNOWN |
| 同 SSID 不同 BSSID | AT ≤2；成功需 BSSID 匹配 |
| 同 BSSID 不同 Attempt | 按 netId + 时间窗区分 |

> **代价**：在 netId 常缺失的真机上，SUCCESS 可能频繁落 UNKNOWN → **这是"宁可不判也不误判"**，但**必须评估对命中率的影响**（列为残余风险 R-1）。

---

## P0-4 INCOMPLETE 与 frozen 的关系（修正）

### 4.1 三种"结束"必须分开

| 概念 | 含义 | 是否终局 |
|---|---|---|
| **Verdict 快照** | 某时刻的裁决结果 | ❌ 可被后续证据刷新（除非终局类） |
| **底层连接请求生命周期结束** | Android 侧确实结束 | 由 `ConnectionActivity` 判定 |
| **Attempt 最终冻结** | 不再接受任何证据 | 仅 `SUCCESS/AUTH_FAIL/ERROR` |

### 4.2 INCOMPLETE 的正确语义

```
INCOMPLETE = "本次观察期内未收敛"，是【临时快照】：
  · 写盘记录该 Attempt 并未收敛（供续跑参考）
  · 【不冻结】—— 后续到达的 SUCCESS 证据仍可【重新裁决】为 SUCCESS
  · 【不写 triedSet】【不推 cursor】
```

**重新裁决规则**：
```
INCOMPLETE 后若收到 SUCCESS(SP>=2) → 重新裁决为 SUCCESS（覆盖）
INCOMPLETE 后若收到 AUTH_FAIL(AT-3×AP-3) → 重新裁决为 AUTH_FAIL
INCOMPLETE 后收到【旧 Attempt】事件 → 丢弃（按 evolved netId/时间窗过滤）
```

### 4.3 证明 INCOMPLETE 不让正确密码永久丢失

| 问题 | 保证 |
|---|---|
| 正确密码会不会因 INCOMPLETE 丢失？ | **不会**（不写 triedSet、不推 cursor、可重新裁决） |
| 旧 Attempt 事件会污染新吗？ | 不会（netId/时间窗过滤 + 各自 Attempt 独立） |
| 恢复后？ | INCOMPLETE 候选**保留 + 重试**（不跳过） |

---

## P0-5 迁移顺序（修正：支A 纳入第一批）

| 批次 | 内容 | 理由 |
|---|---|---|
| **B1**（第一批） | ① `stillActive` 检查 ② INCOMPLETE ③ **支A 降级（AT-1 不得 DIRECT）** | **支A 当前会永久排除正确密码 → 最高风险，必须第一批** |
| B2 | failover / m1retry 接入（低风险） | 二者本就 INFERRED |
| B3 | authcast 接入（需测试注入口） | 依赖广播注入 |
| B4 | 支B 接入统一裁决（替换窗口 hack） | — |
| B5 | triedSet/cursor 改独立 `persistable` | — |

**每批必须**：门禁"禁止非裁决点构造终局 Verdict"的计数**单调递减**。

---

## P0-6 下游影响审计（已核实）

| Verdict/等级 | 生产使用点 | 实际影响 | 改动必要性 |
|---|---|---|---|
| `persistable(grade)` | `CrackService:1073`（triedSet 唯一写入） | 永久排除 | **改为独立判定** |
| `advancesCursor(grade)` | `CrackEngine:638` | 续跑起点 | **改为独立判定** |
| `persistable(g)` | `VerdictTally:56` | 失败统计（UI） | 同步 |
| `INFERRED` | `CrackEngine:2426`（补验轮收集） | 第二轮重试 | 保持 |
| `SUCCESS` | 循环内 return + `CrackService` 命中处理 | 命中收尾 | 保持 |
| **triedSet 持久化** | `TriedSet.save`（1199） | 跨会话 | 不受影响 |
| **cursor 持久化** | `updateProgress` | 跨会话 | 不受影响 |

**结论**：**下游影响面仅 3 个判定点** —— 但**必须逐一改**，不能只改 `persistable()` 就假定安全（`advancesCursor` 也要独立）。

---

## 附：当前无法可靠证明的能力（残余风险）

| # | 残余风险 |
|---|---|
| R-1 | netId 缺失时**可能不存在**可支撑永久排除的组合 → 命中率下降或需放宽（**须真机评估**） |
| R-2 | 壁钟↔单调映射在跨批次延迟下不精确 |
| R-3 | `TEMP-DISABLED` 在 SAE/WPA3 不产生 → 该族网络无 AP-3 |
| R-4 | 同 SSID 多配置时 SSID 归属不可靠 |
| R-5 | ColorOS 不输出部分 supplicant 日志 → 证据可缺失 |

---

## 本轮不做

不改生产代码；不提交；不动版本号/队列/UI；不降低断言；不以 UNKNOWN 伪造零误判；不用固定延时替代生命周期。
