# UI 改造基线报告

**冻结时间**：2026-11-21
**用途**：UI 全面升级改造的不可覆盖基线

---

## 一、基线状态

| 项 | 值 |
|---|---|
| 仓库 | `/workspace/promo-wpaforge` |
| remote | `https://github.com/wlmosv-png/wpaforge.git` |
| 冻结时分支 | `recovery/restore-v1.9.60` |
| 冻结时 HEAD | `baf405c7ba36a9892c6ce02f88b559e830f5e346` |
| 工作区 | 干净（`git status --porcelain` 空） |
| 版本号 | **1.9.60 (156)** |
| main 分支 | `db97138d46dce649f1900fcf7daee4fe1bde00d4`（未动） |

## 二、基线 tag

```
baseline-before-ui-redesign-20261121  →  baf405c
```

创建为 annotated tag，含完整说明。**不可覆盖、不可移动。**

## 三、改造分支

```
feature/ui-redesign-20261121  （从 baf405c 创建）
```

所有 UI 工作只在此分支进行。

## 四、基线 APK

```
路径    : /sdcard/Download/wpaforge-1.9.60-156-recovery.apk
大小    : 1,748,415 字节
SHA-256 : 7f83d654f93cccbfb7043be9d9708b4c96c87dee131ae4beac8255a068fe7eba
包名    : io.github.wlmosv_png.wpaforge
版本    : 1.9.60 (156)
签名    : CN=wlmosv, OU=TGAutoSign, O=wlmosv, C=CN
          SHA-256 af5524cd554ae6ede3ec2747c7bed159b09bc4f6a43244a68b4622e9463225c8
```

## 五、基线测试结果

```
门禁：8273 通过 / 7 失败 / 218 个测试类
```

**7 项失败均为历史既有**（v1.9.60 发布说明已记录 6 项，另 1 项为门禁偶发竞态）：

| 测试类 | 失败数 | 性质 |
|---|---|---|
| `audit/P05AttributionTest` | 2 | 历史既有（归属证据链） |
| `audit/UnifiedVerdictIntegrationTest` | 4 | 历史既有（U3/U6×2/U7） |
| `audit/AuthCastAttributionTest` | 0-1 | **门禁偶发**，单独跑连续 3 次均 16/0 |

**本次任务不修复这些失败，不删除测试，不放宽断言。**

## 六、后端核心文件指纹（零改动验证基准）

以下文件的 MD5 在改造完成后必须**完全一致**：

```
ae8138328a38c5921d0d4b84d3042188  audit/CrackEngine.java
289cbb9bb64d3994fab74fcf78566dad  CrackService.java
bc2cb8bb4ccde7fc5463557fc2c8122e  audit/Adjudicator.java
cbaa5ac7ed682a3cc2a931d68cf71183  audit/SuccessRule.java
d5322a78d081189de9c0349c5adb45f1  audit/EvidenceGrade.java
f5bb68496a404b6b8aa24964f496c9e4  audit/AttemptState.java
b6c09d01472283210c8456152ea20d8e  audit/VerdictTally.java
0c2fbe3fd6d9676362d333c8f4964e0e  audit/AttemptLedger.java
0f47d02232d324d486a3db74aa03564c  wps/WpsEngine.java
4e1ee9a03657694039fde6dcb862eee8  audit/RootProber.java
```

## 七、虚拟屏环境（已验证可用）

| 项 | 值 |
|---|---|
| 工具 | Eta 内置 VirtualDisplay（`launch_app display=virtual`） |
| display_id | 3 |
| 分辨率 | 1080 × 2376 |
| 实际渲染 | ✅ 已验证（观测页完整渲染，截图 285,585 字节） |

**注意**：本机无 Android Emulator、无 adb、无 KVM。虚拟屏是 Android 系统级 `VirtualDisplay`，不是模拟器。它能真实渲染 App 并截图，满足「必须实际启动 App 观察渲染」的要求。

## 八、保护清单（改造期间不得触碰）

| 对象 | 值 | 状态 |
|---|---|---|
| `baseline-before-ui-redesign-20261121` | `baf405c` | 新建，不可覆盖 |
| `baseline-before-rootless-20261118` | `8672270` | 保留 |
| `v1.9.60` tag | `e8a442e` | 保留，不可移动 |
| `main` | `db97138` | 保留，不可改动 |
| `feature/rootless-adaptation` | `e6bc85a` | 保留 |
| `recovery/restore-v1.9.60` | `baf405c` | 保留 |
| 回滚点 `b79f025` | `b79f025` | 保留 |
