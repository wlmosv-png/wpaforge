# 架构

## 分层

```
                    ┌──────────────────────────┐
   界面层            │ MainActivity             │  四页签：观测 / 审计 / 破解 / 设置
   (Android UI)     │ DictActivity             │  字典管理
                    │ AboutActivity            │  关于
                    ├──────────────────────────┤
                    │ CrackService             │  前台服务（队列执行、通知）
                    ├──────────────────────────┤
   算法层            │ audit/                   │  ← 无 Android 依赖（除 Service 交互）
   (纯逻辑)          │  WpaCore     加密        │
                    │  DictGenerator 生成器    │
                    │  DictRule    规则解析    │
                    │  DictStore   文件读写    │
                    │  DictSources 在线下载    │
                    │  TargetDictBuilder 派生  │
                    │  SmartSorter 排序        │
                    │  TaskQueue   队列持久化  │
                    │  RootProber  连接执行    │
                    │  HistStore   记录        │
                    │  SavedWifiReader 自查    │
                    ├──────────────────────────┤
   数据层            │ core/NetRecord           │  网络模型 + 解析
                    │ scan/Scanner             │  系统 API 交互
                    │ scan/ChannelAnalyzer     │  信道分析
                    ├──────────────────────────┤
   视图层            │ ui/RadarSweep            │  雷达扫掠（Canvas）
   (自绘)            │ ui/SignalBars            │  信号柱状图
                    │ ui/ThinProgress          │  进度条
                    │ ui/NetDialog             │  圆角弹窗
                    │ ui/AuthorCard            │  作者卡片
                    └──────────────────────────┘
```

## 关键决策记录

### D1. 用 `cmd wifi` 而不是 `WifiNetworkSpecifier`

**背景**：Android 10+ 的 `WifiNetworkSpecifier.requestNetwork()` 强制弹出系统确认框，无法静默连接。

**决策**：改用 shell 权限的 `cmd wifi connect-network`，通过 root 调用。

**代价**：需要 root；`cmd wifi` 是系统内部接口，不同 ROM 可能有差异。

**收益**：全自动、无弹窗、结果可读。实测正确密码约 12 秒连上，错误密码约 3 秒返回。

**关键细节**：必须先 `forget-network`，否则系统复用旧凭据会导致假成功。

### D2. 两段式构建，不用 Gradle

**背景**：`aapt2` 与 `d8` 在 Android 上是 arm64 原生二进制，桌面侧跑不了；反之桌面侧有 `javac`。

**决策**：资源编译在设备上跑，dex 生成在桌面上跑，最后桌面组装签名。

**代价**：要跑两条命令。

**收益**：零 SDK 依赖，构建链完全可控，可离线。

### D3. 自绘 UI，不用 XML 布局

**背景**：目标是视觉独立于同类工具。

**决策**：全部界面用代码构建，图表用 `Canvas` 自绘。

**代价**：布局代码比 XML 长。

**收益**：换肤只需改常量；图表无第三方依赖；视觉完全可控。

### D4. 手写加密实现

**背景**：需要 PBKDF2 / HMAC-SHA1 / PRF-512。

**决策**：不用 `javax.crypto` 的 `SecretKeyFactory`，手写 SHA-1 / HMAC / PBKDF2。

**代价**：代码多，需要自证正确性。

**收益**：无 provider 差异；可用 RFC 官方向量直接验证；性能可预测。

**验证**：RFC 3174（SHA-1）、RFC 2202（HMAC-SHA1）、RFC 6070（PBKDF2）、Wi-Fi 联盟 PMK 向量。

### D5. 任务状态持久化到 JSON

**背景**：断点续跑 + 后台运行 + 多目标队列，三者都需要状态能跨进程存活。

**决策**：用 `org.json` 序列化 `TaskQueue` 到应用私有目录，写临时文件后 rename 保证原子性。

**代价**：无法用 Linux 侧单测（`android.jar` 里的 `org.json` 是 stub）。

**收益**：无数据库依赖；纯文本可读可调试。

### D6. 候选分三层

**问题**：字典动辄几十万条，而实测约 1 秒/条——10 万条要 27 小时。

**决策**：

```
第 1 层  目标派生     ~500 条   按 SSID/BSSID 现场造，命中率最高
第 2 层  智能排序     全量      重排已有候选
第 3 层  字典原序     全量      兜底
```

**收益**：几百条精准候选的效果往往胜过十万条盲试。

## 线程模型

```
主线程              UI 更新
                    ↓ 绑定
CrackService        ← 前台服务，持有 worker 线程
    │
    └── worker      runQueue()
            │
            └── RootProber.run()
                    │ 逐条循环
                    ├── execAsync("su -c cmd wifi connect-network ...")
                    ├── interruptibleSleep()  每 100ms 检查暂停/取消标志
                    └── exec("su -c cmd wifi status")  读结果
```

**取消机制**：`cancel()` 会 `destroyForcibly()` 杀掉正在跑的子进程，不用等阻塞调用自然返回。

**暂停机制**：与取消分离。暂停只设标志位并保留 `offset`，任务状态转为 `PAUSED`，可续跑。

## 数据流：一次破解

```
选择目标 + 字典
      ↓
TaskQueue.enqueue()        写队列文件
      ↓
CrackService.startQueue()  读队列，取第一个待办
      ↓
构建候选：
   TargetDictBuilder.build()     生成派生候选
   DictStore.load()              加载用户字典
   合并去重
   SmartSorter.prioritize()      按目标重排
      ↓
RootProber.run(target, dict, offset)
      ↓
   每条：
     forgetTarget()              清旧配置，防假成功
     su -c cmd wifi connect-network <ssid> wpa2 <pw>
     轮询 cmd wifi status
     ├─ "connected to <ssid>"  → 命中
     └─ "not connected"        → 密码错
     写 offset
      ↓
HistStore.append()         记录结果
      通知栏更新进度
```

## 目录

| 路径 | 内容 |
|---|---|
| `/sdcard/Download/WPAForge-dict/` | 字典文件（用户可见可编辑） |
| 应用私有 `files/queue.json` | 任务队列与断点 |
| 应用私有 `files/history.csv` | 破解记录 |
| 应用外部目录 | 导出文件 |
