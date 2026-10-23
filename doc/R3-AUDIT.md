# WPAForge 判定链审计报告 · 第 2 轮（R3：终结条件 / 事件归属 / 提前结案出口）

- 轮次目标：提高**结果分类的真实性、状态一致性、可追溯性**。不追求提高真实网络认证命中率。
- 本报告的性质：**审计 + 合成测试 + 接口契约**。生产判定逻辑**零修改**。
- 授权范围声明：全程只使用源码分析、合成字符串与桌面 JVM 单测。**未执行任何真实 Wi-Fi 认证尝试、未连接任何网络、未改动设备状态。**

---

## 1. 结论摘要

- **已证实问题（源码可证明）**：16 项，见第 6.1 节（R3-01 … R3-16）。其中 4 项属于「结论强于证据」的终态问题：
  - `E8 回连判据` 的护栏 `leftBaseline` 在常见环境下**第 1 轮即为真**，回连判据退化为「≥2.5 秒 + 别的网络 COMPLETED」两个条件；
  - 在「连接命令被静默丢弃 + 系统自行回连其它网络」这一组合下，候选**从未被尝试**却被记为 `AUTH_FAIL`，而专门的「未尝试 → ERROR」出口在该组合下不可达；
  - 看门狗到期且无任何失败证据时归类为 `AUTH_FAIL`（`Verdict.TIMEOUT` 已无生产者）——把「没有成功证据」当成「失败已被证明」；
  - 明文口令进入**持久化任务日志文件**（1 处生产调用点）。
- **机制已证实、触发频率待实测**：4 项（R3-T1…T4：清空失败率、广播跨候选污染、回连/静默漏判占比、120ms 复核是否够）。机制本身由真实函数跑出来；它们**发生多频繁属于设备侧事实，本轮未验证**。
- **高风险假设**：`logcat -c` 每次都成功。整套日志类判据（含成功与失败两侧）都建立在这个**从未被校验**的前提上。
- **测试强度缺口**：`VerdictFlowTest`（11 条断言）**不引用任何生产类**；`AttributionTest` 用本地常量 `MIN_HIT_MS = 3000` 而生产是 `0L`——这些断言无法发现生产代码的任何回归。
- **验收判定：`PARTIAL`**。约定范围内的静态与合成证据齐备并已存档；设备端现象（广播序列、日志配额、清空失败率）**未验证**，报告中一律标注为待实测。

---

## 2. 基线与方法

### 2.1 基线（改动前）

| 项目 | 值 |
|---|---|
| 仓库 | `/workspace/promo-wpaforge`（≡ `/data/local/tmp/eta/promo-wpaforge`） |
| HEAD | `10548684e2fc04780b15385e90a0b19ee343059b`（= round-1 的 R1 契约提交） |
| 工作区 | 干净；本轮**只新增 1 个文件**（`R3AuditProbe.java`，未提交），`git diff --stat` 为空 |
| 门禁基线 | `sh /workspace/wpf-gate.sh` → **1151 通过 / 0 失败**（站点链接门禁全通过） |
| 版本 | 1.9.24 / 94（本报告不涉及出包，版本号未动） |

存证目录：`/data/local/tmp/wp-review/round2/`

| 文件 | 内容 |
|---|---|
| `00-head.txt` | 基线 HEAD |
| `01-git-status.txt` / `01-git-diff-stat.txt` | 证明只新增 1 个探针文件、生产文件零修改 |
| `02-gate-round2.txt` | 本轮门禁输出（1151/0，且探针**未被门禁收编**） |
| `10-r3-probe.txt` | R3 合成探针完整输出（28 OK / 21 VIOLATION / 0 NOT-RUN） |

### 2.2 实际执行的命令

```sh
# 1) 编译（与门禁同一 classpath）
javac --release 17 -encoding UTF-8 -nowarn \
  -cp "$AJ" -d /workspace/r3probe/classes @/workspace/r3probe/src.txt
# 2) 报告模式（退出码 0，只报不改）
java -cp "$OUT:$AJ" io.github.wlmosv_png.wpaforge.R3AuditProbe /workspace/promo-wpaforge
# 3) 严格模式（有 VIOLATION 即退出 1，供将来接门禁用）
java -cp "$OUT:$AJ" io.github.wlmosv_png.wpaforge.R3AuditProbe /workspace/promo-wpaforge --strict
# 4) 全量门禁（证明没有把红灯混进来）
sh /workspace/wpf-gate.sh
```

### 2.3 诚实边界：三类证据必须分开读

探针 `R3AuditProbe` 把每条结论的来源标成三类，报告沿用同一口径：

