# WPAForge Attempt 生命周期与证据裁决设计（阶段 A）

> 状态：**设计稿**（阶段 A 产出，未实施）。阶段 C 起才动生产代码。
> 目标：把「一次候选验证」从「一个 AUTH_FAIL 布尔」升级为 **可追溯生命周期 + 双维度证据 + 统一裁决**。

---

## 0. 为什么需要这份设计

现有判定链反复出现同类误判，根因不是单点 bug，而是三个闭环缺失：

| 缺失 | 表现 | 证据 |
|---|---|---|
| **生命周期模型缺失** | 本地轮询结束被当成连接终止 | `tryOneShell` 只等本地 `deadline`；`connect-network` 是 `oneway` 异步 |
| **归属模型缺失** | 文本切片无下界 → 相邻 Attempt 事件互串 | `AttemptMarker.sliceAfter` 取「标记后全部文本」 |
| **裁决模型缺失** | 5 个失败分支各自 `return AUTH_FAIL`，无统一优先级 | failover/authcast/m1retry/wpalogA/wpalogB |

---

## 1. Attempt 生命周期状态机

一次候选 = 一个 **Attempt**；状态只沿箭头前进，不回退。

```
  SUBMITTED -> CONNECTING -> ASSOCIATING -> AUTHENTICATING -> OBTAINING_IP -> SUCCESS
                    |             |               |
                    |             |               +-> FAILED_AUTH  (终局·可靠归属)
                    |             +-> REJECTED ---+
                    |
                    +-> TERMINATED_UNKNOWN  (终止但原因不明)
                    +-> INCOMPLETE          (观察期到·连接仍活动)   <- 新增
                    +-> UNDETERMINED        (底层状态无法确认)
```

### 1.1 状态定义

| 状态 | 进入条件（可观测） | 退出条件 | 可用证据 |
|---|---|---|---|
| `SUBMITTED` | `connect-network` 已发出 | status 变化 / 系统开始连接 | 命令已发；**不代表连上** |
| `CONNECTING` | status 出现目标过渡态 | 下一态 / 超时 | `SupplicantState` |
| `ASSOCIATING` | 日志 `Trying to associate` | 下一态 | associate 行 |
| `AUTHENTICATING` | `FOUR_WAY_HANDSHAKE` / 见 M1 | 成功/失败 | M1、握手日志 |
| `OBTAINING_IP` | `COMPLETED` 且无 IP | 有 IP / DHCP 超时 | status |
| **`SUCCESS`** | 四条件同行 | 终局 | WifiInfo 单行 |
| **`FAILED_AUTH`** | 带归属的直接否认（见 §2-B 高证明力） | 终局 | TEMP-DISABLED(带 netId+SSID) |
| `REJECTED` | ASSOC-REJECT（可归属时） | 终局/继续 | assoc-reject |
| `TERMINATED_UNKNOWN` | 连接确已终止但无失败证据 | 终局 | status 离开目标且未连上 |
| `INCOMPLETE` | 观察期到·连接**仍活动**（新增） | 终局 | status 仍显目标过渡态 |
| `UNDETERMINED` | 通道/日志不可读 | 终局 | — |
| `ERROR` | 执行器/通道故障 | 终局 | — |

**硬规则**：
> **本地轮询结束 != Android 底层请求终止。**
> 只在观察到「连接确已终止（离开目标且回连别的网 / 明确失败事件）」或「明确成功」时才允许终局。

---

## 2. 双维度证据评价（禁止合并）

### A. 归属可靠性 Attribution（该事件是否属于当前 Attempt）

| 等级 | 含义 | 依据 |
|---|---|---|
| `AT-0` | 完全无法归属 | 零字段事件（`4-Way Handshake failed`） |
| `AT-1` | 可归属到**网络**（SSID） | 带 `ssid="X"`；**SSID 重名时不成立** |
| `AT-2` | 可归属到**网络+AP** | SSID + BSSID 同时匹配 |
| `AT-3` | 可归属到**本次连接尝试** | 上述 + netId==本次新建值 + 时间落在 Attempt 区间内 |

> **SSID 相同 != Attempt 相同**；**netId 存在 != netId 属于当前 Attempt**（须与「本次新建值」比对）。

### B. 证明能力 ProvingPower（即使属于当前 Attempt，能否证明认证失败）

| 等级 | 含义 | 例子 |
|---|---|---|
| `PP-0` | 不能证明任何事 | `CTRL-EVENT-DISCONNECTED`（多因） |
| `PP-1` | 只能证明「本次没连上」 | `4-Way Handshake failed`（可能丢包/AP慢） |
| `PP-2` | 强指示认证失败但协议非绝对 | `CTRL-EVENT-ASSOC-REJECT` |
| `PP-3` | supplicant 明确判定「密钥不符」 | `TEMP-DISABLED reason=WRONG_KEY`（**仅 WPA/WPA2-PSK；SAE 不产生**） |

**组合 -> 裁决资格**：
| 归属 | 证明 | 资格 |
|---|---|---|
| AT-3 | PP-3 | **可判 DIRECT（唯一允许永久排除的组合）** |
| AT-2 | PP-3 | 可判 AUTH_FAIL，但需冲突检查 |
| <=AT-1 | 任意 | 仅 INFERRED |
| 任意 | <=PP-1 | 仅 INFERRED / 不得 DIRECT |

---

## 3. 统一 Verdict 与规则

