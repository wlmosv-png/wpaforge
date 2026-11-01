# P0 持久化可靠性工程设计 · v3

**状态**：设计稿 v3，待审查。**零生产代码改动。**
**基线**：`344981f`（1.9.32/128）
**历史**：v1 = `2da269c`，v2 = `ec35967`
**本版变更**：按 M4 最终裁定改为**保守方案（QUARANTINED）**，补齐 M4 完整状态转换、文件矩阵与测试矩阵。

---

## §0 M4 裁定（本版核心）

### 0.1 触发条件

**仅凭"`base` 与 `<base>.bak` 同时存在"这一事实本身**，即触发保守隔离，**不尝试判断哪份更新或更完整**。

理由（记录在案，防止后人"优化"回去）：

1. 旧版 `TaskQueue.save()`（`TaskQueue.java:406`）在清空队列时会**手写 `queue.json.bak`** —— 这份文件可能是**清空前的唯一有效数据备份**。
2. `AtomicFile` 会把同名 `.bak` 视为**它自己的事务备份**，并在 `openRead()` 时**用它覆盖 base**（§0.4 实测）。
3. 仅凭两份文件同时存在，**无法可靠判断**哪一份是最新、完整、应该使用的数据。

### 0.2 命名冲突面（已核实）

| 文件 | 产生方 | 是否与 AtomicFile 的 `<base>.bak` 冲突 |
|---|---|---|
| `queue.json.bak` | `TaskQueue.java:406`（旧版手写） | **✅ 冲突（M4 根因）** |
| `queue.json.plain.bak` | `PlaintextMigration:38` | ❌ 后缀不同 |
| `history.csv.plain.bak` | `PlaintextMigration:37` | ❌ 后缀不同 |
| `restore.json.bak-<ts>` | `RestoreStoreFile:119` | ❌ 后缀不同 |

**结论**：只有 `TaskQueue` 这一处与 AtomicFile 争抢 `.bak`。

### 0.3 M4 强制行为

检测到 `base` + `.bak` 同时存在时：

```
① 禁止调用 AtomicFile.openRead()          ← 实测：写事务会**删除**同名 .bak
                                            （见 doc/ATOMICFILE-EVIDENCE.md 场景 A）
② 禁止自动删除 / 覆盖 / 合并 / 选择任一份
③ 先持久化该 Store 的 QUARANTINED 标记    ← 必须最先完成
④ 标记成功后，分别保留并隔离 base 与 .bak
⑤ 记录两份的：原始路径、大小、SHA-256、修改时间、隔离时间、触发原因
⑥ 任意一步失败 → 立即停止，不做任何可能覆盖原始数据的操作
```

**检测必须在 AtomicFile 之外完成**：使用 `File.exists()` 判断，不经过 `AtomicFile`。

---

## §1 M4 完整状态转换

### 1.1 状态定义

```java
enum StoreState {
    NORMAL,        // 正常（COMPLETE / ABSENT 都归此，写入受闸门约束）
    QUARANTINED    // 隔离：一切读写被拒，直到用户显式解除
}
```

`QUARANTINED` 的 `reason` 取值：

| reason | 触发 |
|---|---|
| `BASE_AND_BAK` | **M4**：base 与 .bak 同时存在 |
| `CORRUPT` | 内容结构损坏 |
| `ORPHAN_NEW` | 只有 .new 没有 base |
| `AMBIGUOUS` | 多份候选无法判定（M8/M9/M10） |

### 1.2 M4 状态转换图