| 标记 | 含义 | 可信度 |
|---|---|---|
| **EXECUTED** | 直接调用生产代码里的纯逻辑函数（`FailoverRule` / `SuccessRule` / `WpaLogReader` / `StatusParser` / `SupplicantParser` / `WifiAuthFailureWatcher` 静态判据 / `CrackEngine` 静态判据 / `Redact`） | 结论是跑出来的 |
| **SCANNED** | 对源码文本做的静态计数（如「明文口令进日志的调用点数量」） | 结论是数出来的 |
| **TRANSCRIBED** | `Loop` 是 `CrackEngine.tryOneShell` 判定顺序的**人工转写**（每步标了源码行号），不在生产路径上 | **模型推断**，报告里逐条标注 |

**为什么必须转写而不能直接跑**：`CrackEngine` 依赖 Android（`Context`/`WifiManager`），桌面 JVM 无法实例化。判定顺序与阈值语义都写在 `tryOneShell` 的方法体里，**没有抽成纯函数**。这是 R3 修复本身要解决的第一个结构问题（见第 7 节契约）。

**为什么探针不叫 `*Test.java`**：`wpf-gate.sh` 用 `find -name '*Test.class'` 收测试类。本探针刻意沿用当前行为、把契约违背项标成 `VIOLATION` 而不判红。它现在的任务是「把缺陷变成可复现的证据」，不是「维持绿灯」。修复落地时这些用例才逐个转成 `R3ContractTest.java` 的断言。

---

## 3. 回连相关：事件来源、处理顺序、终态转换

### 3.1 事件来源清单（4 条通道）

| 来源 | 采集方式 | 节奏 | 事件内容 | 时间戳 | 归属手段 | 参与判定的出口 |
|---|---|---|---|---|---|---|
| **S1 status** | `cmd wifi status`（shell） | 每轮 20ms（前 1.2s）/ 80ms | 首条 `WifiInfo` 行的 ssid / state / ip / netId | 采样时刻＝轮次时刻 | **无**（与候选无绑定） | `E7` 成功、`E8` 回连、`E15/E18` 分类、`currentSsid`/`currentNetId` |
| **S2 日志** | `logcat -d -s wpa_supplicant \| grep -iE <7 模式>` | 每轮 | associate / M1 / negotiated / handshake_failed / assoc_reject / wrong_key / CONNECTED | **无**（`LogEvent.at` 恒 0，且全仓无消费点） | 仅靠 `logcat -c` 的缓冲边界 + ssid/bssid 文本 | `E9` 成功、`E11` m1silent、`E12` m1retry、`E13` 失败行 |
| **S3 广播** | `SUPPLICANT_STATE_CHANGED_ACTION`（framework） | 事件驱动 | `ERROR_AUTHENTICATING` + 收到时的 `WifiInfo` SSID | 有（uptimeMillis，**只保留最近一次**） | 时刻 > `commandAt` + 可选 SSID 文本 | `E10` authcast |
| **S4 回调** | `ConnectivityManager.NetworkCallback` | 事件驱动 | ssid + available/lost | 有（uptimeMillis，保留 200 条，run 级 clear） | 时刻 > `commandAt` | **不参与判定**（仅诊断显示 + 无调用者的 `verifyHit`） |

关键事实（源码行号）：

- `WpaLogReader.read()`（`:1165` 调用）读的是 **整个 `logcat` 缓冲**，注释自述「调用方在每条候选前已 `logcat -c`，缓冲里就是本次尝试的日志」。缓冲隔离是**唯一**的归属机制。
- `WifiStateLog.clear()`（`audit/WifiStateLog.java:191-196`）返回 `void`、异常被吞；调用点 `CrackEngine:865` 同样 `catch (Throwable ignored) {}`。**清空是否成功从未被校验。**
- `WifiAuthFailureWatcher` 只有 `lastErrorAt` / `lastErrorSsid` 两个 volatile：无队列、无候选维度。
- `Verdict.TIMEOUT` / `Verdict.TARGET_GONE` 在**全仓已无生产者**（仅测试引用），而 `run()` 仍在判 `TIMEOUT`（死分支）。

### 3.2 一条候选内部的处理顺序（每轮）

判定顺序即优先级。多出口在同轮竞争时，**先算出来的赢**：