| Verdict | 含义 | 允许永久排除 | 允许推 cursor |
|---|---|---|---|
| `SUCCESS` | 当前 Attempt 有可靠成功证据 | — | — |
| `AUTH_FAIL` | 当前 Attempt **可靠归属 + 充分认证失败证据** | 仅 AT-3 x PP-3 | 仅 DIRECT |
| `NETWORK_REJECTED` | 关联被拒/网络层拒绝 | 否 | 否 |
| `TIMEOUT` | 观察期内无终局结果 | 否 | 否 |
| `INCOMPLETE` | 观察期结束但**连接仍活动** | 否 | 否 |
| `UNKNOWN` | 证据冲突/归属不足/无法裁决 | 否 | 否 |
| `ERROR` | 执行器/基础设施错误 | 否 | 否 |

**规则**：
1. SUCCESS 必须有当前 Attempt 的可靠成功证据。
2. AUTH_FAIL 必须有当前 Attempt 的可靠归属 + 充分认证失败证据。
3. NETWORK_REJECTED 不能自动等同密码错误。
4. TIMEOUT = 观察期内无可靠终局。
5. INCOMPLETE = 连接仍活动或生命周期未收敛。
6. UNKNOWN = 冲突/归属不足/无法裁决。
7. ERROR = 执行器错误，不得伪装成密码错误。

> **只有 AT-3 x PP-3 允许永久排除。** 其余一律不写 triedSet、不推 cursor。

---

## 4. 观察与终结条件（替代「固定窗口」）

```
终结条件（任一满足即终局）：
  T1 观察到 SUCCESS 证据（四条件同行）          -> SUCCESS
  T2 观察到 AT-3 x PP-3 失败证据                -> AUTH_FAIL (DIRECT)
  T3 观察到「连接确已终止且无成功」             -> 按已有证据裁决 / UNKNOWN
  T4 最大观察期限到：
       · 连接仍活动      -> INCOMPLETE
       · 连接已终止无结论 -> TIMEOUT
       · 底层状态不可确认 -> UNDETERMINED -> UNKNOWN
```

**关键约束**：
- **弱失败信号（AT<=2 或 PP<=2）不得在连接仍活动时结案**；只能「武装」，等 T1/T3/T4。
- **最大观察期限必须实验确定**（不硬编码）；以「连接生命周期自然终结」为主，最大期限仅兜底。
- **成功证据在任何时刻到达都必须参与当前 Attempt 裁决**（直到该 Attempt 冻结）。

---

## 5. 事件冻结与跨 Attempt 隔离

| 规则 | 说明 |
|---|---|
| **Attempt 冻结** | 进入终局后证据集**冻结**；之后到达的事件**不得改变它** |
| **下界 + 上界** | 切片必须 `[本条 marker, 下一条 marker)`，**不得取「标记后全部文本」** |
| **旧请求隔离** | 上一条请求未收敛时其事件不得被下一条继承；按 **netId / 时间窗** 过滤 |
| **延迟事件** | 采集时间 > Attempt 结束的事件，划归其**发生时刻**所属 Attempt；不可判定则丢弃 |
| **netId 复用** | 记录「本次新建 netId」；冲突即降级归属 |

---

## 6. 回归测试矩阵（阶段 B 目标）

| # | 场景 | 期望 | 当前 |
|---|---|---|---|
| 1 | 正确密码 1s 内成功 | SUCCESS | 通过 |
| 2 | 正确密码 1.2s 成功 | SUCCESS | **未通过** |
| 3 | 正确密码 4.1s 成功 | SUCCESS | **未通过** |
| 4 | 正确密码 6s 成功 | SUCCESS | **未通过** |
| 5 | 正确密码 15s 成功 | SUCCESS | **未通过** |
| 6 | 正确密码 19s 成功 | SUCCESS | **未通过** |
| 7 | 零字段握手失败独立出现 | 不得 DIRECT | 通过 |
| 8 | 明确错误密码（AT-3 x PP-3） | AUTH_FAIL/DIRECT | 通过 |
| 9 | 支A 旧事件污染当前 Attempt | 不得 DIRECT | 待测 |
| 10 | Attempt A 延迟失败事件进入 B | B 不受影响 | 待测 |
| 11 | 连接仍活动但观察期结束 | INCOMPLETE | **未通过（无该状态）** |
| 12 | 连接终止但原因不明 | UNKNOWN（非 AUTH_FAIL） | 待测 |
| 13 | 成功与失败证据冲突 | SUCCESS 优先 | 待测 |
| 14 | 暂停恢复时 Attempt 状态正确 | 状态自洽 | 待测 |
| 15 | INCOMPLETE 恢复续跑不跳过候选 | 不跳过 | 待测 |
| 16 | triedSet/cursor 只受允许 Verdict 影响 | 仅 DIRECT 影响 | 部分 |

---

## 7. 阶段划分

| 阶段 | 内容 | 状态 |
|---|---|---|
| **A** | 本设计文档 | **本阶段产出** |
| **B** | 核心回归测试（支B>1s、支A误归属、双Attempt） | **本阶段产出** |
| C | 统一证据归属 + 裁决机制 | 待批 |
| D | 五分支接入统一裁决 | 待批 |
| E | 候选生命周期（triedSet/cursor 保护） | 待批 |
| F | 真机 + 兼容性验证 | 待批 |

---

## 8. 明确不做

- 不把确认窗口从 1 秒改成 20 秒就宣称解决
- 不把 INFERRED 升成 DIRECT
- 不因「带 SSID」就认定归属可靠
- 不因「命令返回」就认定底层连接结束
- 不全量降级 UNKNOWN 制造零误判
- 不删除既有测试或改期望值掩盖缺陷
