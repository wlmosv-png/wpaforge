# WPAForge 统一裁决核心 —— 第一轮实施方案

> 状态：**方案稿**（待审）。本轮**不改生产代码**。
> 前置：`doc/ATTEMPT-LIFECYCLE-DESIGN.md`（阶段 A 设计）。

---

## 0. 要解决的本质问题

现有 5 个证据来源各自 `return new Attempt(AUTH_FAIL, ...)`，**每个 return 都是一个独立的"终局裁决点"**。任何一处判断宽松，就绕过其他所有保护。

**目标**：把 N 个裁决点收敛为 **1 个**。

```
旧：  failover → return AUTH_FAIL
      authcast → return AUTH_FAIL
      m1retry  → return AUTH_FAIL
      wpalogA  → return AUTH_FAIL (DIRECT)
      wpalogB  → return AUTH_FAIL (INFERRED)
      (5 个独立终局点，互相不知道)

新：  5 个来源 → addEvidence(...)  ──┐
      成功判据  → addEvidence(...)  ──┼──► adjudicate(attempt, activity) → Verdict
      超时      → markDeadline()    ──┘
      (唯一终局点)
```

---

## 1. 数据结构

### 1.1 `Evidence`（证据，不可变）

```java
final class Evidence {
    final Source  source;      // WPA_LOG / STATUS / AUTHCAST / M1_RETRY / FAILOVER / SUCCESS
    final long    occurredAt;  // 事件发生时间（设备时钟，来自 logcat 时间戳）
    final long    observedAt;  // 采集时间（单调 uptime）
    final Attrib  attrib;      // AT_0 / AT_1 / AT_2 / AT_3
    final Proven  proven;      // PP_0 / PP_1 / PP_2 / PP_3
    final Kind    kind;        // SUCCESS / AUTH_FAIL / REJECT / DISCONNECT / NEUTRAL
    final String  detail;      // 判词（不含明文口令）
}
```

### 1.2 `AttemptState`（生命周期，可变）

```java
final class AttemptState {
    final String  attemptId;
    final long    createdAt;      // 单调（命令发出）
    final int     requestedNetId; // 本次新建 netId；-1 = 未取到
    final String  targetSsid, targetBssid;
    long          endedAt;        // 终局单调时刻；0 = 未终局
    boolean       frozen;         // 终局后事件不得修改
    final List<Evidence> evidence = new ArrayList<>();

    void addEvidence(Evidence e);            // 冻结后丢弃 + 记日志
    void freeze(Verdict v, long at);
}
```

### 1.3 `ConnectionActivity`（连接活动状态，每轮采样）

```java
final class ConnectionActivity {
    enum Phase { UNKNOWN, IDLE, CONNECTING, ASSOCIATING, AUTHENTICATING, OBTAINING_IP, TERMINATED }
    final Phase phase;       // 来自 cmd wifi status
    final String ssid;       // status 里的 SSID
    final long   sampledAt;
    boolean stillActive();   // CONNECTING/ASSOCIATING/AUTHENTICATING/OBTAINING_IP
}
```

### 1.4 `Verdict`（结论）与 `Persistable`（排除资格）—— **拆开**

```java
enum Verdict { SUCCESS, AUTH_FAIL, NETWORK_REJECTED, TIMEOUT, INCOMPLETE, UNKNOWN, ERROR }

// 独立于 Verdict —— 只有它为 true 才允许写 triedSet / 推 cursor
static boolean persistable(AttemptState a, Verdict v) {
    return v == Verdict.AUTH_FAIL
        && a.bestFailure() != null
        && a.bestFailure().attrib == Attrib.AT_3
        && a.bestFailure().proven == Proven.PP_3;
}
```

> **这是本轮最关键的结构变更**：把"是什么结论"（Verdict）与"能否永久排除"（persistable）解耦。

---

## 2. 统一裁决入口 `adjudicate`

```java
static Verdict adjudicate(AttemptState a, ConnectionActivity act, boolean deadlineReached) {

    // ① 成功优先：任何充分成功证据一律胜出（不因到达顺序改变）
    Evidence s = a.bestSuccess();
    if (s != null && s.proven == Proven.PP_3) return Verdict.SUCCESS;   // 四条件同行
    if (s != null) return Verdict.UNKNOWN;   // 成功帧但归属未定（netid 冲突）

    // ② 失败证据：取 AT×PP 最高者
    Evidence f = a.bestFailure();
    if (f != null) {
        if (f.attrib == AT_3 && f.proven == PP_3) return Verdict.AUTH_FAIL;      // 可永久排除
        if (act.stillActive()) return Verdict.UNKNOWN;      // ★ 连接仍活动：不结案（等终局/INCOMPLETE）
        return Verdict.AUTH_FAIL;                            // 非 AT_3×PP_3：可判失败，但不可永久排除
    }

    // ③ 无成功、无失败：看期限与连接状态
    if (!deadlineReached) return null;                       // 未终局，继续观察
    if (act.stillActive())  return Verdict.INCOMPLETE;       // ★ 新增
    if (act.phase == TERMINATED) return Verdict.TIMEOUT;
    return Verdict.UNKNOWN;
}
```

