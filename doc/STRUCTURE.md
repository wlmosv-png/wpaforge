# NetScope 项目结构

```
netscope/
├── 1-aapt2.sh                     # 构建第一段：Android 侧资源编译
├── 2-build-dex.sh                 # 构建第二段：桌面侧 dex + 组装 + 签名
├── README.md
├── LICENSE
├── doc/
│   └── STRUCTURE.md               # 本文件
└── app/src/main/
    ├── AndroidManifest.xml        # 权限、Activity 声明
    ├── res/
    │   ├── drawable/
    │   │   ├── ic_launcher_foreground.xml   # 自绘图标（同心环 + 斜扫线）
    │   │   └── ic_launcher_background.xml
    │   ├── mipmap-anydpi-v26/ic_launcher.xml # 自适应图标
    │   └── values/strings.xml
    └── java/io/github/wlmosv_png/netscope/
        ├── MainActivity.java      # 主界面（代码构建，三页签）
        ├── SelfTest.java          # 算法自检（36 项）
        ├── E2ETest.java           # 端到端自检（10 项）
        ├── core/
        │   └── NetRecord.java     # 网络记录模型 + 解析
        ├── scan/
        │   ├── Scanner.java       # 扫描器（广播 + 轮询双路）
        │   └── ChannelAnalyzer.java # 信道占用分析
        ├── audit/
        │   ├── Candidate.java     # 候选口令（带来源标记）
        │   ├── DictGenerator.java # 字典生成器（全部自研）
        │   ├── WpaCore.java       # WPA 密钥派生 + MIC 比对
        │   └── AuditEngine.java   # 审计引擎（进度 / 取消）
        └── ui/
            ├── SignalBars.java    # 信号柱状图（Canvas 自绘）
            └── RadarSweep.java    # 雷达扫掠视图（Canvas 自绘）
```

## 分层

```
core/    纯数据模型，无 Android 依赖，可单独编译测试
scan/    系统 API 交互层（WifiManager / 广播）
audit/   算法层（加密 / 字典），除 AuditEngine 外无 Android 依赖
ui/      自绘视图，全部 Canvas，无第三方 UI 库
```

## 设计决策

- **纯代码 UI**：不写 XML 布局。好处是与任何基于 XML 布局的资源结构天然不同，且主题换色只需改常量。
- **自绘取代控件**：雷达与柱状图都是 `View` + `Canvas`，不引入图表库。避免依赖，也避免视觉撞脸。
- **两段式构建**：`aapt2` 与 `d8` 在 Android 上是 arm64 原生二进制，桌面侧跑不了；反过来桌面侧有 java/javac。所以资源在设备上编、dex 在桌面编，最后桌面组装签名。
- **无 Gradle**：省去 SDK 下载与网络依赖，构建链完全显式可控。