```
轮次开始
 ① 取消/暂停检查 ................................ CrackEngine:993  → ABORTED (E6)
 ② 前 4 轮探测本条命令是否建出配置(targetNetId) ... :1005
 ③ 自适应 sleep 20ms / 80ms ..................... :1016
 ④ cmd wifi status .............................. :1022  → channelAlive / statusRounds
 ⑤ 目标进过 status = sawAttempt + 看门狗 +4s ..... :1033-1046
 ⑥ netId 不符（只记录，不否决） .................. :1060-1079
 ⑦ 判据 A：SuccessRule.isSuccess → SUCCESS ....... :1095  (E7)   ← status 源
 ⑧ 回连判据：onOtherNetwork / leftBaseline / FailoverRule
        → AUTH_FAIL "failover" ..................... :1125-1138 (E8)  ← status 源 · 中间事件
 ⑨ logcat 读 + 关联行 + M1(看门狗 +2.5s) .......... :1165-1195
 ⑩ 判据 B：sawNegotiatedFor(目标 BSSID) → SUCCESS . :1190  (E9)   ← 日志源
 ⑪ 广播认证失败（+120ms 复核）→ AUTH_FAIL ......... :1215-1235 (E10) ← 广播源
 ⑫ M1 后静默 ≥1200ms → AUTH_FAIL "m1silent" ...... :1255-1270 (E11) ← 时间判据
 ⑬ M1 重发 ≥2（+120ms 复核）→ AUTH_FAIL "m1retry" . :1280-1295 (E12)
 ⑭ wrong_key / handshake_failed / assoc_reject → AUTH_FAIL :1297-1305 (E13)
 ⑮ 未到 deadline → 回到 ①
deadline 到期 → 循环后分类（:1320-1366）
```

**注意 ⑦→⑧→⑩ 的次序**：`status` 段（含 `E8`）整体排在「本轮 `logcat` 内容被读取」之前。也就是说，**同一轮里 `E8` 可以先宣判，而这一轮的日志证据还没被看过**。

### 3.3 候选级终态转换图（全部 19 个出口）

```
开始
 ├─ 口令长度非法 (>63 / <8) ................. E1  → AUTH_FAIL  :757   [命令前]
 ├─ 无 shell 通道 ........................... E2  → ERROR      :791
 ├─ forgetTargetByShell 失败 ................ E3  → UNKNOWN    :801
 ├─ 旧配置仍在 (staleNetId ≥ 0) ............. E4  → UNKNOWN    :818
 ├─ 上一条连接断不掉 ........................ E5  → UNKNOWN    :855
 └─ 发命令 commandAt (:869) ─► 循环
      ├─ 取消/暂停 ......................... E6  → ABORTED    :997
      ├─ 三条件同行(SSID+COMPLETED+有效IP) .. E7  → SUCCESS    :1102  [充分证据]
      ├─ 系统连上别的网络(COMPLETED) ........ E8  → AUTH_FAIL  :1135  [中间事件·归属弱]
      ├─ 日志: 目标 BSSID 协商完成 .......... E9  → SUCCESS    :1195  [充分证据]
      ├─ 广播: 认证失败(+120ms 复核) ........ E10 → AUTH_FAIL  :1227  [中间事件·归属弱]
      ├─ M1 后静默 ≥1200ms ................. E11 → AUTH_FAIL  :1263  [纯时间]
      ├─ M1 重发 ≥2 (+120ms 复核) .......... E12 → AUTH_FAIL  :1286  [计数+时间]
      ├─ wrong_key / 失败行 ................ E13 → AUTH_FAIL  :1299  [证据·依赖缓冲隔离]
      └─ 看门狗到期 → 分类
            ├─ 配置没建出 + 未见尝试 + 通道活 ... E14 → ERROR    :1331  [未尝试]
            ├─ 结束时仍显示目标 ............... E15 → UNKNOWN  :1341
            ├─ 见过目标已连但 netId 不符 ...... E16 → UNKNOWN  :1349
            ├─ 未见尝试 + 通道死 .............. E17 → UNKNOWN  :1355
            ├─ 通道活 + status 显示目标 ....... E18 → UNKNOWN  :1362
            └─ 其它（无证据） ................. E19 → AUTH_FAIL :1365  [纯时间·无证据]
```

**不看门狗就到期的「提前结案」出口**：`E7` `E8` `E9` `E10` `E11` `E12` `E13`。其中 `E8`/`E10` 是被当作终态使用的**中间事件**，`E11`/`E19` 是**时间判据**。

### 3.4 任务级终态

| 出口 | 条件 | 结果 | 位置 |
|---|---|---|---|
| 通道自检未过 | `probeChannel().ok == false` | `onBlocked` → `STOPPED`（任务放弃） | `CrackEngine:1606` / `CrackService:onBlocked` |
| 命中 | `hit != null`（唯一赋值点受 `if (a.isSuccess())` 守卫） | `break` → `DONE_HIT` | `CrackEngine:1794-1801` |
| 暂停 | `pauseFlag` | `PAUSED` | `CrackEngine:1866` |
| 取消 | `cancelFlag` | `STOPPED` | `CrackEngine:1867` |
| 跑完未命中 | 其它 | `DONE_MISS` | `CrackEngine:1868` |

