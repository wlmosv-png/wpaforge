# P0 持久化可靠性工程设计

**状态**：设计稿，待审。**尚未修改任何生产代码。**
**基线**：`344981f`（1.9.32/128）
**范围**：B5（原子性）、B6（完整性防线）、B4（并发）

---

## 0. 已核实的前提事实

这些是设计的基础，全部经实际核查，不是假设。

| 事实 | 证据 | 影响 |
|---|---|---|
| **单进程**：`CrackService` 未声明 `android:process` | `AndroidManifest.xml:63` | 进程内锁足够 |
| **无跨进程**：全仓无 `ContentProvider` / AIDL / `Messenger` | grep 零命中 | 不需要文件锁 / 不需要 Binder 序列化 |
| `android.util.AtomicFile` 可用 | `android.jar` 含 `android/util/AtomicFile.class`，`javap` 列出 `startWrite/finishWrite/failWrite/openRead` | 可作为替换机制候选 |
| 当前 4 处 `delete()+renameTo()` | `TaskQueue:425`、`TriedSet:207`、`HistStore:311`、`RestoreStoreFile:72` | 全部要改 |
| 18 处 `save`/`update` 调用点，只有 2 处检查返回值 | 见附录 A | 需要全部改造 |
| 全仓零 `fsync` / `FileChannel` / `AtomicFile` 使用 | grep 零命中 | 当前无任何掉电保证 |

### 真机实测结论（2026-11-01，本机 OnePlus PJX110 / Android 15）

**AtomicFile 功能行为**：用 dex + `app_process` 在本机实跑 5 个场景，全部符合预期：

```
== 1. 首次写入 ==      base exists=true len=5   new exists=false   readFully=HELLO
== 2. 覆盖写入 ==      base len=6 content=WORLD2  new exists=false
== 3. failWrite 回滚 == base len=6 content=WORLD2  new exists=false   ← 原文件完好
== 4. 未 finish 崩溃 == new exists=true（残留）    base content=WORLD2  ← 原文件完好
== 5. 读不存在文件 ==  抛 FileNotFoundException
```

关键：**覆盖写入没有任何 delete 窗口**；`failWrite` 后原文件字节未变；写到一半崩溃只留 `.new`、base 完好。

**finishWrite 是否 fsync**：从本机 `/system/framework/framework.jar` 的 `classes4.dex` 反编译 `Landroid/util/AtomicFile;`，得到实际字节码：

```
1d9b66: invoke-static {v4}, Landroid/os/FileUtils;.sync:(Ljava/io/FileOutputStream;)Z
1d9b80: invoke-virtual {v4}, Ljava/io/FileOutputStream;.close:()V
```

即 **`finishWrite` 确实调用 `FileUtils.sync(FileOutputStream)`（= fsync）**，`failWrite` 同构（1d9bfe 处也是 sync）。
这是本机 framework 的实际字节码，不是文档推测。

### 仍然未验证（必须标记，不得当成结论）

- **目录项持久化**：`fsync` 只保证文件内容落盘，**不保证 rename 后的目录项**。Android 无 Java API 对目录做 fsync。→ **不能宣称"掉电后一定看到新文件"**。
- **`File.renameTo()` 在 `/data` 的原子性语义**：POSIX `rename(2)` 同文件系统原子，但 Android `File.renameTo` 的具体实现路径与 SELinux 介入**未实测**。
- **真机掉电安全**：无实测条件，**本轮不提供任何掉电安全保证**。

---

## 1. 新旧架构对比

### 1.1 现状（问题结构）

```
调用方                      TaskQueue
  │                            │
  ├─ update(ctx, task) ───────>│ load(ctx) ──┐   ← 读（可能失败，返回前缀/空）
  │                            │              │
  │                            │ 改内存       │
  │                            │              │
  │                            └─ save(ctx, list)  ← 无锁整份覆盖
  │                                   │
  │                                   ├─ refuseSave: 只拦「读失败 + 写空」
  │                                   ├─ delete(f)      ← 窗口开始
  │                                   ├─ write(tmp)
  │                                   └─ rename(tmp,f) ← 窗口结束；失败被 || exists 掩盖
  │
  └─ 返回值：18 处调用点，16 处丢弃
```

三个结构性缺陷：
1. **读取状态不可表达**：`load()` 只返回 `List`，`loadFailedFlag` 是进程级静态量，与"这一次读取"无关。
2. **写回无完整性比较**：`save()` 不知道旧文件有多少条。
3. **写入原子性靠两步**：`delete` 制造窗口，`rename` 失败被 `|| f.exists()` 掩盖。

