# WPAForge 交接记录 · 1.9.69 续跑与主界面专项

> 本文件是**事实记录**，不是愿望清单。每条都标注证据等级：
> **[代码]** 源码可查 ｜ **[测试]** 自动化门禁通过 ｜ **[真机]** 设备上肉眼确认 ｜ **[推测]** 未验证

---

## 1. 仓库身份（2026-11-22 现场核实）

| 项 | 值 |
|---|---|
| 唯一正确开发仓库 | `/workspace/wpf169-work` |
| 分支 | `feat/1.9.69-resume-hardening` |
| 版本 | 1.9.69 (165) |
| 基于 | `b6656e8`（1.9.68 / 164，含 1.9.66/67/68 UI 改造） |
| 远端 | `origin` = GitHub `wlmosv-png/wpaforge`（经 `/tmp/wpaforge-recovery-20261121` 中转推送） |

### 禁止事项（踩过的坑）

- **不要用 `/workspace/promo-wpaforge`**：那是 1.9.60 老 UI 的旧克隆，分支
  `recovery/restore-v1.9.60`（HEAD `a09c22f`）。在它上面开发的续跑是**重复劳动**，
  其 UI 是旧的，绝不能移植回本仓库。
- **`/workspace` 与 `/data/local/tmp/eta` 是同一目录**（bind mount，dev/inode 相同），
  不是两份副本，不能互相"备份"。
- **不要 reset / clean / checkout 覆盖**：事故现场本身是取证材料。
- 恢复出来的测试源码（`196aee4` 等）是**按编译产物重建**的，不是逐字原始恢复。

---

## 2. 本阶段完成的功能

### 2.1 续跑（执行游标）

**[代码]** `TaskQueue.Task` 新增：

| 字段 | 语义 |
|---|---|
| `execIndex` | **执行游标**（P 坐标）：执行完一条就 +1，**与结论无关**。续跑起点用它。 |
| `recheckCsv` | 待复验队列（P 坐标，逗号分隔） |
| `phase` | 1 = 第一轮；2 = 复验轮 |
| `resumeSig` | 续跑状态签名（本阶段新增，见 2.3） |
| `excluded` | 本目标已排除条数（= TriedSet.size，累计；本阶段新增） |

`resumeFrom()`：`execIndex >= 0 ? execIndex : offset`（旧数据回退 offset）。

**为什么不能用 `offset` 当续跑起点**：`offset` 是「DIRECT 判定边界」，只有 DIRECT 才推进。
目标网络不给证据时（连续 TIMEOUT → NONE），`offset` 恒为 0，
用户点「继续」引擎真从第 1 条重跑，界面却写「已试 52 条」。
真机日志（2026-11-22 10:13）：「引擎点火：弹药 331 发」→「已暂停，进度 0/336」。

### 2.2 复验队列

**[代码]** `CrackEngine.run(..., int[] recheckSeed, int phase)`：

- 主循环每条候选后：`execNext = i + 1; onExecCursor(execNext)`
- 入队条件：`!a.isSuccess() && a.grade != DIRECT` → `recheck.add(i)`
- 出队条件：命中，或拿到 DIRECT（判错）
- **入队不等于判错**：不写 TriedSet、不进「已证实错误」
- 复验轮（phase 2）：对队列逐条重试，仍无结论的**留在队列**，下次继续

**[测试]** `ResumeRecheckTest` 45 项全过：
- E1：4 条全 NONE → `execCursor=4 recheck=[0,1,2,3] round2=4`（游标推进到底 + 全进复验 + 复验真跑）
- E5：`trace=[e0#r1, e1#r1, e2#r1, e0#r2] hit=e0`（**正确密码第一轮没中，复验命中**）
- E10：复验中途暂停 → 已试 1 条，其余 4 条仍留队

### 2.3 续跑状态有效性守卫（本阶段新增）

**[代码]** `audit/ResumeGuard.java`：签名 = 有序候选序列 + 目标键 + 强制重跑（FNV-1a 64）。

失效条件（任一变化 → 作废重建）：

| 变化 | 为什么必须失效 |
|---|---|
| 字典内容 | 旧下标指向别的候选 |
| 字典顺序 | 同上 |
| 目标派生 / 智能排序 / 线索 | P 空间排列变了 |
| 目标网络 | 跨目标串进度 |
| 强制重跑开关 | 过滤口径变了 |

失效动作：`execIndex=-1 / recheckCsv="" / phase=1 / offset=0` → 从头跑。
**安全保证**：已由 DIRECT 证实错误的仍在 TriedSet 被跳过；**尚未证实的一定重跑**。

**[测试]** `ResumeRecheckTest` G1–G11：内容/顺序/目标/重跑变化 → reset；
同签名不重置；游标越界 → reset；复验队列越界剔除 + 去重 + 升序；相位归一。

### 2.4 进度单调（本阶段修复）

**[代码]** `MainActivity.progressPair()`：

```
第一轮：done = execCursor,          total = dictSize
复验轮：done = dictSize + phaseDone, total = dictSize + phaseTotal
```

**修的 bug**：旧写法复验轮返回 `{phaseDone, phaseTotal}`，`phaseDone` 从 0 起
→ 第一轮刚跑完的 100% **掉回 0%**（用户看到「进度倒退」）。

**[测试]** `ResumeProgressTest` 12 项全过：游标单调、复验不重复计数、
复验总量口径、暂停恢复不倒退、命中即终态、多次恢复无爆炸重复。

---

## 3. 判定链（本阶段**一行未改**）

**[代码]** 语义边界：