「终态唯一」在任务级成立（`hit` 赋值点唯一、受 `SUCCESS` 守卫；`finishTask` 只认非空命中口令）。这条不变量**未被破坏**（`S5` 已跑通）。

---

## 4. 合成测试矩阵（R3）

探针用例（全部为合成字符串，无任何真实网络交互）。「实际」一列直接抄自 `10-r3-probe.txt`。

| Case | 合成场景 | 独立预期（契约） | 实际 | 状态 |
|---|---|---|---|---|
| S1-a | status 三条件同行 | 可同行取得 | `ssid/state/ip/netId` 全取到 | PASS |
| S1-1 | 1 参 `parse()` + 全仓扫描 `.at` 消费点 | 事件可排序 | `at` 恒 0 且无生产者消费 | **VIOLATION** |
| S1-2 | 广播源结构 | 事件可归属到候选/任务 | 只有「最近一次」两个 volatile | **VIOLATION** |
| S1-3 | `firstAvailableAfter` 调用点 | 说明文字与代码一致 | 只在无调用者的 `verifyHit` 里 | **VIOLATION** |
| S1-4 | 全仓 `attemptId/runId` | 存在任务关联标识 | 0 命中 | **VIOLATION** |
| S2-a…e | `FailoverRule` 边界（5 例） | 见 `FailoverTest` 契约 | 全部符合 | PASS |
| S2-1 | 命令时未连接 → `leftBaseline` | 应表示「真的离开过原连接」 | 第 1 轮即 `true` | **VIOLATION** |
| S2-2 | 命令被丢弃 + 3s 后回连自家网 | 应归「未尝试」 | `AUTH_FAIL(failover)@3000ms` | **VIOLATION** |
| S2-3 | 同轮同时含回连证据与目标协商完成日志 | 成功证据优先 | `AUTH_FAIL(failover)` | **VIOLATION** |
| S3-a…c | 陈旧缓冲喂真实判据（3 例） | — | 三个判据全部成立 | PASS（机制证据） |
| S3-d | 空日志喂全部日志判据 | 全部返回 false | 全部 false | PASS（同时是 S3-3 的证据） |
| S3-1 | 缓冲隔离前提 | 前提应被校验 | `clear()` 无返回值、异常被吞 | **VIOLATION** |
| S3-2 | 陈旧缓冲残留 wrong_key / 两次 M1 | 不得在第 1 轮判负 | 第 1 轮即满足判据 | **VIOLATION** |
| S3-3 | 空日志 vs 事件未发生 | 两者可区分 | 解析结果完全同形 | **VIOLATION** |
| S4-a…c | `m1SilentImpliesWrongKey` + `sawNegotiatedFor(空 bssid)` | 语义正确、可被成功证据压制；空 BSSID 时护栏失效 | 符合 | PASS |
| S4-1 | 成功证据比静默阈值晚 100ms 到 | 不得据此判负 | `AUTH_FAIL(m1silent)@1650ms` | **VIOLATION** |
| S4-2 | 看门狗到期 + 无失败证据 | 应归 TIMEOUT/UNKNOWN | `AUTH_FAIL@10000ms` | **VIOLATION** |
| S4-3 | `Verdict.TIMEOUT` 生产者 | 应有生产者 | 无（死分支 + 死枚举） | **VIOLATION** |
| S5-a | SUCCESS 后仍有迟到事件 | 终态不被覆盖 | 保持 SUCCESS | PASS |
| S5-b | `hit` 赋值点唯一（+ 受 `isSuccess` 守卫检查） | 唯一 | 1 处，守卫存在 | PASS |
| S5-3 | 提前结案的候选计入已试 | 未尝试不得计为已试 | `tested++` 在 `tryOne` 之前 | **VIOLATION** |
| S6-a…b | 广播判据真实函数 | 旧事件被采纳、SSID 缺失不过滤 | 与预期一致 | PASS（机制证据） |
| S6-1 | 广播归属维度 | 应含候选/任务标识 | 仅时刻 + 可选 SSID | **VIOLATION** |
| S6-2 | 日志通道隔离单位 | 应为候选 | 为「缓冲」，且前提未校验 | **VIOLATION** |
| S6-c | 对照：NetworkWatcher | 有 run 级 clear + 时间戳 | 有 | PASS |
| S7-a…d | `Redact` 标签 / 判词 / 通知栏 | 稳定、不含明文 | 符合 | PASS |
| S7-1 | 明文口令进 `onLog` | 禁止 | **生产 1 处**（`CrackEngine:1795`） | **VIOLATION** |
| S7-2 | `HistStore` CSV + 导出 | 默认脱敏 | 明文（现为「结果展示」豁免） | **VIOLATION** |
| S7-3 | `queue.json` 命中口令 | 默认脱敏 | 明文 | **VIOLATION** |
| S8-1 | 分层摘要「事件N」 | 数字真实 | `watcherHits` 无自增点，恒 0 | **VIOLATION** |
| S8-2 | watcher 与判定 | 说明与代码一致 | 判定已不读 watcher | **VIOLATION** |
| S8-3 | 连续超时告警 | 可达 | 依赖已无生产者的 `TIMEOUT`，不可达 | **VIOLATION** |

