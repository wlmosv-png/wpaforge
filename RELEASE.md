<!-- GitHub Release 正文。标题填：WPAForge 1.9.24 · 精简：只保留 Root 通道，Shizuku 与免 Root 彻底移除 -->
<!-- tag: v1.9.24   target: main -->

更少的依赖 · 更少的失败路径 · 更清楚的预期

⚠️ 运行环境：需要 Root（Android 8.0+）

---

## 🧹 移除：Shizuku 与免 Root 通道彻底删除

从代码到清单、从依赖到界面，两条通道**已彻底删除**：

- 删掉 `ShizukuRunner` / `SafeShizukuProvider` / `SpecifierProber` / `SuggestionProber` /
  `ConnectionProber` 五个类，以及随包附带的三个 Shizuku jar
  —— **本应用不再依赖任何第三方库**
- AndroidManifest 里的 Shizuku 权限、包可见性声明、V3 协议 meta-data 与 Provider 全部撤掉
- 界面残留清干净：失败弹窗里的模式切换入口、设置页的授权引导、队列行的模式标签
- 队列里老任务存过的旧模式值（1=免 Root / 2=Shizuku）一律按 Root 处理，
  不会因为一个历史值落到一条已经不存在的通道上

## 📋 为什么

- Shizuku 通道每次关联都要借一次 binder；免 Root 通道（`WifiNetworkSpecifier`）
  每条候选都要弹一次系统确认框 —— 两者都**撑不起逐条字典遍历**
- 留着它们的代价是真实的：探测逻辑、模式分支、第三方依赖、清单声明，
  以及「明明选了却跑不动」的困惑

## 📋 兼容性与升级

- **运行环境：需要 Root（Magisk / KernelSU 等）**，判定依赖 `su + cmd wifi`
- 覆盖安装即可，签名与配置保持一致，**已保存的目标与破解进度完整保留**
- 系统要求：Android 8.0 及以上（minSdk 26）

回归测试 990 用例，全部通过。