### 1.2 目标架构

```
调用方                      TaskQueue
  │                            │
  ├─ update(ctx, task) ───────>│ synchronized (LOCK) {          ← 进程内互斥
  │                            │   LoadResult r = read();       ← 三态结果
  │                            │   if (!r.isComplete()) 拒绝写回 ← 新增闸门
  │                            │   改内存
  │                            │   boolean ok = write(list);    ← 原子替换
  │                            │   return ok;                   ← 必须被消费
  │                            │ }
  │
  └─ 检查返回值 → 失败要反馈 UI
```

四处改变：
1. **`LoadResult` 三态**：`COMPLETE` / `ABSENT`（文件不存在，合法空）/ `BROKEN`（读失败或解析失败）。
2. **写回前闸门**：只有 `COMPLETE` 或 `ABSENT` 才允许写回；`BROKEN` 一律拒绝并保留原文件。
3. **`synchronized` 包住整个「读-改-写」**（单进程已验证）。
4. **原子替换用 `AtomicFile`**，并显式 `finishWrite` / `failWrite`。

---

## 2. 文件读写事务流程

### 2.1 读取：三态模型

```java
public enum ReadState { COMPLETE, ABSENT, BROKEN }

public static final class LoadResult {
    public final ReadState state;
    public final List<Task> tasks;   // COMPLETE/ABSENT 时有效；BROKEN 时为空
    public final String brokenReason; // BROKEN 时的诊断
}
```

判定规则：

| 情形 | state | tasks |
|---|---|---|
| 文件不存在 | `ABSENT` | 空（合法） |
| 文件存在、整体 JSON 解析成功、每个元素都能转成 Task | `COMPLETE` | 全部 |
| 整体 JSON 解析失败 / 任一元素转换失败 / IO 异常 | `BROKEN` | **空**（必须 `clear()`，不再返回前缀） |

**关键纠正（对应你的第 1 条）**：`BROKEN` 时返回**确定性空**，且**禁止作为完整数据写回**。不再依赖"前缀"这种半状态。

### 2.2 写入：事务顺序

```
1. 检查闸门：源读取状态必须是 COMPLETE 或 ABSENT
   └─ 若是 BROKEN → 立即返回 Failure(BROKEN_READ)，原文件一字不动

2. 完整性比较（新增）：
   └─ 若 新条数 < 旧文件条数 且 本次不是显式删除 → 返回 Failure(SHRINK_REFUSED)

3. 原子写入（AtomicFile）：
   FileOutputStream s = af.startWrite();
   try {
       s.write(bytes);
       af.finishWrite(s);     // 内部 close + sync + rename
       return Success;
   } catch (IOException e) {
       af.failWrite(s);       // 删除临时文件，保留原文件
       return Failure(WRITE_FAILED);
   }
```

**关键纠正（对应你的第 2 条）**：不再用裸 `File.renameTo`。改用 `AtomicFile`，并**明确标注**它的保证边界（§6）。

### 2.3 「显式删除」如何表达

`save(ctx, list)` 无法区分"用户删了两条"和"读到残缺数据"。新增重载：

```java
public static boolean save(Context ctx, List<Task> tasks, WriteIntent intent);
public enum WriteIntent { NORMAL, EXPLICIT_SHRINK }
```

只有 `remove()` / `clearFinished()` / 用户清空这几处传 `EXPLICIT_SHRINK`。其余一律 `NORMAL`（受完整性闸门约束）。

---

## 3. 并发控制模型

### 3.1 进程模型（已核实）

- `CrackService` 无 `android:process` → **与 UI 同进程**
- 无 ContentProvider / AIDL → **无跨进程访问**
- 结论：**进程内 `synchronized` 足以覆盖全部读写**

### 3.2 锁的设计

```java
private static final Object LOCK = new Object();   // 类级，跨实例共享
```

**必须包住的范围**（这是关键——只包 `save` 不够）：

| 方法 | 是否入锁 | 理由 |
|---|---|---|
| `load(ctx)` | ✅ | 读也被写打断 → 读到半写状态 |
| `save(ctx, list, intent)` | ✅ | 写入本身 |
| `update` / `updateProgress` / `remove` / `clearFinished` / `enqueue` | ✅ | 整个「读-改-写」必须是一个临界区 |
| `nextPending` / `hasWork` / `hasPending` | ⚠️ 只读，入锁保证一致性快照 |