| 等级 | 含义 | 可写 TriedSet | 可推 DIRECT 边界 |
|---|---|---|---|
| `DIRECT` | 有归属的直接否认（AT3） | ✅ | ✅ |
| `INFERRED` | 间接推断（M1 重发 / 回连等） | ❌ | ❌ |
| `NONE` | 什么都没证明 | ❌ | ❌ |

- `SUCCESS` 恒为 `DIRECT`（四条件同源于同一行 WifiInfo）
- `NETWORK_AUTH_FAIL` / `TIMEOUT` / `INCOMPLETE` / `TERMINATED_UNKNOWN` / `UNKNOWN`
  / `ERROR` 一律 `NONE`，**不写 TriedSet、不推边界**
- 证据不足、超时、连接未完成 → 进复验队列，**不判失败**

**必须守住的边界**：UI 把六种结果统一显示为「未命中」只是**展示层合并**，
底层 `Verdict` / `EvidenceGrade` / `TriedSet` 条件 / `cursor` 条件 / 复验队列**全部不变**。

---

## 4. UI 与进度

### 4.1 用户可见三格（1.9.69 用户口径）

| 显示 | 归类（仅 UI） |
|---|---|
| 破解成功 | `SUCCESS` |
| 未命中 | `AUTH_FAIL`(DIRECT/INFERRED) + `UNKNOWN` + `INCOMPLETE` + `TERMINATED_UNKNOWN` + `NETWORK_AUTH_FAIL` |
| 环境异常 | `ERROR` / `TARGET_GONE`（唯一需要用户处理） |

「已证实错误」并入「未命中」——对用户同义（这条密码不对）；技术上仍单独计数。

### 4.2 已修的真机问题

| 问题 | 根因 | 修复 |
|---|---|---|
| 未命中不涨（已试 45、未命中 7） | 界面用减法推导，无结论候选被「待复验」吃掉 | `VerdictTally` 新增 `noMatch` 独立桶，每条执行完 +1，经 `onTallyNoMatch` 实时下发 |
| 右侧大片留白 | 2×2 格用 `WRAP_CONTENT + weight` | 改 `MATCH_PARENT + weight=1`，三格一行 |
| 任务卡数字不实时 | 只在 `notifyQueue` 刷新 | `onCursorState` 内就地更新缓存 + `renderQueue()` |
| 进度 100% 掉回 0% | 复验轮用 `phaseDone/phaseTotal` | 改追加阶段口径（见 2.4） |
| 多字典计划残留 | 终态分支把 `p.summary()` 写进静态字段 | 三分支一律清空 + 终态作废残留 PENDING + UI 以 `usePlanMode` 为前提 |
| 「✓ 2」汇总条错位 | 横跨整行且与任务卡徽章重复 | 删除 |
| 任务卡「无结论…建议核对环境后重跑」 | 把正常未命中写成故障 | 改「这 N 条这次没拿到明确答复，会自动再试」 |

**[真机]** 已在设备上确认：未开始态（图标文字居中、比例正常）、任务卡文案、
「✓ 2」已消失、三格统计。

### 4.3 仍存在的视觉问题

- **[推测]** 窄屏 / 大字体下的三格布局未在真机验证（只跑过 1080×2376 @480dpi）。
- **[已知]** 九种状态里只真机确认了 3 种（未开始 / 历史未命中卡 / 历史已破解卡）；
  正在运行 / 暂停 / 复验轮 / 环境异常需要真跑破解才能产生，本轮禁止连 Wi-Fi 故未做。

---

## 5. 测试与门禁

**[测试]** 门禁：**9153 通过 / 15 失败 / 248 个测试类**。

15 条失败**全部是既有旧契约测试**（基线 16 条，`UiRefactorTest` 转绿），
它们钉死与本阶段新语义冲突的旧断言：

```
B3ResumeSkipTest           NONE 不复验
CandidateCursorResumeTest  尝试数=候选数
P0ResumeEndToEndTest       非 DIRECT 不中断循环
ResumeMatrixTest           cursor 只由 DIRECT 推进
P05NoExclusionTest         无结论不算试过
InfRetryRoundTest          NONE 不进第二轮
AdaptiveWindowTest         全 TIMEOUT 全 NONE
DictExecChainTest          每条恰好执行一次（与「未证实必须重跑」语义冲突）
CrackPageLayoutTest        旧文案/旧格位
CrackCountUnifyTest        结构串与 renderCountsLine 实现漂移
```

**这些一条都没改、也没放宽**。要转绿必须按新语义改写期望值（测试侧改动，
不动生产代码），且 `DictExecChainTest`「每条只执行一次」与验收标准
「未证实必须重跑」**语义不可兼得**，需产品决策。

---

## 6. 构建产物

| 项 | 值 |
|---|---|
| APK | `build/out/wpaforge-signed.apk` |
| 版本 | 1.9.69 (165) |
| 大小 | 1,822,143 B |
| SHA-256 | `3fe1c981c9d9dd8bef144b8ca01b62a14bb9ac7cda53629a73634dcefc1c0f22` |
| 签名证书 SHA-256 | `af5524cd554ae6ede3ec2747c7bed159b09bc4f6a43244a68b4622e9463225c8` |
| 构建脚本 | `1-aapt2.sh`（Android 侧 aapt2）+ `2-build-dex.sh`（Linux 侧 dex+签名） |

---

## 7. 下一阶段建议

1. **迁移那 15 条旧契约测试**（纯测试侧，需先定 `DictExecChainTest` 的语义取舍）。
2. **补真机九状态截图**：需允许连自己的网络跑短字典。
3. **窄屏 / 大字体**布局复核。
4. 若要把本分支合入 `main`，走 PR（`main` 现为 `db97138`，未被本分支改动）。