汇总：**28 OK / 21 VIOLATION / 0 NOT-RUN**（报告模式退出码 0；`--strict` 退出码 1，已验证）。

### 4.1 乱序 / 迟到 / 重复 / 旧任务 / 缺日志 五维覆盖

| 维度 | 用例 | 结论 |
|---|---|---|
| 乱序 | S2-3（同轮内 status 结论先于日志证据） | 顺序本身可证明；**触发概率未实测** |
| 迟到 | S4-1（成功证据晚于静默阈值）、S5-a（SUCCESS 后迟到事件被忽略） | 前者是漏判窗口；后者不变量成立 |
| 重复 | S3-a/S3-2（重复 M1 → 计数达标 → 判负） | 计数判据对重复事件无幂等保护 |
| 旧任务 | S6-1/S6-2（广播与缓冲都缺任务边界） | 归属维度缺失（与 R6 同源） |
| 缺日志 | S3-3（缺失与未发生同形）、S4-2（无证据判 AUTH_FAIL） | 两种不同事实被压成同一个终态 |

---

## 5. 建议的接口契约（仅设计，本轮未实现）

### 5.1 判定核心抽纯函数（R3 的前置结构改造）

现状：`tryOneShell` 里「顺序 + 阈值 + 出口」耦合在 Android 方法体内，无法离线验证，只能转写。建议把**决策**抽成纯函数：

```java
public final class CandidateJudge {
    /** 一次轮询的全部输入（全部为纯数据） */
    public static final class Frame {
        public long tSinceCommandMs;
        public long deadlineMs;
        public String statusSsid, supplicantState, ip;
        public long statusNetId;         // -1 = 未知
        public long targetNetId;         // -1 = 未知（注意：未知不得否决）
        public boolean logChannelReadable;
        public boolean logBufferIsolated; // 见 5.2：起点标记可见
        public boolean sawTargetAssociate, sawTargetNegotiated;
        public int targetM1Count;
        public long firstM1AtMs;
        public boolean authErrorSinceCommand;
        public String authErrorSsid;
        public boolean leftPreCommandLink; // 必须由「观测到离开」直接得出
    }
    /** 返回 null = 本轮无终态，继续；返回非 null = 本条候选的终态 */
    public static CrackEngine.Attempt decide(Frame f, String targetSsid, String targetBssid);
}
```

收益：每个出口都能用一张表和一组合成 `Frame` 钉死；`tryOneShell` 只剩采集。

### 5.2 attemptId / runId 生命周期与传递

| 项 | 设计 |
|---|---|
| `runId` | `run()` 起始生成一次：`启动墙钟 + 目标 SSID 的 sha256 前 4 + 递增序号`。只活在本次任务（不写盘） |
| `attemptId` | `runId + "#" + 字典序号`（序号与判词里的顺序号同源，便于对回字典） |
| 生成 | `run()` 的 `for` 循环里，`tryOne` 之前 |
| 传递 | `run() → tryOne(..., AttemptCtx) → tryOneShell(..., AttemptCtx) →` 所有判据函数；`AttemptCtx` 里带 `attemptId` + `commandAt` |
| 落地 | ① 每条 `Attempt.confirmedBy` 追加 attemptId；② 「最近判定」尾巴与逐条结果的 `detail` 里带 attemptId；③ 广播 watcher 维护 `activeAttemptId`（收到广播时取当前值） |
| **起点标记** | `logcat -c` **之后**立刻写一行自造 tag（如 `log -t WPF_PROBE attempt=<id>`）。读日志时若**找不到**该行 → 「缓冲未清空 或 读取不完整」→ 该候选的**全部日志证据标记为不可信，禁止据此判负** |

`attemptId` **能证明**：
- 某条证据是否属于本次候选（不再靠「时刻 > 命令」这种弱条件）；
- 日志缓冲是否真的被隔离（起点标记是**可验证的存在性证据**，把 `logcat -c` 从假设变成事实）；
- 事后复盘时「哪几条候选根本没被尝试过」。