**记录**：`TriedSet.save`、`HistStore.append/rewrite` 各自独立锁（不同文件，无嵌套）。

**禁止**：临界区内做 IO 之外的调用（不允许在锁内回调 UI）。

### 3.3 与 UI 线程的交互

`MainActivity:5490` 目前对 N 个任务**循环 N 次 `update()`**。改造后：
- 仍可在 UI 线程调用（`synchronized` 是轻量锁，非阻塞 IO 需评估）
- **更好**：新增 `updateAll(ctx, List<Task>)` 单次事务，把 N 次 `load+save` 降为 1 次

---

## 4. 崩溃恢复流程

### 4.1 文件状态矩阵

| 磁盘状态 | 含义 | 恢复动作 |
|---|---|---|
| `queue.json` 正常 | 正常 | 无 |
| `queue.json` + `queue.json.new` 残留 | AtomicFile 写中途崩溃 | `AtomicFile.openRead()` 自身忽略 `.new`（**需真机确认**）；启动时删除 `.new` |
| 仅 `.new` 残留，`queue.json` 不存在 | 极端：rename 前崩溃 | **保留 `.new`**，人工可恢复；不自动重命名（避免把半截当完整） |
| `queue.json` 损坏（JSON 非法） | 数据损坏 | **不覆盖**；重命名为 `queue.json.corrupt-<ts>` 保留证据；以空队列启动并向用户明确报告 |
| `queue.json.bak` 存在 | 上次是清空操作 | 保留，不自动恢复 |

### 4.2 关键纠正（对应你的要求）

- **文件损坏时保留原始文件和可恢复证据**：`BROKEN` 时把原文件**重命名**为 `queue.json.corrupt-<timestamp>`，而不是原地不动（否则下次还会读失败）。同时写入一条 `queue.json.corrupt-<ts>.reason` 说明失败原因。
- **临时文件/备份文件有明确策略**：启动时执行一次 `recoverFiles()`：
  - 删除 `.new`（AtomicFile 的临时文件，若 base 存在）
  - 若 base 不存在但有 `.new`，保留并上报
  - `.corrupt-*` 文件保留 N 份（如 3 份），超出删除最旧
- **`loadFailedFlag` 生命周期重构**：从"进程级静态量"改为"每次读取的返回值的一部分"（`LoadResult.state`）。彻底删除全局标志。→ 从根本上消除"卡死无重置入口"的问题。

### 4.3 TriedSet 的 dirty 语义（对应你的要求）

```java
// 错误（现状）：save 里 rename 之前就 dirty=false
// 正确：
public boolean save(File f) {
    ... 写入 ...
    boolean ok = <原子替换结果>;
    if (ok) dirty = false;      // 只有成功才清
    return ok;
}
```

调用方 `flushTried()` 必须检查返回值；失败时保留 dirty，下次重试。

---

## 5. 失败注入测试方案

### 5.1 接缝设计（必须，否则测不到真实代码）

`TaskQueue` 目前唯一耦合是 `ctx.getFilesDir()`。引入**包级可见**的存储抽象：

```java
// 生产：直接包装 File
interface Store {
    boolean exists();
    long length();
    byte[] read() throws IOException;
    void write(byte[] data) throws IOException;   // 内部用 AtomicFile
    boolean delete();
    boolean renameTo(String name);
}

static void setStoreForTest(Store s);   // 包级，生产无调用者
```

**这不算改生产逻辑**——所有路径仍走同一个 `write()` 实现，只是底层 `File` 可被替换。

### 5.2 失败注入点

| 测试 | 注入 | 断言 |
|---|---|---|
| **F1 读失败+写少**（B6 核心） | `read()` 抛 IOException | `update()` 返回 false；原文件字节未变 |
| **F2 部分解析** | 文件内容 `[合法,合法,非法]` | `state=BROKEN`；不写回；原文件保留为 `.corrupt-*` |
| **F3 写中途失败** | `write()` 写一半后抛 | 原文件完好；返回 false |
| **F4 rename 失败** | `write()` 内 rename 抛 | 原文件完好；返回 false（不是 true） |
| **F5 并发丢更新**（B4） | 两线程各 remove 一条 | 终态必须 2 条（当前真实类实测 95.7% 失败） |
| **F6 TriedSet dirty** | save 失败 | `isDirty()` 仍为 true；下次 flush 会重试 |
| **F7 空覆盖仍允许** | 显式 EXPLICIT_SHRINK + 空列表 | 允许，且写 `.bak` |
| **F8 崩溃残留** | 预置 `.new` 文件 | 启动 recovery 清理，base 不受影响 |