```
                        ┌─────────────────────────────┐
                        │  启动 / 任意访问 Store 之前   │
                        └──────────────┬──────────────┘
                                       │
                          【不经过 AtomicFile，用 File.exists() 检测】
                                       │
                    ┌──────────────────┴──────────────────┐
                    │                                     │
          base 与 .bak 同时存在                   其他情况
                    │                                     │
                    ▼                                     ▼
        ┌───────────────────────┐              ┌──────────────────┐
        │  进入 M4 保守隔离流程   │              │  走 §2 常规流程   │
        └───────────┬───────────┘              └──────────────────┘
                    │
                    ▼
        ┌───────────────────────────────┐
        │ 步骤 1：写 QUARANTINED 标记     │  ← 第一优先，先于任何文件移动
        │  reason = BASE_AND_BAK         │
        │  含 base/.bak 的路径+大小+sha256│
        │       +mtime+隔离时间           │
        └───────────┬───────────────────┘
                    │
           ┌────────┴────────┐
       写标记失败          写标记成功
           │                 │
           ▼                 ▼
   ┌──────────────┐  ┌──────────────────────────┐
   │ 中止           │  │ 步骤 2：隔离两份文件        │
   │ 原文件一字不动  │  │  base    → q.json.quarantined-<ts>   │
   │ 返回 FAIL_CLOSED│  │  .bak    → q.json.bak.quarantined-<ts>│
   └──────────────┘  └───────────┬──────────────┘
                                  │
                     ┌────────────┴────────────┐
                 隔离失败                   隔离成功
                     │                         │
                     ▼                         ▼
          ┌────────────────────┐    ┌──────────────────────┐
          │ 标记保留（已写成功） │    │ 状态 = QUARANTINED    │
          │ 原文件未动          │    │ 等待用户显式选择恢复   │
          │ 下次启动重试隔离     │    └──────────┬───────────┘
          │ （幂等）            │               │
          └────────────────────┘     ┌─────────┴──────────┐
                                     │                    │
                              用户选择恢复 base    用户选择恢复 .bak
                                     │                    │
                                     ▼                    ▼
                          ┌──────────────────────────────────────┐
                          │ 校验：能完整解析成合法 List<Task>      │
                          │      + SHA-256 与隔离时记录一致        │
                          └──────────────┬───────────────────────┘
                                         │
                              ┌──────────┴──────────┐
                          校验失败              校验通过
                              │                    │
                              ▼                    ▼
                    ┌──────────────┐   ┌────────────────────────┐
                    │ 保持隔离      │   │ 恢复所选份为 base        │
                    │ 另一份保留    │   │ 另一份保留为            │
                    │ 报告原因      │   │  .notchosen-<ts>        │
                    └──────────────┘   │ 删除隔离标记             │
                                       │ 状态 = NORMAL           │
                                       └────────────────────────┘
```

### 1.3 关键不变式（M4 专属）

| # | 不变式 |
|---|---|
| I1 | **检测到 base+.bak 时，绝不允许调用 `AtomicFile.openRead()`** |
| I2 | 标记写入**必须先于**任何文件移动 |
| I3 | 标记写入失败 → **不做任何文件操作**，返回失败 |
| I4 | 隔离只做**重命名**，绝不做删除 |
| I5 | **不允许自动选择** base 或 .bak 作为恢复来源；必须用户显式选择 |
| I6 | 恢复时必须校验 SHA-256 与隔离时记录一致（防隔离期间被篡改） |
| I7 | 未被选中的那份**保留**（改名 `.notchosen-<ts>`），不删除 |
| I8 | `QUARANTINED` 状态下，所有读写/清空/迁移入口一律拒绝 |

---

## §2 文件处理矩阵（更新）

### 2.1 文件种类

| 文件 | 归属 | 说明 |
|---|---|---|
| `queue.json` | 应用（base） | 唯一真相 |
| `queue.json.new` | AtomicFile | 写入中的临时文件 |
| `queue.json.bak` | **双方争用** | 旧版手写备份 / AtomicFile 事务备份 |
| `queue.json.tmp` | 旧版遗留 | 更早版本的写入残留 |
| `queue.json.snapshot-<ts>` | 应用（新） | v3 起替代 `.bak` 的备份命名 |
| `queue.json.quarantined-<ts>` | 应用 | M4 隔离后的 base |
| `queue.json.bak.quarantined-<ts>` | 应用 | M4 隔离后的 .bak |
| `queue.json.notchosen-<ts>` | 应用 | 用户恢复后未选中的那份 |

### 2.2 矩阵

