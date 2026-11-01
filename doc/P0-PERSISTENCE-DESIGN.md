# P0 持久化可靠性工程设计 · v2

**状态**：设计稿 v2，待验收。**零生产代码改动。**
**基线**：`344981f`（1.9.32/128）· 设计稿 v1 = `2da269c`
**范围**：B5（原子性）、B6（完整性防线）、B4（并发）

> v2 相对 v1 的改动：补齐 6 项修正，并**修正 v1 的一个事实错误**
> （v1 声称"AtomicFile 不产生 .bak"，实测证明**它会产生并优先使用 .bak 覆盖 base**）。

---

## §0 已核实的事实（全部来自本机实测，非文档推测）

### 0.1 进程模型

| 事实 | 证据 |
|---|---|
| `CrackService` 无 `android:process` | `AndroidManifest.xml:63` |
| 全仓无 `ContentProvider` / AIDL / `Messenger` | grep 零命中 |
| **→ 纯单进程** | `synchronized` 足够 |

### 0.2 AtomicFile 完整文件生命周期（本机 Android 15 实测）

**探针 A：写入流程**

```
初始          : q.json(11)
startWrite 后 : q.json(11) + q.json.new(0)     ← base 原地不动，不产生 .bak
finishWrite 后: q.json(NEW_CONTENT)            ← .new 直接 rename 覆盖 base
崩溃(未finish) : q.json(THIRD) + q.json.new(7) ← base 完好
openRead 后   : q.json(THIRD)                  ← 自动删除残留 .new
```

**探针 B：`.bak` 的语义（关键）**

```
场景1  只有 .bak、无 base   → openRead 自动恢复: .bak → base，读到 FROM_BAK
场景2  base 与 .bak 都存在  → openRead 用 .bak 覆盖 base！读到 BAK_VALUE
```

**字节码佐证**（`/system/framework/framework.jar` 的 `classes4.dex`）：

```
openRead:
  iget-object mLegacyBackupName ; exists() ; rename(.bak → base)
  iget-object mNewName ; exists() && base.exists() ; delete(.new)
  new FileInputStream(base)

finishWrite:
  FileUtils.sync(fos)  ← fsync，已确认
  close
  AtomicFile.rename(.new → base)
```

### 0.3 **由此发现的严重冲突（v1 遗漏）**

当前 `TaskQueue.save()` 在清空队列时**手动写 `queue.json.bak`** 作为备份。
而 `AtomicFile.openRead()` 把 `<base>.bak` **当作崩溃遗留备份，优先覆盖 base**。

→ **引入 AtomicFile 后，一次"清空队列"写出的 `.bak`，会在下次读取时把清空前的数据"恢复"回来，或直接回滚最新写入。**

**因此：应用层自己的一切备份文件，必须改为不复用 `<base>.bak` 的命名。**

### 0.4 仍然未验证

| 项 | 原因 |
|---|---|
| 目录项持久化 | Android 无 Java API 可 fsync 目录 |
| 真机掉电安全 | 无测试条件 |
| 其他 ROM/版本 AtomicFile 行为 | 本机实测仅代表 Android 15 / OnePlus |

---

## §1 读取状态模型（修正 1 + 2）

v1 用三态（COMPLETE/ABSENT/BROKEN），v2 拆成**四态 + 一个隔离态**。

```java
public enum ReadState {
    COMPLETE,     // 文件存在、结构完整、全部元素解析成功
    ABSENT,       // 文件不存在（合法空状态）
    CORRUPT,      // 文件存在，但内容结构损坏（JSON 非法 / 元素类型错 / 截断）
    IO_ERROR      // 文件存在性未知或读取过程发生 IO 异常（权限、SELinux、IO 故障）
}
```

**CORRUPT 与 IO_ERROR 的判定规则（互斥）**：

| 情形 | 判定 | 依据 |
|---|---|---|
| `FileNotFoundException` 且 `!file.exists()` | `ABSENT` | 文件确实不在 |
| `FileNotFoundException` 但 `file.exists()` | `IO_ERROR` | 存在性矛盾 = 访问受限 |
| `IOException`（权限/IO 故障） | `IO_ERROR` | 不是内容问题 |
| 读到字节，但 `new JSONArray` 抛 | `CORRUPT` | **内容结构**确实坏 |
| JSON 合法，但某元素 `getJSONObject` 抛 | `CORRUPT` | 元素结构坏 |
| 读到 0 字节 / 空白 | `CORRUPT` | 有文件无内容 |