`attemptId` **不能证明**：
- 证据本身是真的（系统报什么就是什么，我们无法独立复算握手）；
- 「没看到事件」＝「事件没发生」——**这一点必须由缺失标记单独表达**，不能让 attemptId 假装解决了它；
- 它救不了「结论强于证据」的分类问题（`E8`/`E19` 该改的是判据本身，不是加 ID）。

### 5.3 终态语义表（建议）

| 终态 | 含义 | 允许的进入条件（建议） |
|---|---|---|
| `SUCCESS` | 已证明密码正确 | 三条件同行（`E7`）或目标 BSSID 协商完成（`E9`） |
| `AUTH_FAIL` | **已证明**密码错误 | 仅 `E10`(归属成立) / `E12` / `E13`(归属成立)；`E8` 与 `E11` 需先满足 5.4 的护栏 |
| `UNKNOWN_MISMATCH` | 见过目标已连，但归属不成立 | `E16`（沿用 round-1 决策 1） |
| `TIMEOUT_NO_EVIDENCE`（新增） | 等满时限、**无任何失败证据** | `E19` 改归这里 |
| `NOT_ATTEMPTED`（新增） | 命令未生效、本条从未被尝试 | `E14` |
| `ABORTED` / `ERROR` | 用户中断 / 通道异常 | 现状不变 |

`UNKNOWN` / `TIMEOUT_NO_EVIDENCE` / `NOT_ATTEMPTED` **一律不得被 UI、队列或导出映射为成功**（现状已满足，需在转换后加回归）。

### 5.4 出口护栏（建议的最小改动方向）

| 出口 | 建议护栏 |
|---|---|
| `E8` 回连 | 只在「本次已观测到目标关联/握手行」**或**「已观测到离开命令前那条连接（直接证据，不是 `!onOtherNetwork`）」时才允许判负；否则只记录、不结案 |
| `E11` 静默 | 降级为**加速提示**：只有在另有归属成立的失败证据时才允许结案；单独不结案 |
| `E19` 到期 | 改归 `TIMEOUT_NO_EVIDENCE`，不再写成 AUTH_FAIL |
| 日志类判据 | 受 5.2 的「起点标记可见」保护；不可见即不得判负 |
| 同轮优先级 | ⑦→⑧ 之间插入「先取日志帧」：保证同一轮内成功证据与失败证据同时到达时，成功优先 |

---

## 6. 问题登记：源码可证明 vs 推测

### 6.1 源码可证明（静态或真实函数跑出）

| ID | 问题 | 证据位置 | 分类 |
|---|---|---|---|
| R3-01 | `E8` 回连判据的 `leftBaseline` 在「命令时未连接」时第 1 轮即为真，护栏空转 | `CrackEngine:1125-1133` + 探针 S2-1 | 结论强于证据 |
| R3-02 | 命令被丢弃 + 系统回连其它网络 → 候选从未尝试却记 `AUTH_FAIL`，`E14` 不可达 | `:1135` vs `:1325` + 探针 S2-2 | 结论强于证据 |
| R3-03 | 同轮内 `E8`(AUTH_FAIL) 优先于 `E9`(SUCCESS) 判定 | `:1125` 在 `:1165` 之前 + 探针 S2-3 | 优先级 |
| R3-04 | 日志通道隔离（`logcat -c`）的结果从未校验，而全部日志判据依赖它 | `:865` + `WifiStateLog:191-196` + 探针 S3-1 | 前提未验证 |
| R3-05 | 陈旧缓冲可在第 1 轮直接满足 wrong_key / m1retry → 立即判负 | 探针 S3-a/S3-2（真实函数） | 归属缺失 |
| R3-06 | 「日志缺失」与「事件未发生」不可区分 | 探针 S3-3 | 分类诚实 |
| R3-07 | 到期且无证据 → `AUTH_FAIL`；`Verdict.TIMEOUT` 无生产者（死分支 + 死枚举） | `:1365`、`:1835`、grep | 结论强于证据 |
| R3-08 | 广播归属只有「时刻 > 命令」+ 可选 SSID，无候选/任务维度 | `WifiAuthFailureWatcher` + 探针 S6-1 | 归属缺失 |
| R3-09 | `m1silent` 是经验阈值且一到就结案，成功证据晚到即被吞（漏判窗口） | `:225`/`:1263` + 探针 S4-1 | 时间判据 |
| R3-10 | 提前结案的候选仍计入 `tested` / 进度 | `run():1725-1727` + 探针 S5-3 | 可追溯性 |
| R3-11 | 分层摘要「事件N」恒 0（`watcherHits` 无自增点），且 watcher 已不参与判定而日志称「判定完全依赖系统回调」 | `:953`/`:1378`/`:1586` + 探针 S8-1/S8-2 | 诊断失真 |
| R3-12 | 连续超时告警为死代码 | `:1835` + grep | 诊断失真 |
| R3-13 | **明文口令进入持久化任务日志文件**（1 处生产调用点） | `:1795` → `CrackService.onLog` → `log()` → `appendFileLog` | 脱敏（决策 4） |
| R3-14 | 日志事件无时间戳且全仓无消费点 → 无法排序、无法归属 | 探针 S1-1 | 可追溯性 |
| R3-15 | 测试强度：`VerdictFlowTest`（11 条断言）**零 import 生产类**；`AttributionTest` 本地 `MIN_HIT_MS=3000` 而生产是 `0L`（`@Deprecated` 未用） | `VerdictFlowTest.java`（无 import）、`AttributionTest.java:93` vs `CrackEngine:136` | 测试质量 |
| R3-16 | 全仓无 `attemptId/runId`（0 命中） | grep + 探针 S1-4 | 可追溯性（= R6） |

