<!-- GitHub Release 正文。标题填：WPAForge 1.9.65 · 判据 D 采集时序整改（认证失败不再漏判） -->
<!-- tag: v1.9.65   target: main -->

## 1.9.64 能读证据了，但读得太早

1.9.64 已经把 `dumpsys wifi` 里一直被丢弃的三条认证失败证据链接进来，
方向是对的。但它的**采集时序**有真实缺陷：

```
DUMPS_PROBE_AT_MS = { 2600, 3600 }     ← 只有两个固定时刻
dumpsProbedSlot    = 一次性            ← 查过之后不再查
```

而真机的认证失败事件，通常在连接尝试**结束之后**才写进
`mConnectionEvents`，实测耗时 **3.8~4.9 秒**（部分 AP / 部分 ROM /
弱信号重试会更晚）。2600ms 与 3600ms 两个探测点都早于这部分事件，
加上 `dumpsProbedSlot` 的一次性限制，结果就是——

> 真实的 `AUTH_FAILURE_WRONG_PSWD` 明明已经写进 `dumpsys`，
> 界面上却显示「未确定」。

## 这一版改什么

### 1. 采集时序：从「固定时刻」到「生命周期驱动 + 有限轮询」

时序逻辑抽成纯逻辑类 `DumpsProbePlan`（可离线穷举、有单元测试）：

- **固定时刻兜底**：2600 / 3600 / **5200ms**。5200 覆盖真机 3.8~4.9s 的落盘区间，
  它是**兜底，不是唯一修复**。
- **生命周期终止立即触发**：观察到连接进入 `TERMINATED`，立刻再查一次，
  不必等下一个固定时刻。
- **主循环退出后有限轮询**：连接生命周期结束、主循环按时间预算退出后，
  在 **3 秒截止时间**内按 600ms 有间隔地**有限**轮询；达到截止仍无证据，
  才交给裁决层降级为 UNKNOWN / INCOMPLETE。
- **一次未命中不再永久封锁**：`dumpsProbedSlot` 的一次性限制**已移除**，
  固定时刻、终止触发、兜底轮询三条路径并行。

### 2. 归因：`DumpsAttribution`（与测试共用同一份代码）

认证失败必须**同时**满足，缺一不认：

1. 当前 attempt 的时间窗口（两端同天、顺序正确）；
2. 窗口内 target SSID 的 `uid=0` `ADD_OR_UPDATE`——**本次新建的配置**；
3. 明确的 `AUTH_FAILURE_WRONG_PSWD` 或等价的 `DISABLED_BY_WRONG_PASSWORD`；
4. 事件时间落在窗口内；
5. 连接级还要求 **BSSID 收窄**（同 SSID 不同 BSSID 不误认）。

**`uid=0` 本身不能单独证明某条失败属于当前候选**——必须同时有本次配置关联，
否则会读到上一条候选、或同 SSID 历史配置的失败。

### 3. 判定与持久化不变量：一个字都不放松

| 结果 | 允许行为 |
|---|---|
| AUTH_FAIL + DIRECT | 写 TriedSet、推进 Cursor |
| AUTH_FAIL + INFERRED | **不**永久排除 |
| UNKNOWN / INCOMPLETE | **不**永久排除 |
| NETWORK_AUTH_FAIL | **不**冒充候选级密码错误 |
| SUCCESS | 必须满足原有严格成功条件 |

### 4. `m1retry` 来源审计结论

`m1retry` 来自 `logcat -d -s wpa_supplicant` 的
「RX message 1 of 4-Way Handshake」计数，只在上一条目标记
（`AttemptMarker.sliceAfter`）之后的切片里统计，归属 **AT1 / NOT_CONNECTED**，
**永不产生 AUTH_FAIL**——不可能被当成「已证实密码错误」。本机（PJX110）
`wpa_supplicant` 日志恒为 0 行，`m1retry` 实际不会触发；候选级证据只能来自判据 D。

### 5. 破解页 UI：统计区重排

- 四列横排 → **窄屏 2×2 网格 / 宽屏 1×4**，每个指标独立容器、标签与数字分离。
- 「未确定」细分（疑似密码错误 / 网络认证失败 / 连接未完成 / 环境异常 / 无法判断）
  从数字格里挪出，改为**独立信息区**，固定顺序、零值隐藏。

## 验证

- 全量门禁：**236 个测试类全部通过**（+3 类 / +81 用例）。
- 新增：`DumpsProbePlanTest`（时序 27）、`DumpsAttributionSafetyTest`（归因 27）、
  `CrackStatsModelTest`（布局/分类 27）。
- 真机：错误密码必须落到「已证实错误」而不是「未确定」；正确密码仍由原有严格成功条件独占判定。