**核心区别（你的第 2 点）**：

- **CORRUPT** → 允许隔离（把文件改名保护起来）
- **IO_ERROR** → **禁止任何写操作、禁止隔离**（文件可能完好，只是读不到）
  - 返回失败，上层重试；
  - **原文件一字不动**。

---

## §2 状态转换图（修正 1）

```
                    ┌──────────────┐
                    │   启动 / 读取 │
                    └──────┬───────┘
                           │
         ┌─────────────────┼─────────────────┬──────────────┐
         │                 │                 │              │
      file 不存在      读取成功           内容损坏        IO 异常
         │                 │                 │              │
         ▼                 ▼                 ▼              ▼
    ┌────────┐       ┌──────────┐      ┌─────────┐    ┌──────────┐
    │ ABSENT │       │ COMPLETE │      │ CORRUPT │    │ IO_ERROR │
    └────┬───┘       └────┬─────┘      └────┬────┘    └────┬─────┘
         │                │                 │              │
         │ 允许写          │ 允许写           │ 隔离后进隔离态 │ 禁写、可重试
         │                │                 │              │
         │                │                 ▼              │
         │                │          ┌──────────────┐      │
         │                │          │ QUARANTINED  │      │
         │                │          │（持久化保存） │      │
         │                │          └──────┬───────┘      │
         │                │                 │              │
         │                │      用户显式"确认解除"          │
         │                │                 │              │
         │                │                 ▼              │
         │                │          回到 ABSENT ──────────┘
         │                │        （需用户明确操作）
         ▼                ▼
      ┌────────────────────────┐
      │   允许写入（受闸门约束）  │
      └────────────────────────┘
```

### 2.1 关键不变式

1. **`IO_ERROR` 永不触发隔离，永不写盘。**
2. **`CORRUPT` 隔离后 → `QUARANTINED`，此后所有写入一律拒绝**，直到用户显式解除。
3. **`QUARANTINED` 状态持久化在磁盘**（不是内存标志），**进程重启后仍有效**。
4. **解除隔离必须由用户显式确认**（UI 上明确提示"这会丢失原队列数据"），代码不得自动解除。

### 2.2 这堵住了 v1 的漏洞

v1：`BROKEN` → 重命名 corrupt → 下次读到"文件不存在" → `ABSENT` → **放行写入**。

v2：重命名 corrupt 的**同时**写入隔离标记 → 下次读：`ABSENT` + **`QUARANTINED` 标记存在** → **拒绝写入**，直到用户解除。

---

## §3 隔离状态持久化方案（修正 1）

### 3.1 存放位置与格式

```
files/quarantine/<storeId>.quarantine
```

内容（JSON，单行）：

```json
{"store":"queue.json","since":1793517600000,"reason":"CORRUPT","evidence":"queue.json.corrupt-1793517600000","detail":"JSONArray parse failed at offset 0"}
```

字段：

| 字段 | 含义 |
|---|---|
| `store` | 哪个存储（`queue.json` / `history.csv` / `tried/*`） |
| `since` | 隔离开始的毫秒时间戳 |
| `reason` | `CORRUPT`（当前只会是它；`IO_ERROR` 不隔离） |
| `evidence` | 被隔离文件的实际路径（corrupt 文件名） |
| `detail` | 诊断信息（异常摘要，不含敏感数据） |

### 3.2 写入时机与原子性

- 隔离文件本身用 **`AtomicFile`** 写（它不会有 `.bak` 冲突，因为隔离文件我们从不手动写 `.bak`）。
- **顺序**：先写隔离标记 → **再**重命名 corrupt 文件。
  - 若在两步之间崩溃：标记存在但 corrupt 文件还在 → 下次读仍是 `CORRUPT` → 重新执行隔离（幂等）。
  - 反过来则会出现"文件被移走但没标记"的窗口 → 所以顺序不能颠倒。

### 3.3 解除流程（必须用户显式确认）

```
用户点「解除隔离并重新开始」
  → UI 弹确认框，明确写出：
      · 被隔离的文件路径
      · 隔离时间
      · 后果：当前队列将从空开始，旧数据保留在 corrupt 文件中
  → 用户确认
  → 删除隔离标记（AtomicFile.delete）
  → 返回 ABSENT
```