**三点关键**：
- **②中的 `act.stillActive()` 检查** —— 直接修复缺陷 2（ASSOCIATING 时不得结案）；
- **成功优先是"集合级"判断**（`bestSuccess()`），不再依赖"到达顺序"或"某一轮的同时性"；
- **`persistable` 与 Verdict 解耦** —— AT-2×PP-3 可得 AUTH_FAIL，但**不可永久排除**。

---

## 3. 五个证据来源的接入方式（不再各自 return）

| 来源 | 现在 | 改为 |
|---|---|---|
| **failover** | `return AUTH_FAIL(INFERRED)` | `addEvidence(AT_1/PP_1, FAILOVER)` + `continue` |
| **authcast** | `return AUTH_FAIL(DIRECT/INFERRED)` | `addEvidence(AT_2或1/PP_3, AUTHCAST)` + `continue` |
| **m1retry** | `return AUTH_FAIL(INFERRED)` | `addEvidence(AT_2/PP_1, M1_RETRY)` + `continue` |
| **wpalog 支A** | `return AUTH_FAIL(DIRECT)` | `addEvidence(AT_1或3/PP_3, WPA_LOG)` + `continue` |
| **wpalog 支B** | `return AUTH_FAIL(INFERRED)` | `addEvidence(AT_0/PP_1, WPA_LOG)` + `continue` |
| **成功（status/日志）** | `return SUCCESS` | `addEvidence(AT_1..3/PP_3, SUCCESS)` + `continue` |

**循环结束后（或 deadline）**：`Verdict v = adjudicate(a, activity, deadlineReached)`。

---

## 4. 如何**保证**旧代码不可能绕过新裁决

**这是本方案的核心工程约束**，用三层保证：

