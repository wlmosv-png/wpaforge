# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.1.1] - 2026-10-11

**判定换血：不再依赖系统回调，改用 WiFi 状态机日志作为主判据。**

读透了参考项目（bszapp/android-wifi-pojie v3.x）的实现后做的架构调整。
参考项目用 wpa_supplicant 的 `WPA: Key negotiation completed` 判成功，但实测
本机（Color OS 15 / Android 15）**不输出这一行**，所以换用本机确实存在的证据。

### 新增

- **`WifiStateLog`**：解析 `WifiClientModeImpl` 状态机日志。
  - `ClientModeImpl$L2ConnectedState ... "SSID" AA:BB:CC:...` → **已连上该 AP**（成功判据）
  - `ClientModeImpl$ConnectableState ... "SSID"` → **正在尝试**（区分「没试」和「试了失败」）
  - 关键设计：失败后系统**回连别的已保存网络**时，`L2ConnectedState` 那行**不带 SSID**。
    只有 SSID 明确的连接才算命中，从根上排除「连上了别的网络」造成的假阳性。

### 修复（判定链）

- **主判据换成状态机日志**：`tryOneShell` 每轮读一次 `WifiClientModeImpl`，
  发现目标 SSID 的 `L2ConnectedState` 即判成功。之前依赖 `NetworkCallback`，
  而本机回调经常不触发或拿不到 SSID，导致**密码正确也判不出**。
- **三重印证**：状态机日志 / NetworkWatcher 事件 / `WifiInfo` 当前 SSID 任一确认即成功。
- **归因加固**：记录命令前的 SSID，只有当“当前连着目标且与命令前不同”才算命中，
  避免把命令前就存在的连接算到本条候选头上。
- **归类修正**：结束时「见过尝试但没连上」判 `AUTH_FAIL`；
  **「连尝试都没见到」判 `UNKNOWN`** 而不是 AUTH_FAIL —— 那是通道问题，
  不该把正确密码误报成密码错误。

### 实测验证（真机，非推测）

```
正确密码 12345678 → ConnectableState "B209" → L2ConnectedState ... "B209" 28:93:7d:...  ✓ 命中
错误密码 00000000 → ConnectableState "B209" → 无 B209 的 L2ConnectedState，回连 211    ✓ 不误判
```

### 测试

- 新增 `WifiStateLogTest` 15 项（含真机日志样本、回连场景、含空格 SSID）。
- 全部 13 个测试类共 **214 项** 全通过。

---

## [1.1.0] - 2026-10-11

- 去掉 `tryOneShell` 中 `MIN_HIT_MS` 时间门槛。用户日志证明目标已产生可用事件，
  却因「间隔 < 1.5 秒」被拒 → 误判 UNKNOWN，导致正确密码被跳过。
  归因已由 `firstAvailableAfter(ssid, commandAt)` 保证，门槛多余且有害。

## [1.0.4] - 2026-10-11

- 修 `tryOneShell` 里事件时间戳固定导致永远超时判 AUTH_FAIL 的 bug。

## [0.6.0] - 2026-10-11

**Shizuku 模式真正可用了。** 之前它显示「服务未运行」，而 Shizuku 应用里
明明显示 WPAForge 的开关是打开的 —— 根因是依赖不完整，不是权限问题。

### 修复（根因）

- **缺 `shizuku-aidl.jar`**：`rikka.shizuku.Shizuku` 的静态初始化块里有一句
  `SHIZUKU_APPLICATION = new IShizukuApplication(){...}`，而
  `moe.shizuku.server.IShizukuApplication` 这一系列 AIDL 类**不在 `shizuku-api.jar` 里**
  （官方把它拆成独立的 `dev.rikka.shizuku:aidl` 构件）。
  结果：`Shizuku` 类 `<clinit>` 直接 `NoClassDefFoundError`，
  **每一次 `pingBinder()` 都在类加载阶段就失败**，被 catch 后返回 false，
  界面只能显示「服务未运行」。
  现在补上 `aidl-13.1.5` 的 16 个类（`IShizukuApplication` / `IShizukuService` /
  `IRemoteProcess` / `IShizukuServiceConnection` 及其 Stub）。