| # | base | .new | .bak | .tmp | 处理 | 测试 |
|---|---|---|---|---|---|---|
| M1 | ✅ | — | — | — | 正常 | T-M1 |
| M2 | ✅ | ✅ | — | — | 交给 AtomicFile（openRead 自清 .new） | T-M2 |
| M3 | ❌ | ✅ | — | — | **不自动改名**；QUARANTINED(`ORPHAN_NEW`) | T-M3 |
| **M4** | **✅** | — | **✅** | — | **QUARANTINED(`BASE_AND_BAK`)**；禁 openRead；两份都隔离保留 | **T-M4a/b/c** |
| M5 | ❌ | — | ✅ | — | **同 M4**（只有 .bak 也不自动恢复为 base） | T-M5 |
| M6 | ✅ | — | — | ✅ | 校验 base；通过则 .tmp→`.tmp.archived-<ts>`（不删） | T-M6 |
| M7 | ❌ | — | — | ✅ | 保留 .tmp，报"可能可恢复" | T-M7 |
| M8 | ✅(坏) | — | — | ✅ | 两份保留；QUARANTINED(`AMBIGUOUS`) | T-M8 |
| M9 | ❌ | ✅ | ✅ | — | 全部保留；QUARANTINED(`AMBIGUOUS`) | T-M9 |
| M10 | ✅ | ✅ | ✅ | ✅ | 全部保留；QUARANTINED(`AMBIGUOUS`) | T-M10 |

**M5 修订（v3）**：v2 允许 AtomicFile 自动恢复 `.bak → base`；v3 改为**同样隔离**。
理由：无法证明这份 `.bak` 是"事务备份"还是"用户清空前的数据备份"，二者语义完全不同。

---

## §3 隔离标记持久化

### 3.1 位置与内容

```
files/quarantine/<storeId>.quarantine
```

`<storeId>` 例：`queue.json` → `queue_json`

JSON 单行：

```json
{
  "store": "queue.json",
  "reason": "BASE_AND_BAK",
  "since": 1793517600000,
  "entries": [
    {"role":"base","orig":"queue.json","size":12345,"sha256":"ab12...","mtime":1793517500000,"isolated":"queue.json.quarantined-1793517600000"},
    {"role":"bak","orig":"queue.json.bak","size":12000,"sha256":"cd34...","mtime":1793517000000,"isolated":"queue.json.bak.quarantined-1793517600000"}
  ],
  "detail": "base and .bak both present"
}
```

### 3.2 标记写入自身的原子性

- 标记文件用 **它自己的 `AtomicFile`**（base 是 `.quarantine`，不会与 `queue.json.bak` 混淆）。
- 写完后**回读校验**（比对字节），失败即视为"标记写入失败"。

### 3.3 顺序保证

```
写标记（含回读校验） → 成功 → 才允许移动文件
                     → 失败 → 中止，原文件不动
```

---

## §4 测试矩阵（M4 完整）

### 4.1 核心用例

| ID | 场景 | 步骤 | 断言 |
|---|---|---|---|
| **T-M4a** | base + **旧版手写 .bak** | 预置 base(内容A) + .bak(内容B，模拟清空前) | ① 两份都还在（隔离后文件名）② state=QUARANTINED ③ reason=BASE_AND_BAK ④ **未调用 openRead** ⑤ 两份 sha256 与预置一致 |
| **T-M4b** | base + **AtomicFile 遗留 .bak** | 预置 base + .bak（模拟事务备份） | 同上（**不区分 .bak 来源**，一律隔离） |
| **T-M4c** | 标记写入失败 | Store 注入 `write() throws` | ① 返回失败 ② **base 与 .bak 原始路径、字节完全未变** ③ 无隔离文件产生 |
| **T-M4d** | 隔离中进程被杀 | 标记写成功后、移动前注入崩溃 | 重启后：① 仍识别 QUARANTINED ② **不得误判 ABSENT** ③ 原文件仍在原地 ④ 可重试隔离（幂等） |
| **T-M4e** | 隔离后调各写入入口 | update/mutate/remove/enqueue/clearFinished/save 全试 | 全部返回 `QUARANTINED` 失败；磁盘无任何变化 |
| **T-M4f** | 隔离后重启 | 重开进程 | 仍 QUARANTINED；无自动解除 |
| **T-M4g** | 用户恢复 base | 选 base + 校验通过 | ① base 恢复为 `queue.json` ② `.bak` → `.notchosen-<ts>` **保留** ③ 标记删除 ④ state=NORMAL |
| **T-M4h** | 用户恢复 .bak | 选 .bak + 校验通过 | 对称断言 |
| **T-M4i** | 恢复时校验失败 | 选中的份内容损坏 | ① 保持 QUARANTINED ② 两份都保留 ③ 明确报告原因 ④ 不得静默覆盖另一份 |
| **T-M4j** | 恢复时 sha256 不符 | 隔离期间篡改被选份 | 保持隔离；报告完整性校验失败 |