**代码中不得存在任何自动解除路径**（重启、清缓存、重装都不能解除）。

---

## §4 AtomicFile 完整文件状态矩阵（修正 3）

**这是 v1 最欠缺的部分。以下全部基于实测。**

### 4.1 文件种类

| 文件 | 归属 | 说明 |
|---|---|---|
| `queue.json` | 应用（base） | 唯一真相 |
| `queue.json.new` | **AtomicFile** | 写事务中的临时文件 |
| `queue.json.bak` | **AtomicFile（注意！）** | `openRead` 会优先用它覆盖 base |
| `queue.json.tmp` | 旧版本遗留 | v1 之前的写入残留 |
| `queue.json.snapshot-<ts>` | **应用（新）** | 替代原 `.bak` 的备份，命名避开 AtomicFile |
| `queue.json.corrupt-<ts>` | 应用 | 隔离证据 |

### 4.2 状态矩阵（每种组合 + 处理 + 测试）

| # | base | .new | .bak | .tmp | 处理 | 测试 |
|---|---|---|---|---|---|---|
| M1 | ✅ | — | — | — | 正常读 | T-M1 |
| M2 | ✅ | ✅ | — | — | **让 AtomicFile 自己处理**（openRead 会删 .new）；应用层不动 | T-M2 |
| M3 | ❌ | ✅ | — | — | **不自动改名**！保留 .new，标记 `QUARANTINED`（原因 `ORPHAN_NEW`），等人工 | T-M3 |
| M4 | ✅ | — | ✅ | — | **AtomicFile 会用 .bak 覆盖 base** → 应用层**必须在启动时先自己检测并移走 .bak**，否则数据回滚 | T-M4 |
| M5 | ❌ | — | ✅ | — | AtomicFile 会自动恢复 .bak→base。**允许**（这是它的设计），但恢复后需校验内容；校验失败 → QUARANTINED | T-M5 |
| M6 | ✅ | — | — | ✅ | 验证 base 完整性 → 通过则把 .tmp 归档为 `.tmp.archived-<ts>`；**不直接删除** | T-M6 |
| M7 | ❌ | — | — | ✅ | **保留 .tmp**，检查其完整性；完整则提示用户可恢复（不自动改名） | T-M7 |
| M8 | ✅(坏) | — | — | ✅ | **两份都保留**，都进 corrupt 存档，标记 `QUARANTINED`（原因 `AMBIGUOUS`） | T-M8 |
| M9 | ❌ | ✅ | ✅ | — | 无法确认哪份完整 → `QUARANTINED`（`AMBIGUOUS`），两份都保留 | T-M9 |
| M10 | ✅ | ✅ | ✅ | ✅ | 同上，全部保留 + `QUARANTINED` | T-M10 |

### 4.3 关键规则

1. **"AtomicFile 优先执行自身恢复逻辑"**：M2/M5 由 AtomicFile 处理，**应用层 recovery 不得提前删 `.new` 或 `.bak`**。
2. **例外**：M4（base + .bak 同时存在且 base 完好）—— 因为实测证明 AtomicFile 会**用 .bak 覆盖完好的 base**，这是数据回滚风险。应用层必须在 `openRead` 之前把 `.bak` 移走（改名 `.bak.stale-<ts>`）。
3. **旧 `.tmp` 一律不无条件删除**（见 §5）。

### 4.4 应用层备份改名（解决 §0.3 冲突）

`TaskQueue.save()` 原逻辑（清空时写 `queue.json.bak`）改为：

```java
File snap = new File(dir, FILE + ".snapshot-" + ts);   // 不用 .bak
```

并保留最近 3 份，超出删最旧。

---

## §5 旧 `.tmp` 恢复决策表（修正 5）

**禁止启动时无条件删除。**

| # | base 状态 | .tmp 状态 | 决策 |
|---|---|---|---|
| D1 | 完整可读 | 存在 | 归档为 `.tmp.archived-<ts>`（保留 3 份），**不删**；日志记录 |
| D2 | 不存在 | 存在且完整 | **保留**，提示"发现一份可能可恢复的数据"，由用户决定是否恢复 |
| D3 | 不存在 | 存在但损坏 | **保留**并改名 `.tmp.corrupt-<ts>`，进 QUARANTINED(`AMBIGUOUS`) |
| D4 | 损坏 | 存在 | **两份都保留**，都进 corrupt 存档，QUARANTINED(`AMBIGUOUS`) |
| D5 | 不存在 | 不存在 | 正常，ABSENT |

