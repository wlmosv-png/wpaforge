# WPAForge

> Android WPA 密码破解工具 —— 逐条真实连接，带目标派生、智能排序、断点续跑与后台队列。

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2026%2B-green.svg)]()
[![Version](https://img.shields.io/badge/version-0.9.5-orange.svg)]()

**中文** | [English](README_EN.md)

> **代码审阅者请看 [`doc/TECHNICAL-NOTES.md`](doc/TECHNICAL-NOTES.md)** ——
> 那里只讲设计取舍和踩过的坑（配置复用假阳性、判定时序、`finally` 覆盖结果等），
> 比本文件更适合评估实现质量。

---

<p align="center">
  <img src="docs/screenshots/all.png" alt="WPAForge 界面" width="100%">
</p>

## 这是什么

WPAForge 通过系统的 `cmd wifi` 接口逐条尝试连接目标网络，用真实的关联结果判断密码是否正确。

和其它同类工具的**关键区别**在于：

| | WPAForge | 常见 WiFi 破解工具 |
|---|---|---|
| **目标派生** | 按 SSID / BSSID 现场生成候选，几百条覆盖常见场景 | 只能按字典行序盲试 |
| **智能排序** | 按目标特征给候选打分重排，最可能的密码排最前 | 无 |
| **断点续跑** | 进度实时存盘，中断后从原位置继续 | 中断即从头再来 |
| **多目标队列** | 排队自动执行，每项可单独暂停/继续 | 一次只能一个 |
| **后台运行** | 前台服务 + 通知栏进度，锁屏继续跑 | 部分支持 |
| **在线字典** | 10 个公开来源一键下载，边下边过滤 | 需自行寻找 |
| **算法透明** | 加密实现自研，过 RFC 3174 / 2202 / 6070 三套标准向量 | 黑盒 |

---

## 技术亮点

### 1. 三种执行通道，一套判定

所有模式共用一个 **`CrackEngine`**。通道只负责"把一条候选发出去"，
判定完全在引擎里做。

```
                    ┌──────────────────┐
   三种执行模式 ───→ │   CrackEngine    │ ←── 唯一判定入口
   (Root/Shizuku/   │  （引擎，判定）   │
    免root)         └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ShellChannel   ShellChannel   requestNetwork
        (Root/su)      (Shizuku)      (系统精确连接)
```

| 通道 | 命令 | 每条耗时 | 弹窗 |
|---|---|---|---|
| Root | `su -c "cmd wifi ..."` | ~1 秒 | 无 |
| Shizuku | shell 身份执行同一条命令 | ~1 秒 | 无 |
| 免 root | `WifiNetworkSpecifier` + `requestNetwork` | 7~15 秒 | **每条一次** |

**为什么免 root 用 `requestNetwork`**：这是 Android 10+ 唯一能让普通应用
**精确控制单次连接**的接口 —— 你指定 SSID + 密码，系统立刻去连，
成功失败通过 `onAvailable` / `onUnavailable` 明确回调。

不用 `WifiNetworkSuggestion` 的原因：它是**白名单**，提交后由系统自己挑时间连，
应用无法知道它到底试没试，判定只能靠猜。

**免 root 的弹窗无法避免** —— 那是 Android 的安全边界，不是实现缺陷。
所以它定位是兜底方案；真要用请上 Shizuku（一次 ADB 激活，之后和 root 同速）。

### 1.1 多信号综合判定

任何单一信号都不可靠：

| 信号 | 单独使用的问题 |
|---|---|
| `cmd wifi status` 文本 | Shizuku 通道可能被截断；格式随 ROM 变化 |
| `onAvailable` 回调 | 某些 ROM 会因为**别的网络**触发 |
| `WifiInfo.getSSID()` | Android 10+ 受限，更新有延迟 |

引擎同时看三个，任一确认即成功，并把**是哪个信号确认的**写进结果 ——
日志里能直接区分"假阳性"和"真实成功"。

实测时序（真机，OnePlus PJX110 / Android 15）：

```
正确密码 → T+1 秒       连上
错误密码 → T+1~6 秒     not connected
           T+7 秒       回退到别的 AP
```

所以判定有一条硬规则：**前 3.5 秒绝不判负**。
早期版本 400ms 就判负，导致正确密码被误杀 —— 用户看到的现象就是
"密码明明对却识别不到"。

### 1.2 两个必须处理的坑

**坑一：配置复用导致假阳性**

`cmd wifi connect-network` 对同名网络会**复用已保存的配置**。
目标之前连过的话，不论传什么密码，系统都拿旧配置去连 → 连上了 → 报成功。
实测密码填 `00000000` 也能"命中"。

处理：每条之前删配置，**删完回读校验**（最多 3 轮），
并且开跑前若已连着目标，先断开以建立干净基线。

**坑二：命中被覆盖成未命中**

`finally` 块在 `return` 之后仍会执行，导致收尾回调发两次，
第二次密码为 `null`，把 `DONE_HIT` 覆盖成 `DONE_MISS`。

处理：加 `finished` 标志。

> 这两个 bug 原本在**两条通道里各有一份**，因为判定逻辑有两份代码。
> 这是把所有通道收敛到一个引擎的直接原因。

### 2. 目标派生：往字典里补它没有的

智能排序只能**重排已有的**候选。如果字典里没有 `FAST_E972123` 这种派生密码，它永远排不出来。

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

**关键设计**：WPA 强制密码至少 8 位，但用户想设的常是 6–7 位（`972888`、`209209`）。所以对不足 8 位的**自动补几种最可能的填法**，而不是丢弃。

7 个类别按**轮流交错**合并，保证前几十条就覆盖所有类别。

### 3. 智能排序：让对的那条更早出现

不增删任何候选，只重排顺序。评分维度：

| 维度 | 权重 |
|---|---|
| SSID 完全匹配 | +900 |
| SSID 前缀匹配 | +400 |
| SSID 内数字串 | +350 |
| BSSID 尾号 | +320 |
| 通用高频弱密码 | +260 |
| 长度接近 8–11 位 | +60 |
| 纯数字偏好 | +45 |

实测（目标 `B209`，BSSID 尾号 `45DCED`）：

```
输入: aardvark, zzzzzzzzzz, B209123, randompass, 45dced, 12345678

输出: B209123 → 45dced → 12345678 → zzzzzzzzzz → aardvark → randompass
      ↑SSID    ↑MAC尾号  ↑高频弱密码        无关的沉底
```

### 4. 为什么速度是 1 秒/条

AP 对错误密码的响应速度是物理限制。手机一次只能连一个网络，**无法并行**——这不是实现问题。

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

全部测试（141 项，全部通过）：

```
SelfTest        36 / 36   加密算法（SHA-1 / HMAC-SHA1 / PBKDF2，RFC 向量）
PrivScanTest    21 / 21   特权扫描输出解析（中文 SSID、含空格、隐藏网络）
RuleTest        25 / 25   规则字典解析与展开
SortTest        14 / 14   智能排序
TargetDictTest  17 / 17   目标派生
E2ETest         10 / 10   握手比对端到端
PersistTest      5 /  5   候选生成 → 落盘 → 读回一致性
SpecifierTest   13 / 13   判定语义（超时 / SSID 不匹配**绝不**判成功）
```

`SpecifierTest` 是防回归的关键 —— 它把判定语义写死成测试，
确保"超时不能算成功"这类规则不会被后续改动破坏。

---

## 功能

```
观测页   扫描周边网络 · 筛选（全部/加密/开放/隐藏）· 信号柱状图 · 信道分析
审计页   内置生成器 · 加载字典 · 密码强度检查
破解页   选目标 · 选字典 · 目标派生开关 · 智能排序开关 · 队列 · 实时候选流
字典管理 新建(普通/规则) · 导入 · 在线下载 · 合并 · 查看 · 编辑 · 统计
设置页   本机已存 WiFi 自查 · 破解记录 · 导出
关于页   项目介绍 · 作者链接
```

**界面语言**：自动跟随系统（中文系统显示中文，其余显示英文），也可在设置里手动切换。

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

`assets/builtin/` 中的基础字典来自**公开的弱密码统计数据集**与通用规律生成表（弱密码排行、数字规律、字母数字组合、常见英文词频）。这些是社区长期公开的安全研究数据，不是任何单一作品的原创内容。

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