### 4.1 编译期/静态门禁（最强）
在 `R10InvariantTest` 的既有断言上扩展（**不降低**，而是**加严**）：
```
断言 A：CrackEngine 内 `new Attempt(password, Verdict.AUTH_FAIL` 的出现次数必须 == 0
        （除 adjudicate 内部那 1 处）
断言 B：`new Attempt(password, Verdict.SUCCESS` 的出现次数必须 == 0
```
> 关键：这不是"数出口"，而是**禁止任何非裁决点构造终局 Verdict**。
> 迁移期可临时保留计数，但**必须单调递减到 1**。

### 4.2 结构保证
- `Evidence` 与 `Verdict` 构造器**包内私有**，只有 `adjudicate` 能产出终局 Verdict；
- 各证据分支**没有权限**构造 `Attempt(Verdict.AUTH_FAIL, ...)`（编译不过）。

### 4.3 行为保证
- `frozen` 标志：Attempt 终局后 `addEvidence` 直接丢弃（防"旧事件改已冻结 Attempt"）。

---

## 5. 逻辑矛盾逐条回答

### 5.1 AT-2 × PP-3 为何可 AUTH_FAIL、但不能永久排除？

- **AUTH_FAIL** 表达的是"**本轮裁决认定认证失败**"——影响界面显示、重试策略；错了代价是"这一轮多试一次"。
- **永久排除**（写 triedSet + 推 cursor）表达的是"**此后这密码不必再试**"；错了代价是"**正确密码被永久错过**"。
- AT-2 表示**缺 netId 绑定**，即"可能不是本次 Attempt 的事件"→ 不足以支撑不可逆结论；
- 因此：**可判 AUTH_FAIL（可回退），不可永久排除（不可逆）**。
- **现状缺陷**：当前 `wpalog 支A` 实为 **AT-1**（只有 SSID），却给了 **DIRECT（可永久排除）** —— **必须降级**。

### 5.2 AUTH_FAIL 与 DIRECT 语义必须分开

**现状**：`Attempt.grade`（DIRECT/INFERRED）**同时**表达"证据等级"与"排除资格"，二者耦合。
**改为**：`Verdict`（结论）× `persistable`（bool，独立判定）。下游 `triedSet/cursor` **只看 `persistable`**。

### 5.3 AT-3 的 netId 如何从真实 Android 流程可靠获得？

| 途径 | 可靠性 | 说明 |
|---|---|---|
| `cmd wifi list-networks` 命令后读 | **低** | 时序敏感，实测常取到 -1（配置未建好） |
| `cmd wifi status` 的 `Net ID:` | **中** | 只在"已连上"时出现；与本条绑定需时序 |
| 命令前/后 netId 差集 | **中** | 命令前记录，命令后取"新增的那个" |

**结论**：netId **不能保证拿到** → **AT-3 是"尽力而为"等级**，拿不到就**降级到 AT-2**，**不得伪造**。

### 5.4 netId 复用 / 无法获得时如何降级？

- **拿不到 netId** → 最高 AT-2（**不允许 AT-3**）；
- **netId 复用**（系统回收旧值）→ 与"命令前记录"比对：**相同即视为旧连接** → AT ≤1；
- **冲突** → 降级 + 记录。

### 5.5 SUCCESS 四条件是否真能证明"本次候选建立的连接"？

**不能单独证明**。四条件（SSID+COMPLETED+IP+netId）中，**只有 netId 能区分"新连接 vs 旧连接"**；SSID/COMPLETED/IP 在"设备原本已连着同 SSID"时**全部可能为真**。

**因此 SUCCESS 依赖两道前置闸门**（现有代码已有）：
1. 命令前 `forgetTargetByShell`（删净目标配置 + 回读）；
2. 命令前若已连着目标 → 强制断开 + 确认。

**缺口**：**这两道闸门失败时，当前代码仍可能判 SUCCESS**。应改为：**闸门失败的 Attempt → 成功证据降级为 UNKNOWN**。

### 5.6 设备原本已连接相同 SSID 时如何防冒认？

```
命令前：记录 preNetId（status 的 Net ID）
若 status 显示已连着目标：
   → 强制断开 + 回读确认"不再连着"
   → 记录 preNetId 供后续比对
命令后：SUCCESS 要求 netId != preNetId（必须是新建立的连接）
       若 netId 不可得 → 降级 UNKNOWN（不冒认）
```

### 5.7 连接超时 / 底层仍活动 / 广播延迟时如何避免错误结案？

| 情形 | 现行为 | 应为 |
|---|---|---|
| 期限到·连接仍活动 | （wpalog 命中则）AUTH_FAIL | **INCOMPLETE** |
| 期限到·已终止无结论 | TIMEOUT | TIMEOUT ✅ |
| 期限到·状态不可确认 | UNKNOWN | UNKNOWN ✅ |
| 广播延迟（采集 > Attempt 结束） | 可能被当前轮采信 | **按发生时间划归**；不可判定则丢弃 |

**核心**：`adjudicate` 的 `act.stillActive()` 检查 + `frozen` 机制。

---

## 6. 涉及的源文件（预估）

| 文件 | 改动 |
|---|---|
| `audit/Evidence.java` | **新增**（证据结构 + AT/PP 枚举） |
| `audit/AttemptState.java` | **新增**（生命周期 + 冻结） |
| `audit/ConnectionActivity.java` | **新增**（status → Phase 解析，纯函数可测） |
| `audit/Adjudicator.java` | **新增**（唯一裁决入口，纯函数可测） |
| `audit/CrackEngine.java` | **改**：5 分支改投递证据；循环末调 adjudicate |
| `audit/SuccessRule.java` | **改**：接前置闸门状态 |
| `R10InvariantTest.java` | **改**：加严"禁止非裁决点构造终局 Verdict" |
| 各既有测试 | 契约更新（新增 INCOMPLETE 等） |

---

## 7. 迁移策略（避免一次性大重构）

| 步 | 内容 | 可回退 |
|---|---|---|
| **M1** | 新增 `Evidence/AttemptState/ConnectionActivity/Adjudicator`（**纯逻辑，不接线**）+ 单测 | 是 |
| **M2** | `adjudicate` 的 `stillActive` 检查 + INCOMPLETE；**先只接 wpalog 支B** | 是 |
| **M3** | 逐分支接线（failover → authcast → m1retry → 支A），每接一个跑全量 | 是 |
| **M4** | 接线完成，删除旧 `return`；门禁改为"禁止非裁决点构造终局" | 是 |
| **M5** | triedSet/cursor 改看 `persistable` | 是 |

**每步独立 commit + 全量门禁 + 出包**（按你的强制交付流程）。

---

## 8. 测试清单（对应你的 7 项 + A-J）

| # | 测试 | 期望 | 现状 |
|---|---|---|---|
| 1 | 延迟成功 1ms/1.2s/4.1s/6s/15s/19s | 全部 SUCCESS | 1 ✅ / 5 ❌ |
| 2 | ASSOCIATING + 零字段失败 | 不 AUTH_FAIL | ❌（#17 已证） |
| 3 | 期限到·连接仍活动 | INCOMPLETE | ❌（无该状态） |
| 4 | 连接终止·原因不明 | 非 AUTH_FAIL | 待测 |
| 5 | 本次充分成功不被旧失败压制 | SUCCESS | ✅（#9 修正后） |
| 6 | 旧 Attempt 延迟事件不污染新 | 新不受影响 | 待测（场景 A） |
| 7 | 真实充分错误证据 | AUTH_FAIL | ✅ |
| A–J | 见阶段 A 方案 §5 | — | 待建 |

---

## 9. 本轮不做

- 不改任何生产代码
- 不改固定窗口时长
- 不降级全 UNKNOWN
- 不动队列/UI/恢复