**"完整性"判定**：能完整解析成 `List<Task>` 且每个元素合法。

**决策表的核心原则（你的要求）**：旧文件可能是唯一幸存副本，**清理目录不能牺牲历史数据**。

---

## §6 Task 对象并发安全设计（修正 4）

### 6.1 现状核查

| 方法 | 返回 | 问题 |
|---|---|---|
| `load(ctx)` | `List<Task>`，元素是**同一个可变对象** | 调用方拿到后可直接改字段，绕过锁 |
| `nextPending(ctx)` | `Task`（来自 `load`） | 同上 |
| `update(ctx, task)` | 直接用传入对象覆盖列表元素 | 传进来的对象若是外部持有引用 → 锁外修改 |

**结论**：确实是"返回内部可变对象"。

### 6.2 设计

```java
// Task 增加快照
public Task copy() { ... }        // 深拷贝（所有字段含 String / List）

// load 返回快照
public static List<Task> load(Context ctx) {
    synchronized (LOCK) {
        LoadResult r = read();
        if (r.state != COMPLETE && r.state != ABSENT) return Collections.emptyList();
        List<Task> out = new ArrayList<>(r.tasks.size());
        for (Task t : r.tasks) out.add(t.copy());   // 快照
        return out;
    }
}
```

### 6.3 统一修改入口

**禁止外部直接改 `Task` 字段后调 `update()`。** 改为：

```java
public static boolean mutate(Context ctx, String taskId, Consumer<Task> f);
```

- 在锁内：读 → 找到 task → 应用 `f` → 写回。
- `update(ctx, task)` 保留但**内部改为按 id 合并**（不用整对象替换）。

### 6.4 不变式

> **任何 `Task` 实例一旦离开 `TaskQueue` 的锁范围，就是只读快照。**
> 所有状态修改必须经 `mutate()`。

---

## §7 事务流程（v1 基础上按四态细化）

```
写入入口（update / mutate / remove / enqueue / save）
  │
  ├── synchronized (LOCK)
  │
  ├── 1. 检查隔离标记
  │      if (quarantineExists()) → return Failure(QUARANTINED)
  │
  ├── 2. 读取
  │      LoadResult r = read()
  │      switch (r.state) {
  │        case IO_ERROR → return Failure(IO_ERROR)      // 不写、不隔离
  │        case CORRUPT  → quarantine(r) ; return Failure(CORRUPT)
  │        case ABSENT   → 基准 = 空列表
  │        case COMPLETE → 基准 = r.tasks
  │      }
  │
  ├── 3. 完整性比较（NORMAL 意图下）
  │      if (intent == NORMAL && 新条数 < 旧条数) → return Failure(SHRINK_REFUSED)
  │
  ├── 4. 原子写入（AtomicFile）
  │      startWrite → write → finishWrite（内部 fsync + rename）
  │      失败 → failWrite → return Failure(WRITE_FAILED)
  │
  └── 5. 备份（仅在 EXPLICIT_SHRINK 时）
         写 .snapshot-<ts>（不使用 .bak）
```

---

## §8 失败注入测试方案（修正 6）

### 8.1 接缝

```java
interface Store {
    boolean exists();
    long length();
    byte[] read() throws IOException;
    WriteHandle beginWrite() throws IOException;   // 对应 startWrite
    boolean finish(WriteHandle h);                 // 对应 finishWrite
    void abort(WriteHandle h);                     // 对应 failWrite
    boolean delete();
    boolean renameTo(String name);
    String[] list();                               // 用于矩阵测试
}
static void setStoreForTest(Store s);              // 包级
```

生产实现包装 `AtomicFile`，行为不变。

### 8.2 用例