- **binder 是异步推来的，却只在需要时查一次**：Shizuku 的 binder 通过
  ContentProvider 异步交给客户端。应用启动时服务还没起来（或刚重启过），
  `pingBinder()` 就一直 false 且**不会自己重试**。
  现在在 `Application.onCreate` 就注册 `addBinderReceivedListenerSticky`
  —— sticky 版在注册时若 binder 已就绪会**立即回调**，之后服务重启还会再回调。
- **授权结果回调走错了 API**：Shizuku 的授权结果是
  `addRequestPermissionResultListener`，不是 `Activity.onRequestPermissionsResult`。
  上一版接错了地方，授权状态永远不会刷新。
- **补全授权流程**：切到 Shizuku 模式现在会走完整链路 ——
  没装 → 给下载指引；装了没启动 → 一键跳 Shizuku 应用；没授权 → 直接弹授权框。

### 新增

- **Shizuku 通道自测**：启动时等 binder 就绪后真跑一条 `cmd wifi help`，
  结果写进启动自检。现在那份文件里会直接写着
  `Shizuku 通道实测: 可用（cmd wifi connect-network 已确认）` ——
  「能 ping 到 binder」和「能执行命令」是两回事，自测把后者也验了。
- **设置页模式状态可点击重测**，并带上模式名前缀，不会再出现
  「卡片写 Root、状态行说免 Root」的误读。

### 验证

本机（OnePlus PJX110 / Android 15 / Shizuku 13.6.0）实测输出：

```
-- 特权通道 --
  Shizuku 服务: 在
  Shizuku 授权: 已授权
  Shizuku 版本: 13
  Shizuku 通道实测: 可用（cmd wifi connect-network 已确认）
  特权扫描通道可用: 是
```

---

## [0.5.7] - 2026-10-11

### 修复（三种执行模式）

- **Shizuku 模式根本没有授权入口**：`ShizukuRunner.requestPermission()` 写了却从未被调用，
  `onRequestPermissionsResult` 也没有处理 Shizuku 的回调。表现是——选了 Shizuku、
  点开始，永远停在「尚未授权本应用」，而界面上**没有任何地方能触发授权**，死循环。
  现在切到 Shizuku 会自动走完整流程：没装→给下载指引；装了没启动→可一键跳到
  Shizuku 应用；没授权→直接弹系统授权框；授权结果回写到界面。
- **Shizuku 通道命令被错误包了一层 su**：`RootProber.execAsync()` 在 Shizuku 分支
  用 `String.join(" ", {"su","-c",X})` 拼出 `su -c X` 丢给 ShizukuRunner。
  Shizuku 本来就是 shell 身份、**没有 su**，命令必然失败。现在会剥掉 su 包装，
  只把真实命令交给 shell 身份执行。
- **免 Root 模式的失败原因全部丢失**：`addNetworkSuggestions` 返回非成功时只写
  logcat，界面上永远只有一句「本批未成功」。现在会把系统状态码翻成人话
  （权限缺失 / 重复条目 / 系统拒绝 / WEP 不支持）直接显示在候选流里。

### 改进（设置页与布局）

- **设置页补齐第三个模式**：之前只有「Root 模式」和「免 Root」两个按钮，
  Shizuku 只能从破解页的「执行方式」里换，用户在设置里根本找不到。现在是三个并排。
- **模式可用性实时检测**：设置页当前模式下方新增一行状态，直接显示
  `✓ Root 通道已就绪` 或 `✗ 尚未授权本应用` —— 不用点开始就知道能不能跑。
- **破解页顶部不再糊成一团**：进度条从状态卡里移出来单独放，并且**默认隐藏**，
  真正开始跑才出现。之前还没开始就画一个孤零零的「0%」，跟三行文字挤在一起。

---

## [0.5.5] - 2026-10-11

### 修复