### 4.2 禁止性断言（防"优化"回去）

| ID | 断言 |
|---|---|
| T-M4-X1 | 源码中**不存在**"检测到 .bak 就删除/改名后继续读 base"的路径 |
| T-M4-X2 | 源码中**不存在**自动选择恢复来源的路径（恢复必须带 `userChosen` 参数） |
| T-M4-X3 | `SaveIntent` 默认值为 `NORMAL`；`EXPLICIT_SHRINK` 只在显式删除路径使用 |

---

## §5 恢复流程（用户显式）

### 5.1 UI 契约

隔离时界面必须显示：

```
检测到队列数据存在两份（base 与 .bak），无法判断哪份是最新的。
两份都已原样保留，请选择要恢复的一份：

  [A] queue.json          12,345 B   修改于 11-01 09:12   SHA256 ab12…
  [B] queue.json.bak      12,000 B   修改于 11-01 08:40   SHA256 cd34…

  ⚠ 未被选择的一份会保留为 .notchosen-<ts>，不会删除。
```

### 5.2 恢复 API

```java
boolean resolveQuarantine(Context ctx, Role chosen);   // Role.BASE / Role.BAK
```

- **必须显式传 `chosen`**，无默认值、无重载。
- 内部：校验 SHA-256 → 校验可解析 → 执行 → 更新标记 → 删除标记。

---

## §6 边界声明

| 保证 | 状态 |
|---|---|
| 文件内容 fsync | ✅ 字节码确认 + 实测 |
| 无 delete 窗口 | ✅ 实测（startWrite 不删 base） |
| 写失败不改原文件 | ✅ 实测（failWrite 后 base 完好） |
| M4 不自动选择来源 | ✅ 设计强制（`resolveQuarantine` 必须传参） |
| 隔离跨重启有效 | ✅ 磁盘文件 |
| 目录项持久化 | ❌ 无 API，不宣称 |
| 真机掉电安全 | ❌ 未测，不宣称 |

---

## §7 分阶段

| 阶段 | 内容 |
|---|---|
| P0-0 | `Store` 接缝 + `setStoreForTest` |
| P0-1 | 写 T-M4a..T-M4c 失败测试 → 必红 |
| P0-2 | 四态 + 隔离标记（含 M4 检测前置） |
| P0-3 | M4 隔离流程 + 恢复 API |
| P0-4 | AtomicFile 接入（非 M4 路径）+ 备份改 `.snapshot-` |
| P0-5 | `synchronized` + `mutate()` + `copy()` 快照 |
| P0-6 | `.tmp` 决策表 D1..D5 |
| P0-7 | 18 处调用点返回值 |
| P0-8 | 真机跑 T-M4d/T-M4e/T-M4f |

---

## 附录：v2 → v3 变更

| 项 | v2 | v3 |
|---|---|---|
| M4 处理 | 自动移走 .bak 后读 base | **QUARANTINED，两份都保留** |
| M5（只有 .bak） | 允许 AtomicFile 自动恢复 | **同样隔离** |
| 恢复来源 | 未定义 | `resolveQuarantine(ctx, Role)` 强制显式 |
| 完整性记录 | 无 | 记录 size/sha256/mtime |
| 标记失败处理 | 未定义 | 中止，原文件不动 |
| 禁止性测试 | 无 | T-M4-X1..X3 |

---

## 附：P0-3 实测修正（2026-11-01）

早期探针记录「openRead 会用 .bak 覆盖 base」。P0-3 用 `StoreIo.FileImpl`
直接验证后修正为：

- **读路径不覆盖**：base 与 .bak 同时存在时，读到的是 base 内容，两份都保留。
- **写路径确有破坏**：事务写会**删除同名 `<base>.bak`**。

因此「应用层必须避开 `.bak`」的结论成立，但依据是**写事务会删它**，
而不是「读时覆盖」。完整实测见 `doc/ATOMICFILE-EVIDENCE.md`。
