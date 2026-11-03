# D4：统一截止时间裁决（把第二套判定并进 Adjudicator）

> 2026-11-02。基线 `7f72519` / 1.9.32 (128) / 3804 通过。

## 一、缺陷（真实根因）

`CrackEngine` 里存在两套互相独立的判定逻辑：

### ① 前置闸门失败时绕过 Adjudicator 直接造 Attempt

- `forgetTargetByShell` 失败 -> UNKNOWN
- 净检失败（旧配置还在）-> UNKNOWN
- 基线两侧都读不到 -> UNKNOWN
- 断开失败 -> UNKNOWN

四条路径都不产生 Evidence、不经过 Adjudicator。
`AttemptState.Gate` 的四个枚举值里，STALE_CONFIG / UNREADABLE /
DISCONNECT_FAILED 在生产代码中从未被构造 —— 全树只有 `Gate.OK`。

后果：Adjudicator 的「规则 0（前置闸门未通过 -> UNKNOWN）」是永不触达的死分支。
矩阵测试跑满 192 格，测的是一个生产不存在的输入维度。

### ② 到期归类 classifyDeadline 是第二套判定

tryOneShell 末尾先走 classifyDeadline（五分支纯函数），
紧接着又叠一段「生命周期兜底」把 UNKNOWN/TIMEOUT 升级成 INCOMPLETE。
两张表先后执行、后者覆盖前者；「连接已终止」与「状态读不到」最终都是 TIMEOUT，
而 Adjudicator 对这两者有明确区分（TERMINATED_UNKNOWN vs UNKNOWN）。

## 二、修复方向

1. 前置闸门失败 -> AttemptState 带真实 Gate，交给 Adjudicator 规则 0。
2. classifyDeadline 保留签名，实现改为「构造 AttemptState + 证据 -> Adjudicator」。
3. 循环末尾生命周期兜底合并进 classifyDeadline 的输入，不再二次覆盖 verdict。

### 不可回归的行为

- not-attempted -> ERROR
- channel-no-response -> UNKNOWN
- on-target-unattributed / netid-conflict / status-unclear -> UNKNOWN
- 连接 ACTIVE -> INCOMPLETE
- 连接 TERMINATED 且只有 AP-1 -> TERMINATED_UNKNOWN
- 连接 UNKNOWN -> UNKNOWN
- 无证据且非 ACTIVE -> TIMEOUT
