# AtomicFile 真机行为证据（P0-3 实测）

**日期**：2026-11-01 · **设备**：OnePlus PJX110 / Android 15
**方法**：`d8` 转 dex + `app_process`，直接用 `StoreIo.FileImpl` 操作真实文件。

## 一、事务写（beginWrite / finishWrite / abortWrite）

```
1. 首次事务写
   beginWrite 后 : q.json.new(0)              ← 原文件不存在，只造临时文件
   finishWrite 后: q.json(3)                  ← 临时文件 rename 成目标
   finishWrite=true 内容=AAA

2. 覆盖事务写
   beginWrite 后 : q.json.new(0) q.json(3)    ← **原文件完好，未被删除**
   finishWrite 后: q.json(4)  内容=BBBB

3. abortWrite 回滚
   abortWrite 后 : q.json(4)  内容=BBBB       ← 原文件字节不变，临时文件被删

4. 只 beginWrite 不提交（模拟崩溃）
   崩溃后        : q.json.new(4) q.json(4)
   base 内容     = BBBB                        ← **原文件完好**

5. 新实例读取
   读到          = BBBB                        ← 内容未被半截污染
```

**结论**：事务语义成立。`beginWrite` 不改动原文件；`abortWrite` 与「未提交」都不会污染 base。

## 二、`.bak` 冲突（关键，影响设计）

```
A. 写时是否吞掉同名 .bak
   造 .bak 后    : q.json.bak(11)
   写入 q.json 后: q.json(3)
   .bak 内容     = <FileNotFoundException>     ← **.bak 被删除**

B. 读时是否用 .bak 覆盖 base
   初始          : q.json(9) q.json.bak(8)
   读到          : BASE_DATA                   ← 读的是 base，未被 .bak 覆盖
   读后目录      : q.json(9) q.json.bak(8)     ← 两份都还在
```

### 与早期结论的差异（如实记录）

设计文档 v3 §0.2 曾记录「`openRead` 会用 `.bak` 覆盖 base」。
本次用 `StoreIo.FileImpl` 直接验证：**读路径不覆盖**（场景 B 读到 BASE_DATA 且两份都在）。
早期探针的差异可能来自 `readFully()` 与 `openRead()` 的路径不同。

**但写路径的结论更严重且已确证**：**事务写会删除同名 `.bak`**（场景 A）。

### 对实现的影响

- 应用层**绝不能再写 `<base>.bak`**：任何一次 `save` 都会把它删掉，备份能力被静默摧毁。
- 因此 P0-3 已把清空快照改名为 **`<base>.snapshot-<ts>`**（带时间戳、冲突检测），
  与 AtomicFile 的文件空间完全隔离。
- `queue.json.bak`（历史遗留）由 M4 隔离机制保护，不删除、不覆盖。

## 三、边界声明

| 保证 | 状态 |
|---|---|
| beginWrite 不改动原文件 | ✅ 实测 |
| abortWrite / 未提交不污染 base | ✅ 实测 |
| finishWrite 后内容完整 | ✅ 实测 |
| 事务写在真机可用 | ✅ 实测 |
| 写事务会删除同名 `.bak` | ✅ 实测（**设计据此避开**） |
| 读路径不覆盖 base | ✅ 实测（**修正早期结论**） |
| 目录项持久化 / 掉电安全 | ❌ 无 API，**不宣称** |

## 四、复现

```bash
javac --release 17 -cp android.jar:/workspace/wpf-test/classes -d cls FileImplProbe.java
java -cp d8.jar com.android.tools.r8.D8 --min-api 26 --lib android.jar \
     --output dex cls/FileImplProbe.class \
     /workspace/wpf-test/classes/io/github/wlmosv_png/wpaforge/audit/StoreIo*.class
cp dex/classes.dex /data/local/tmp/afx/fi.dex
cd /data/local/tmp/afx && CLASSPATH=$PWD/fi.dex app_process /system/bin FileImplProbe
```