### 5.3 真实类 vs 桩

- **F1/F5/F6/F7**：可直接用真实 `TaskQueue` + `setStoreForTest`（无需 Android Context）
- **F2/F3/F4/F8**：`org.json` 在桌面 android.jar 是桩 → **必须在真机/dex 上跑**（标记为未验证直到真机通过）

---

## 6. Android 真机验证边界

| 保证 | 桌面可验 | 真机可验 | 无法验证 |
|---|---|---|---|
| `AtomicFile` 可用且行为如预期 | ❌（桩） | ✅ **已实测通过（5/5）** | — |
| `AtomicFile.finishWrite` 是否 fsync | ❌ | ✅ **已反编译确认调用 FileUtils.sync** | — |
| 目录项持久化 | ❌ | ❌ | **无 Java API，无法验证** |
| `rename(2)` 原子性 | ⚠️ 桌面 JVM 可验 | ⚠️ 需 root 注入故障 | **目录项持久化无 API** |
| JSON 元素级异常行为 | ❌（桩） | ✅ | — |
| 并发丢更新 | ✅（已实测 1913/2000） | — | — |
| 掉电安全 | ❌ | ❌ | **本轮无法提供** |

**明确声明**（依据上述实测）：
- 可以宣称：**文件内容 fsync 成立**（finishWrite 内部调 `FileUtils.sync`，本机字节码已确认）。
- 可以宣称："移除了自身制造的 delete 窗口"（实测覆盖写入无 delete、failWrite 原文件完好）。
- 可以宣称："写入失败不再被报告为成功"（`failWrite` + 显式返回值）。
- **不宣称**"掉电绝对安全"——目录项持久化无 API 可保证，这是平台限制，不是实现缺陷。
- 复现命令见 §7。

---

## 附录 A：18 处 save/update 调用点（需全部检查返回值）

见 `TaskQueue.java` / `CrackService.java` / `MainActivity.java` 中的调用点清单（审计报告 §B4-a 已列出 15 处 + `enqueue` 内 1 处 + `RestoreJournal` 相关 2 处）。

具体改造：每处改为 `if (!TaskQueue.update(...)) { 记录并反馈 }`。

---

## 附录 B：分阶段实施顺序（每阶段先写失败测试）

| 阶段 | 内容 | 前置 |
|---|---|---|
| P0-0 | 引入 `Store` 接缝 + `setStoreForTest`（不改变行为） | — |
| P0-1 | 写 F1/F5 失败测试 → 当前必红 | P0-0 |
| P0-2 | `LoadResult` 三态 + 闸门 | P0-1 |
| P0-3 | `AtomicFile` 替换 + 返回值语义 | P0-2 |
| P0-4 | `synchronized` + `updateAll` | P0-3 |
| P0-5 | `TriedSet` dirty 修复 + `recoverFiles()` | P0-4 |
| P0-6 | 18 处调用点返回值检查 | P0-5 |
| P0-7 | 真机 dex 跑 F2/F3/F4/F8 | P0-6 |

**每阶段结束必须**：完整回归 + `git diff` 检查 + 真实测试输出。

---

## 7. 实测复现步骤（可重跑）

```bash
# 1. 编译探针（linux 环境）
javac --release 17 -cp android.jar -d cls src/AtomicFileProbe.java
java -cp d8.jar com.android.tools.r8.D8 --min-api 26 --lib android.jar \
     --output dex cls/AtomicFileProbe.class
cp dex/classes.dex /data/local/tmp/afx/

# 2. 设备上跑（android 环境）
cd /data/local/tmp/afx
CLASSPATH=/data/local/tmp/afx/classes.dex app_process /system/bin AtomicFileProbe

# 3. 反编译 framework 确认 fsync（android 环境）
cd /data/local/tmp/afx && unzip -o -q /system/framework/framework.jar 'classes*.dex'
/apex/com.android.art/bin/dexdump -d classes4.dex > dd.txt
grep -n "Landroid/util/AtomicFile;" dd.txt   # 定位类
# 该类内可见: invoke-static {v4}, Landroid/os/FileUtils;.sync:...
```