### 6.2 机制已证实 / 触发频率待实测（**不得写成事实**）

| ID | 待实测的问题 | 现有证据 | 需要的证据 |
|---|---|---|---|
| R3-T1 | `logcat -c` 实际失败或部分失败的概率 | 机制已证（前提未校验 + 污染后果可跑出） | 真机上对同一候选连续采集「清空后读回」的样本，统计起点标记缺失率 |
| R3-T2 | 广播跨候选/跨任务污染的实际发生 | 判据结构已证（无候选维度） | 真机广播序列 + 候选时间线对照 |
| R3-T3 | `E8`/`E11` 造成的漏判在真实日志里的占比 | 时序窗口已证 | 历史任务日志统计（`未连上（已尝试）` / `m1silent` 判词与前后候选对照） |
| R3-T4 | `E10` 的 120ms 复核在慢设备上是否够 | 顺序已证 | 真机时间戳分布 |

### 6.3 明确未做的判定

- 未评估「修 R3 会不会降低真实命中率」。本轮**不追求**命中率，阈值与判据取舍留给修复轮用数据决定。
- 未验证任何设备侧现象；报告中所有「真机」字样仅出现在**引用既有注释**时，并标注了出处。

---

## 7. R3 与 R1 / R2 / R6 的依赖关系

```
        ┌──────────────────────────────────────────────┐
        │ R6 runId/attemptId（归属骨架 + 日志起点标记） │
        └───────────────┬──────────────────────────────┘
                        │ 前置：没有它，任何「本次是否见过 X」的护栏都无法表达在证据上
        ┌───────────────▼──────────────────────────────┐
        │ R3 出口优先级 + 归属护栏                      │
        │  · E8 加「本次见过目标关联」前置              │
        │  · 同轮内成功证据优先                         │
        │  · 日志证据受起点标记保护                     │
        └───────┬───────────────────────┬──────────────┘
                │                       │
   ┌────────────▼─────────┐   ┌─────────▼────────────────┐
   │ R1 netId 否决         │   │ R2 静默/到期判据          │
   │ 仅在「netId 与本次    │   │ 「等待期限已到」与        │
   │ 新建配置同源」时可比； │   │ 「业务失败已证实」必须    │
   │ netId ≤ 0 不比较      │   │ 分成两个终态，再谈阈值     │
   └───────────────────────┘   └───────────────────────────┘
```

- **R6 → R3**：R3 的三条主修复（回连护栏、日志可信标记、旧事件边界）都要一个「属于本次」的标识。R6 不是可选项，是 R3 的前置。
- **R3 → R1**：`netId` 只有在「这条配置是本次命令建的」可证时才有资格否决。现状 `targetNetId` 是「命令后前 4 轮探测到的值」，误差窗口大；有了 attemptId + 起点标记后，`R1` 的否决条件才能写严而不误杀（与 round-1 决策 1/2 一致：`netId ≤ 0` 不比较）。
- **R3 → R2**：`E19`/`E11` 的语义先分清（`TIMEOUT_NO_EVIDENCE` vs `AUTH_FAIL`），R2 的阈值讨论才有意义；否则调阈值只是在两个语义混在一起的口子上挪数字。
- **R6 → R7**：`RUNNING` 陈旧归一化需要知道「上一次跑到哪条候选」，同样是 attemptId 的消费方。
- **R10**（乱序/迟到/重启合成矩阵）依赖前三者定型后再补，否则矩阵会随判据变动反复重写。

---

## 8. 建议的实现顺序（**待确认后执行**）

