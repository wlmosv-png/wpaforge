# org.json 桌面/设备行为差分证据

**日期**：2026-11-01
**设备**：OnePlus PJX110 / Android 15
**目的**：证明测试用 stub（AOSP libcore 源码编译）与设备真实 org.json **行为一致**，
而不是仅凭"引用 AOSP 源码"就宣称已验证。

## 方法

同一份 Java 用例（`JsonDiffProbe`）：
- 桌面：`javac -cp wpf-test/stub:android.jar` → 走 stub 的 AOSP 实现
- 设备：`d8` 转 dex → `app_process` → 走系统真实实现

输出逐行比对。

## 结果

| 用例 | 桌面（stub） | 设备（真实） | 一致 |
|---|---|---|---|
| C1 空文件 | THROW JSONException | THROW JSONException | ✅ |
| C2 纯空白 | THROW JSONException | THROW JSONException | ✅ |
| C3 非法 JSON `{"not":"array"}` | THROW JSONException | THROW JSONException | ✅ |
| C4 截断 `[{"id":"a"},` | THROW JSONException | THROW JSONException | ✅ |
| C5 合法空数组 `[]` | OK len=0 | OK len=0 | ✅ |
| C6 非对象元素 `[42]` | THROW JSONException | THROW JSONException | ✅ |
| C7 混合 `[A,B,42]` 取第 3 个 | THROW JSONException | THROW JSONException | ✅ |
| C8 缺失字段 optString | OK `<dflt>` | OK `<dflt>` | ✅ |
| C9 类型不匹配 `{"id":123}` optString | OK `'123'` | OK `'123'` | ✅ |
| C10 null 元素 `[null]` getJSONObject | THROW JSONException | THROW JSONException | ✅ |

**10/10 完全一致，零差异。**

## 对实现的含义

1. `new JSONArray("")` 与 `new JSONArray("   ")` 都抛异常
   → 所以 TaskQueue 里「空/空白内容判 CORRUPT」与真实行为一致；
   同时说明「合法的 `[]`」必须与「空文件」分开处理（已实现）。
2. `getJSONObject` 遇到非对象元素抛 JSONException
   → 「部分解析整体失败」的判定依据在真机成立。
3. `optString` 对缺失字段返回默认值、对数字返回字符串
   → **结构完整但业务字段缺失/类型错，不会被 JSON 层发现**。
   这属于业务完整性校验（P0 设计文档 §4 C 项），当前**未实现**，列为风险。

## 复现

```bash
# 桌面
javac --release 17 -cp "/workspace/wpf-test/stub:android.jar" -d cls JsonDiffProbe.java
java -cp "/workspace/wpf-test/stub:cls:android.jar" JsonDiffProbe

# 设备
java -cp d8.jar com.android.tools.r8.D8 --min-api 26 --lib android.jar --output dex cls/JsonDiffProbe.class
cp dex/classes.dex /data/local/tmp/afx/jd.dex
cd /data/local/tmp/afx && CLASSPATH=$PWD/jd.dex app_process /system/bin JsonDiffProbe
```