- **扫描结果为空（根因）**：`NEARBY_WIFI_DEVICES` 曾带 `android:usesPermissionFlags="neverForLocation"`。
  该标志让系统认为本应用不需要位置信息来推断设备，于是在部分 ROM（实测 ColorOS 15）上
  直接把扫描结果里的 SSID\/BSSID 抹掉，返回 0 条且**不报任何错**。
  去掉该标志后 `getScanResults` 从 0 条恢复到 20+ 条。
- **观测页单点依赖**：以前只走 `WifiManager`，一旦拿不到就彻底空白。
  新增特权降级通道 —— 普通 API 无结果时自动通过 root\/Shizuku 执行
  `cmd wifi list-scan-results`（破解功能本就在用的通道），实测可取 24 条。
- **诊断静默**：扫描失败以前只显示「还没扫描到网络」。
  现在把真实原因（权限缺失 \/ WiFi 关闭 \/ 系统节流）直接印在界面上。
- **日志目录不可写**：`\/sdcard\/Download` 在 scoped storage 下对本应用只读，
  覆盖写入会 `EEXIST` 失败。日志改到应用私有外部目录，任何 ROM 上必然可写。
- **版本号显示错误**：设置页与关于页硬编码 `WPAForge 0.5.0 (5)`，
  与实装版本长期不一致。现统一由 `Ver` 类从 `PackageManager` 读取真实
  `versionName`\/`versionCode`，不再有任何手写常量。

### 新增

- **启动自检**：每次启动写一份
  `Android\/data\/<pkg>\/files\/logs\/startup-last.txt`，
  记录权限、WiFi 状态、扫描条数、特权通道可用性 —— 排查不用再靠复述现象。
- **特权扫描解析器**：解析 `cmd wifi list-scan-results` 文本输出，
  支持中文 SSID、含空格 SSID、隐藏网络、5G 频段；含 21 项测试。
- **信道占用横向条**：2.4G 全信道横排，条长表示拥挤度，按拥挤度分档配色（绿→橙→红），
  最空信道标「推荐」。
- **三层分段进度条**（`StagedProgress`）：破解是「整个字典 → 当前批次 → 当前候选」
  的嵌套过程，单条进度条语义会漂。现把三层同时画在一根轨道上（外暗内亮），
  命中后整条转绿。
- **候选流可折叠**：标题行可点，展开占满剩余空间，收起只留最近 5 行，
  把空间让给队列卡片；标题行实时显示行数。
- **本机已存 WiFi 独立卡片**：从设置页「工具」区提到**观测页**，
  显示已保存网络数与弱口令数（弱口令标红），点击查看明细；
  结果缓存在内存，不重复读 root。

### 改进

- **字典页操作收拢**：原两行六个大按钮占掉屏幕上部一整块，
  而六个功能里四个一周用不到一次。现全部收进右下角悬浮按钮（FAB）的弹窗，
  列表直接顶到标题下方。

### 测试

```
SelfTest        36 \/ 36   加密算法（RFC 3174 \/ 2202 \/ 6070 向量）
PrivScanTest    21 \/ 21   特权扫描输出解析
RuleTest        25 \/ 25   规则字典解析与展开
SortTest        14 \/ 14   智能排序
TargetDictTest  17 \/ 17   目标派生
E2ETest         10 \/ 10   握手比对端到端
------------------------ 合计 123 项全过
```

---

## [0.5.0] - 2026-10-10

**正式命名 WPAForge**（原 NetScope）。

### 新增

- **目标派生字典**：按 SSID / BSSID 现场生成候选。7 个类别（SSID 原文与变形、数字族、SSID+数字、BSSID 尾号、字母部分、SSID+字母、片段组合）轮流交错合并，保证前几十条覆盖所有类别
  - 对不足 8 位的片段自动补足（WPA 强制 8 位，但用户常想设 6–7 位）
  - 实测 `FAST_E972` 生成 547 条
- **智能排序**：按目标特征给候选打分重排。SSID 匹配 +900、数字串 +350、BSSID 尾号 +320、高频弱密码 +260
  - 1771 条排序耗时 28ms
  - 不增删候选，只改顺序