| ID | 注入 | 断言 |
|---|---|---|
| T-M1..M10 | 预置不同文件组合 | 见 §4.2 表 |
| T-R1 | `read()` 抛 `FileNotFoundException` 且 `exists()==false` | `ABSENT`，允许写 |
| T-R2 | `read()` 抛 `IOException` | `IO_ERROR`；**原文件字节未变**；返回失败 |
| T-R3 | 内容为非法 JSON | `CORRUPT`；隔离标记被创建；corrupt 文件存在 |
| T-R4 | `[合法,合法,非法]` | `CORRUPT`（不是"部分成功"）；不写回 |
| T-Q1 | 隔离标记存在 | 任何写操作返回 `QUARANTINED`；盘上无变化 |
| T-Q2 | 隔离后重开（模拟重启） | 仍 `QUARANTINED` |
| T-Q3 | 用户解除后 | 变 `ABSENT`，可写 |
| T-W1 | `finish()` 返回 false | 返回失败；原文件完好；**`dirty`/状态未清** |
| T-W2 | 新条数 < 旧条数（NORMAL） | 拒绝；盘上不变 |
| T-W3 | 新条数 < 旧条数（EXPLICIT_SHRINK） | 允许；生成 `.snapshot-<ts>` |
| T-C1 | 两线程各 `mutate` 一次 | 两条修改都生效（B4） |
| T-C2 | `load()` 后改返回对象 | 不影响盘上（快照隔离） |
| T-T1 | base 完好 + .tmp 存在（D1） | .tmp 被归档不删除 |
| T-T2 | base 缺失 + .tmp 完好（D2） | .tmp 保留，报可恢复 |
| T-T3 | base 损坏 + .tmp 存在（D4） | 两份都保留；QUARANTINED |
| T-B1 | base 与 .bak 同时存在（M4） | .bak 被移走，base 未被覆盖 |

### 8.3 真实类 vs 桩

- **可在桌面 JVM 跑**：`TaskQueue` 全部（有了 `setStoreForTest`）
- **必须在真机跑**：涉及 `org.json` 元素级异常（android.jar 是桩）
- **已实测通过**：AtomicFile 的 5 个场景 + .bak 语义（见 §0.2）

---

## §9 边界声明（不得含糊）

| 保证 | 状态 |
|---|---|
| 文件内容 fsync | ✅ 本机字节码确认 + 实测。**可宣称** |
| 无 delete 窗口 | ✅ 实测：startWrite 不删 base。**可宣称** |
| 写入失败不改动原文件 | ✅ 实测：failWrite 后 base 完好。**可宣称** |
| 隔离状态跨重启有效 | ✅ 设计为磁盘文件（待实现验证） |
| 目录项持久化 | ❌ **无 API**，不宣称 |
| 真机掉电安全 | ❌ **未测**，不宣称 |
| 其他 ROM 行为一致 | ❌ 本机实测仅代表 Android 15 / OnePlus |

---

## §10 分阶段（每阶段先写失败测试）

| 阶段 | 内容 | 前置 |
|---|---|---|
| P0-0 | `Store` 接缝 + `setStoreForTest`（不改行为） | — |
| P0-1 | 写 T-R1..R4 失败测试 → 必红 | P0-0 |
| P0-2 | 四态 `ReadResult` + 隔离标记 | P0-1 |
| P0-3 | AtomicFile 接入 + `.bak` 冲突处理（M4）+ 备份改名 | P0-2 |
| P0-4 | `synchronized` + `mutate()` + 快照 | P0-3 |
| P0-5 | `.tmp` 决策表 D1..D5 + 启动 recovery | P0-4 |
| P0-6 | 18 处调用点返回值检查 | P0-5 |
| P0-7 | 真机跑 T-M*/T-R4/T-C1 | P0-6 |

---

## 附录：v1 → v2 修正对照

| 你的要求 | v1 的做法 | v2 的做法 |
|---|---|---|
| 1. BROKEN→ABSENT 漏洞 | 未覆盖 | 四态 + 持久化隔离标记 `QUARANTINED` |
| 2. CORRUPT vs IO_ERROR | 混为 BROKEN | 明确拆分，IO_ERROR 禁写禁隔离 |
| 3. AtomicFile 完整生命周期 | 只考虑 .new | 实测 .bak 会被优先使用；给出 10 组矩阵 + M4 冲突处理 |
| 4. Task 并发安全 | 未涉及 | `copy()` 快照 + `mutate()` 统一入口 |
| 5. 旧 .tmp 处理 | 无条件清理 | D1..D5 决策表，一律不删 |
| 6. 失败注入测试 | 部分 | 补齐 T-M*/T-R*/T-Q*/T-W*/T-C*/T-T*/T-B* |