| 步 | 内容 | 验收要点 |
|---|---|---|
| 1 | R6 骨架：`AttemptCtx` + 日志起点标记 + 尝试 ID 落进判词与逐条结果 | 门禁 1151→N 全绿；行为不变；输出多一列 |
| 2 | 判定核心抽 `CandidateJudge` 纯函数（行为不变，纯搬运） | 逐出口对照表；`R3ContractTest` 先红后绿 |
| 3 | R3 出口：`E8` 护栏、同轮成功优先、日志证据可信标记、`E19→TIMEOUT_NO_EVIDENCE`、`E14→NOT_ATTEMPTED` | 探针的 21 项 VIOLATION 逐项转 PASS |
| 4 | R1：netId 否决（两侧 > 0 且同源） + `UNKNOWN_MISMATCH` | round-1 契约的 F1–F5 漏判风险表逐条复核 |
| 5 | R2：静默降级为加速提示；到期语义拆分后的阈值取值用数据定 | 不为了命中率调阈值 |
| 6 | R7 陈旧 `RUNNING` 归一 + R10 合成矩阵补全 | 进程重启/乱序/迟到用例 |

每一步都按用户给的执行纪律：存基线 → 先建可复现合成用例 → 明确预期与依据 → 只做必要改动 → 跑全量并保存输出 → 审 diff（不得删断言/降强度）→ 输出未覆盖风险。

---

## 9. 未覆盖风险

1. **无设备端验证**：广播序列、`logcat` 配额表现、清空失败率、时间戳分布全部未实测（本轮范围限定）。
2. **转写模型可能与生产实现有偏差**：`Loop` 是人工转写。若发现偏差，**以源码为准**；报告已为每步标注行号以便逐条复核。
3. **性能未评估**：起点标记会让每条候选多一次 shell 调用（现约 20ms/次）。修复轮需要实测每条候选的耗时变化。
4. **`E13` 的归属仍依赖缓冲隔离**：即使加了起点标记，「无归属的失败行」（`4-Way Handshake failed` / `ASSOC-REJECT`）仍靠 `tried`（本次见过目标关联行）做前置；题材本身没有更强归属信息，这是**协议层面的上限**，不是能修掉的缺陷。
5. **`UNKNOWN` 偏多带来的体验**：修完 R3 后 `AUTH_FAIL` 会减少、`UNKNOWN/TIMEOUT_NO_EVIDENCE` 会增加。这是「诚实优先」的必然代价，需要同步调整界面文案，否则用户会读成「工具坏了」。此项本轮未评估。

---

## 10. 脱敏检查（决策 4 / 5）

| 面 | 现状 | 结论 |
|---|---|---|
| 逐条结果（UI 流） | `Redact.pwAt` | 已脱敏 |
| 正在试（ticker） | 明文候选（结果展示） | 需 opt-in 契约（S7 系列） |
| 最近判定（文件日志尾巴） | `verdictTag` → `Redact.pwAt` | 已脱敏 |
| **命中日志行** | **明文**（`CrackEngine:1795` → 文件日志） | **违规，需修** |
| 通知栏 | `Redact.pw` | 已脱敏 |
| 命中卡片 | 明文（结果展示） | 需 opt-in |
| 破解记录 CSV / 导出 | 明文 | 需 opt-in |
| `queue.json` `hitPassword` | 明文 | 需 opt-in |
| 异常 / 调试输出 | `Log.i` 只打口令**长度** | 未发现明文 |
| IP 有效性定义 | `SuccessRule` 只排除空与 `0.0.0.0`；`SupplicantParser` 只接受 `[0-9.]`，IPv6 形态的地址会被解析成 `null` | 见下 |

**关于 IPv6（决策 5）**：现状**不是**「把链路本地地址当无效 IP」，而是**解析器根本不解析 IPv6**——`SupplicantParser.parseWifiInfoLine` 在读 `IP: ` 之后只接受数字与点，`fe80::1` 会得到 `ip == null`。因此「格式有效性 / 接口可用性 / 业务证据充分性」三者当前**没有分开定义**：一个格式合法的 IPv6-only 地址会被静默降级为「没有 IP 证据」→ 三条件不成立 → 不判成功（漏判向，不会误判）。建议在修复轮把三件事拆开：格式（解析层）、接口是否可用（`IP: <none>` / `0.0.0.0` 之外的地址是否存在）、业务证据是否充分（是否拿到地址 + 握手完成）。

---

## 11. 验收判定

**`PARTIAL`**

- 已通过：静态证据（源码可证明项 16 条）、合成用例（30 条断言，21 项契约违背已复现）、可复现命令与输出存档、门禁未被污染（1151/0）。
- 仍有缺口：设备端现象未验证（4 项待实测）、性能影响未评估、转写模型的偏差未交叉验证。
- **R1 / R3 / R2 的生产判定逻辑一行未改**；本轮结论中没有任何一条被表述为「已修复」。