- **断点续跑**：每个任务记录 `offset`，进度实时存盘。暂停后从原位置继续
- **多目标队列**：排队自动执行，每项可单独暂停 / 继续 / 移除
- **前台服务**：后台运行，通知栏显示进度，可在通知上暂停/停止
- **破解记录**：每次破解自动落盘（成功/失败、字典、条数、耗时）
- **结果导出**：完整记录 + 纯密码表两种格式
- **本机已存 WiFi 自查**：读取系统配置中的已保存密码，按 0–100 打分给出弱密码评级
- **关于页**：独立页面，作者卡片 + 项目介绍
- **在线字典**：10 个公开来源（SecLists 等），边下边过滤 + 去重
- **内置字典**：8 个基础字典打包进 APK，首次启动释放
  - 不覆盖用户已改过的文件，带版本标记
- **旧目录自动迁移**：从 `NetScope-dict` 迁移到 `WPAForge-dict`

### 改进

- **新图标**：「靶心 + 穿刺箭头」自适应图标，五档密度，48px 下仍可辨识
- **字典管理界面重设计**：功能按钮从横向滚动改为两行三列，全部可见
- **队列卡片重做**：状态徽章（进行中/已暂停/待处理/已破解）+ 进度条 + 卡片底色随状态变化
- **弹窗全部自绘**：`NetDialog` 替代系统 `AlertDialog`，圆角胶囊按钮
- **停止按钮状态管理**：未运行时禁用，避免误点

### 修复

- **`ForegroundServiceStartNotAllowedException`**：Android 12+ 禁止从后台启动前台服务，会直接崩溃。现用 try/catch 捕获并降级为普通后台服务
- **`requestNetwork` 缺权限**：缺 `CHANGE_NETWORK_STATE` 导致 `SecurityException`
- **`removeCapability(NET_CAPABILITY_INTERNET)`**：错误的参数组合，导致请求被系统拒绝
- **假成功**：目标网络的旧配置被复用，导致传入任意密码都显示成功。现在每条尝试前先 `forget-network`
- **配置误删**：`cleanupConfig` 用 `contains` 匹配 SSID，会误删用户真实配置。改为精确匹配且不删除
- **停止按钮无响应**：`cancel()` 只设标志位，工作线程卡在阻塞调用里。改为记录子进程并 `destroyForcibly`
- **组包缺 STORED**：`AndroidManifest.xml` 与 `resources.arsc` 被压缩，Android 会拒绝安装。三者都改为 `zip -0`
- **R.java 未编译**：aapt2 生成的 `R.java` 从未进入 javac 源码列表，一旦引用资源就编译失败
- **资源未重编**：新增图片资源后未重跑 aapt2，导致 `R.drawable` 找不到

### 测试

```
SelfTest        36 / 36   加密算法（RFC 3174 / 2202 / 6070 向量）
RuleTest        25 / 25   规则字典解析与展开
E2ETest         10 / 10   握手比对端到端
SortTest        14 / 14   智能排序
TargetDictTest  17 / 17   目标派生
```

---

## [0.4.0] - 2026-10-10

- 新增关于页（作者卡片、项目介绍）
- 新增本机已存 WiFi 自查
- 新增破解记录与导出
- 加入作者头像（GitHub 头像）

## [0.3.0] - 2026-10-10

- 新增在线字典下载（10 个公开来源）
- 新增智能排序
- 新增字典导入 / 合并 / 编辑 / 统计
- 修复字典导入文件名丢失

## [0.2.0] - 2026-10-10

- 新增规则字典（区间生成：前缀/后缀/起始/末尾/步长/进制）
- 新增字典管理页
- 移除 500 条上限，支持全量破解
- 界面圆角化
- 修复停止按钮无响应

## [0.1.0] - 2026-10-10

首个可用版本。

- 周边网络扫描 + 信道分析
- 自绘雷达图与信号柱状图
- WPA 密钥派生（手写 SHA-1 / HMAC / PBKDF2 / PRF-512）
- 字典生成器
- 逐条连接尝试（`cmd wifi`，root）
- 实时候选流与进度
