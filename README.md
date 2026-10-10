# WPAForge

> Android WPA 口令破解工具 —— 逐条真实连接，带目标派生、智能排序、断点续跑与后台队列。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2026%2B-green.svg)]()
[![Version](https://img.shields.io/badge/version-0.5.0-orange.svg)]()

**中文** | [English](README_EN.md)

---

## 这是什么

WPAForge 通过系统的 `cmd wifi` 接口逐条尝试连接目标网络，用真实的关联结果判断口令是否正确。

和其它同类工具的**关键区别**在于：

| | WPAForge | 常见 WiFi 破解工具 |
|---|---|---|
| **目标派生** | 按 SSID / BSSID 现场生成候选，几百条覆盖常见场景 | 只能按字典行序盲试 |
| **智能排序** | 按目标特征给候选打分重排，最可能的口令排最前 | 无 |
| **断点续跑** | 进度实时存盘，中断后从原位置继续 | 中断即从头再来 |
| **多目标队列** | 排队自动执行，每项可单独暂停/继续 | 一次只能一个 |
| **后台运行** | 前台服务 + 通知栏进度，锁屏继续跑 | 部分支持 |
| **在线字典** | 10 个公开来源一键下载，边下边过滤 | 需自行寻找 |
| **算法透明** | 加密实现自研，过 RFC 3174 / 2202 / 6070 三套标准向量 | 黑盒 |

---

## 技术亮点

### 1. 为什么用 `cmd wifi` 而不是 `WifiNetworkSpecifier`

Android 10+ 的 `WifiNetworkSpecifier.requestNetwork()` 会**强制弹出系统确认框**，普通应用无法静默连接。这意味着用它跑字典，每一条都要用户手点一次——500 条要点 500 次。

实测错误：

```
ForegroundServiceStartNotAllowedException:
Service.startForeground() not allowed due to mAllowStartForeground false
```

`cmd wifi connect-network` 是 shell 权限的命令，不受这条限制。root 可以调用，**不弹窗、结果可读**。

实测行为：

```
正确密码 → 约 12 秒后  Wifi is connected to "<SSID>"
错误密码 → 约 3 秒后   Wifi is not connected
```

**一个必须处理的坑**：如果系统里存着目标网络的旧配置，`connect-network` 会复用旧凭据，导致**假成功**。所以每条尝试前必须先 `forget-network` 清掉。

### 2. 目标派生：往字典里补它没有的

智能排序只能**重排已有的**候选。如果字典里没有 `FAST_E972123` 这种派生口令，它永远排不出来。

`TargetDictBuilder` 根据目标现场造：

```
输入:  SSID = FAST_E972
       BSSID = AA:BB:CC:E9:72:11

生成:  FAST_E972 / fast_e972 / FASTE972          ← 原文与变形
       97297297 / 97297200 / 97297288            ← 数字重复族
       e9721100 / e9721188 / E97211E9            ← BSSID 尾号族
       e972123e / e9721230 / e97212345          ← 片段 + 数字
       E972E972 / e972e972                       ← 片段重复
```

**关键设计**：WPA 强制口令至少 8 位，但用户想设的常是 6–7 位（`972888`、`209209`）。所以对不足 8 位的**自动补几种最可能的填法**，而不是丢弃。

7 个类别按**轮流交错**合并，保证前几十条就覆盖所有类别。

### 3. 智能排序：让对的那条更早出现

不增删任何候选，只重排顺序。评分维度：

| 维度 | 权重 |
|---|---|
| SSID 完全匹配 | +900 |
| SSID 前缀匹配 | +400 |
| SSID 内数字串 | +350 |
| BSSID 尾号 | +320 |
| 通用高频弱口令 | +260 |
| 长度接近 8–11 位 | +60 |
| 纯数字偏好 | +45 |

实测（目标 `B209`，BSSID 尾号 `45DCED`）：

```
输入: aardvark, zzzzzzzzzz, B209123, randompass, 45dced, 12345678

输出: B209123 → 45dced → 12345678 → zzzzzzzzzz → aardvark → randompass
      ↑SSID    ↑MAC尾号  ↑高频弱口令        无关的沉底
```

### 4. 为什么速度是 1 秒/条

AP 对错误口令的响应速度是物理限制。手机一次只能连一个网络，**无法并行**——这不是实现问题。

| 字典大小 | 耗时（约 1 秒/条） |
|---|---|
| 500 | 8 分钟 |
| 5,000 | 1.4 小时 |
| 50,000 | 14 小时 |

所以**字典的质量远比体积重要**。这也是目标派生 + 智能排序存在的原因。

---

## 加密实现

WPA 密钥派生按 **IEEE 802.11i** 规范手写实现，不依赖第三方加密库：

```
PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 256bit)
PTK = PRF-512(PMK, "Pairwise key expansion",
              min(AA,SPA) || max(AA,SPA) || min(ANonce,SNonce) || max(ANonce,SNonce))
MIC = HMAC-SHA1(KCK, EAPOL_frame_with_MIC_zeroed)[0..16)
```

### 验证结果

| 组件 | 测试向量 | 结果 |
|---|---|---|
| SHA-1 | RFC 3174 / FIPS 180-1 | ✅ 3/3 |
| HMAC-SHA1 | RFC 2202 | ✅ 3/3 |
| PBKDF2-HMAC-SHA1 | RFC 6070 | ✅ 3/3 |
| PMK | Wi-Fi 联盟公开向量 | ✅ 1/1 |

全部测试（102 项）：

```
SelfTest        36 / 36   加密算法（RFC 向量）
RuleTest        25 / 25   规则字典解析与展开
E2ETest         10 / 10   握手比对端到端
SortTest        14 / 14   智能排序
TargetDictTest  17 / 17   目标派生
```

---

## 功能

```
观测页   扫描周边网络 · 筛选（全部/加密/开放/隐藏）· 信号柱状图 · 信道分析
审计页   内置生成器 · 加载字典 · 口令强度检查
破解页   选目标 · 选字典 · 目标派生开关 · 智能排序开关 · 队列 · 实时候选流
字典管理 新建(普通/规则) · 导入 · 在线下载 · 合并 · 查看 · 编辑 · 统计
设置页   本机已存 WiFi 自查 · 破解记录 · 导出
关于页   项目介绍 · 作者链接
```

**破解页的三层候选策略**：

```
第 1 层  目标派生     ~500 条   按 SSID/BSSID 现场造
第 2 层  智能排序     全量      按目标特征重排
第 3 层  字典原序     全量      兜底
```

---

## 构建

本项目**不依赖 Gradle**。构建链是手工两段式——因为 `aapt2` 与 `d8` 在 Android 上是 arm64 原生二进制，桌面侧跑不了；而桌面侧有 javac。

```sh
# 第一段：Android 侧（aapt2 编译资源 + link 出 base.apk）
sh 1-aapt2.sh

# 第二段：桌面侧（javac + d8 + 组装 + 签名）
sh 2-build-dex.sh
```

产物：`build/out/wpaforge-signed.apk`

**为什么这么设计**：省去 SDK 下载与网络依赖，构建链完全显式可控。代价是要两段跑。

### 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/builtin/          内置字典（首次启动释放到设备）
├── res/                     图标、字符串
└── java/io/github/wlmosv_png/wpaforge/
    ├── MainActivity.java            主界面（四页签）
    ├── DictActivity.java            字典管理
    ├── AboutActivity.java           关于页
    ├── CrackService.java            前台服务（队列执行）
    ├── core/NetRecord.java          网络模型
    ├── scan/                        扫描 + 信道分析
    ├── audit/                       算法层（加密、字典、排序、队列）
    └── ui/                          自绘视图
```

---

## 权限

| 权限 | 用途 |
|---|---|
| `NEARBY_WIFI_DEVICES` | Android 13+ 读取扫描结果 |
| `ACCESS_FINE_LOCATION` | Android 12 及以下读取扫描结果 |
| `CHANGE_NETWORK_STATE` | `requestNetwork` 必需 |
| `FOREGROUND_SERVICE` | 后台破解 |
| `POST_NOTIFICATIONS` | 进度通知 |

**root 权限**：破解功能需要。用于调用 `cmd wifi` 命令驱动连接。

---

## 使用边界

**仅对自己拥有授权的网络使用**（自有路由器、实验室、授权渗透测试）。

WPAForge 会向目标 AP 发起**真实的关联请求**。对自有网络是自查；对未授权网络：

- 是违法行为
- 路由器侧会记录失败尝试（含 MAC 与时间）
- 长时间高频尝试可能造成 AP 连接表压力

请自行确保对目标网络拥有明确授权。

---

## 内置字典来源

`assets/builtin/` 中的基础字典来自**公开的弱口令统计数据集**与通用规律生成表（弱口令排行、数字规律、字母数字组合、常见英文词频）。这些是社区长期公开的安全研究数据，不是任何单一作品的原创内容。

在线字典功能从 [SecLists](https://github.com/danielmiessler/SecLists)（MIT 许可）等公开项目下载，数据落于用户本机，本仓库不包含这些数据。

---

## 许可

Apache License 2.0 — 见 [LICENSE](LICENSE)

## 作者

**wlmosv**

- GitHub: https://github.com/wlmosv-png
- Telegram: [@wlmosv_bunny](https://t.me/wlmosv_bunny)

### 其它项目

**[TGAutoSign](https://github.com/wlmosv-png)** — Telegram 自动签到的 Xposed 模块。多账号槽位、群签到判定、定时计划、多主题界面。管理入口：任意聊天发 `/jmb`。

**[apk-index](https://github.com/wlmosv-png)** — APK 静态索引与逆向检索工具。解 AXML、逐 dex 建索引、按字符串/签名/结构反查 hook 点。
