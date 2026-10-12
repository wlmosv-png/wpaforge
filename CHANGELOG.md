# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.8.5] - 2026-10-12

**日志区重做 + 两处弹窗/文案收尾。**

### 1. 日志区：逐行独立着色（这是「显示不好」的真因）

旧实现是：

```java
streamBuffer.append(line).append('\n');
connectStreamView.setText(streamBuffer.toString());
connectStreamView.setTextColor(color);   // ← 整段文本一起变色
```

`connectStreamView` 承载的是**整段**日志。所以每追加一行，整个日志区
的颜色都变成最后一行的颜色 —— 历史行的颜色不停被抹掉。

改用 `SpannableStringBuilder`，每行 append 时打上自己的
`ForegroundColorSpan`，互不干扰。裁掉旧行时一并移除那段的 span，
避免 span 错位。

### 2. 日志区：滚动条与「回到最新」

· **常显竖滚动条** —— ScrollView 默认不显示，用户不知道上面还有内容；
  同时关掉 fading，滚到底也保持可见。
· **加「↓ 回到最新」浮标** —— 用户上滑翻历史时出现在日志区右下角，
  点它回到底部并恢复自动跟随。已在底部时不显示（没用的按钮只挡视线）。
· **高度放宽** —— 折叠态 108dp → 160dp，展开态 240dp → 340dp。
· **保留行数 200 → 400**。Spannable 每行 span 几十字节，内存可忽略。

### 3. 「执行方式」那行：不可点 + 改文案

只有一种通道，点开弹「本版本仅支持 Root 模式」是纯噪音。现在：

· 去掉点击（`setClickable(false)`），删掉 `showModePicker()` 死代码
· 文案 `执行方式: Root · 实测 0.1 条/秒` → `Root 通道 · 实测 0.1 条/秒`，
  不再暗示可切换
· `updateConnectMode()` 里的 Shizuku / 免 Root 分支一并删掉 ——
  `Prefs.mode()` 恒返回 `MODE_ROOT`，那两个分支永远走不到

### 4. 「选择目标网络」弹窗也换成 NetDialog

上一轮只换了字典弹窗，目标弹窗漏了，用户看到的还是白底 Material 样式。
现在统一。

### 测试

`324` 项全过。真机验证：日志着色、滚动条、「回到最新」浮标、
目标弹窗样式、「Root 通道」文案，零崩溃。

---

## [1.8.3] - 2026-10-12

**假阳性加固 + 进度不再丢 + 速度预估不再吓人。**

### 1. 假阳性：判据 A 恢复了 netId 绑定（重点）

判据 A（SSID + `COMPLETED` + 有效 IP）此前**没有** netId 绑定。
代码注释自称「归因由两道前置闸门保证」，但第二道有洞：

```java
if (target.ssid.equals(snapshotSsid())) { 断开 }
```

`snapshotSsid()` 走 shell 的 `cmd wifi status`，超时/抖动时返回 `null`。
`target.ssid.equals(null)` 为 false → **不断开**。此时系统可能仍挂着
目标配置，判据 A 第一轮就看到「目标 + COMPLETED + IP」→ 误判成功。
这就是假阳性的入口。

两处修正：

**① 闸门②改为 SSID + netId 双通道。** SSID 读不到时看 `list-networks`
里目标的 netId；任一存在都要先断开。两条都拿不到才放行。

**② 判据 A 恢复 netId 绑定，但用「宽松式」。** 历史两头都踩过：完全不看
netId → 系统回连被误判；硬要求等于命令后 400ms 取到的值 → 那个值常常是
-1，正确密码永远判不出来。折中：**只在两侧都拿到有效 netId 时才强制相等**，
任一侧为 -1 视为证据不足、退回原三条件。(该值经常是 -1，硬卡会造成假阴性。)

命中证据也写进了 `confirmedBy`：`supplicant+netId` 表示 netId 也吻合（证据最强），
`supplicant` 表示只靠三条件。

新增 `FalsePositiveTest`（17 项）把这几条归因通路钉死：
回连别的网络不算命中、SSID 同 netId 不同不算同一条连接、
任一侧 -1 不构成不符、诱饵行不污染 netId、空输入不产生假阳性。

### 2. 进度不再丢：offset 定期落盘

`offset` 以前只在任务**开始**与**结束**时写盘。跑到第 3000 条被系统回收，
磁盘上记的还是 0 —— 用户看到的就是「进度没记录」。

现在引擎每条都会回调 `onProgress(tested, ...)`，`tested` 即当前游标。
在那里落盘，但**节流**：≥2 秒 或 ≥20 条 写一次。每条都全量重写 JSON
会产生密集 IO；命中/暂停/停止仍由 `finishTask` 立即落盘。

### 3. 速度预估不再偏高：用净尝试耗时算速率

`recordRate` 以前传的是**任务墙钟**（从任务开始到结束），里面混进了
WiFi 重置、基线断开、通道自检这些**一次性**开销，摊进「每条耗时」
让预估值被严重高估。

现在引擎单独累计 `pureTryMs`（只含「每条候选从发命令到出结论」），
通过 `lastPureTryMs()` 暴露给服务层，服务层优先用它记录速率。
界面上的「条/秒」与「预计剩余」也跟着改用净耗时。

### 4. 字典来源弹窗换成自研样式

「字典来源」与「选择字典文件」这两个弹窗原来是系统 `AlertDialog`
（Material 浅色风格），跟字典管理页里的 `NetDialog`（暗色圆角）
完全是两套视觉语言。现在统一走 `NetDialog`。

### 测试

`307 → 324` 项全过（新增 FalsePositiveTest 17 项）。
真机验证：四页签切换、字典三选一 → 内置生成器 → 立即生效、
任务进度实时刷新，零崩溃。

---

## [1.8.2] - 2026-10-12

**修 1.8.0 引入的闪退，并顺手堵两个内存炸弹。**

### 闪退：点「审计」「设置」必崩（1.8.1 修）

1.8.0 收按钮时用的是整行批量替换，把按钮工厂**定义体里**的
`makeButton(label, ACCENT)` 也当调用点换掉了，于是变成：

```java
private Button btnPrimary(String label) {
    Button b = btnPrimary(label);   // ← 自己调自己
}
```

栈立刻爆（StackOverflowError）。审计页与设置页大量用 `btnPrimary`，
所以一点就闪退；观测页与破解页用不到，所以看起来「只有两个页面坏」。

**教训**：批量替换必须排除定义体本身。编译能过、单测全绿
（单测不跑 UI 构建路径）都抓不到它 —— 这类 bug 只有真机点一遍才现形。

### 内存炸弹一：字典「统计」点不动（48 万行表必崩）

`DictActivity.showStats` 在后台线程 `load(e, 100000)` —— 把最多
10 万条读成 `Candidate` 对象。「05 内置号码变形」有 48 万行，
一次就是十几 MB，ColorOS 上直接 OOM。

统计本来就是对「抽样」做的：长度范围、数字/字母/混合占比，
抽 2000 条足够代表整表。上限从 100000 降到 **2000**。

### 内存炸弹二：加载大字典会崩

`loadDictFile` 用 `load(e, 200000)`，而且**没有捕获 OOM**。
`OutOfMemoryError` 是 Error 不是 Exception，`catch (Exception)` 拦不住，
后台线程里抛出来一样杀进程。

两处修正：
· 捕获 `Throwable`，OOM 时弹可读提示（建议改用 TOP 榜或按 SSID 派生），
  而不是闪退
· 上限 200000 → **120000**，并在被截断时明确告知 ——
  按实测约 1 秒/条，20 万条要跑 55 小时，本身就不可用，
  宁可明说也不能静默吃掉后半截

### 测试

`307` 项全过。真机冒烟：观测/审计/破解/设置四页签逐一切换、
字典三选一弹窗、48 万行表的「统计」，全部零崩溃。

---

## [1.8.0] - 2026-10-12

**可用性大修：字典能直接用了、设置页去噪、按钮收成一套。**

### 1. 破解页的字典终于能「直接应用」

以前破解页点字典行只能挑文件；想用内置生成器或按 SSID 派生，
得切到审计页，再切回来还可能没刷新。现在点字典行弹三选一，
全部在原地完成并立即刷新：

```
字典来源
  ├─ 选择字典文件
  ├─ 按目标 SSID 派生    ← 现场按 SSID/BSSID 造候选
  └─ 内置生成器（通用）
```

派生与生成的结果**先落盘再设为当前字典**。服务进程只认 `dictPath`，
不落地就会出现「候选显示正常但一跑就秒结束」——这正是之前
「显示出来了但应用不上」的根因。

### 2. 审计页补上「按 SSID 派生」

审计页原来只有「加载文件」「内置生成器」，按 SSID 派生却只在破解页。
现在审计页也有入口，与破解页**共用同一条实现**，不复制逻辑。

### 3. 设置页去噪

· **删掉「本版本仅支持 Root 模式…」整段解说** —— 只有一个执行方式，
  用户没得选，解释它纯属噪音。保留一行真实的可用性状态。
· **删掉「申请缺失的权限」按钮** —— 启动时已自动申请，
  放个手动按钮只会让人以为不点就不给。改成：权限齐全时什么都不显示，
  确实缺且系统不再弹窗时，才露出「去系统设置里允许」。
· **长按检测加了可见提示** —— 「点一下重新检测 · 长按看完整检测证据」
  直接写在下面，以前完全不可发现。

### 4. 按钮收成一套视觉语言

原来设置页一屏 6~7 个大长条，颜色在紫、绿、灰、暗灰之间乱跳，
用户读不出主次。现在固定四档：

| 档位 | 外观 | 用在哪 |
|------|------|--------|
| 主操作 | 琥珀实心 + 深色字 | 一屏至多一个 |
| 次操作 | 暗底 + 淡描边 | 其余全部 |
| 危险 | 暗底 + 红字 | 只给会丢东西的动作 |
| 幽灵 | 纯文字 | 行内操作 |

绿色从按钮上彻底移除，只留给状态文字（绿 = 安全/成功）。
页签选中态改成「底、字色、字重」三处同时变，扫一眼就能定位当前页。

### 5. 内置字典扩充

新增三份真实弱口令榜（已过滤为 8–63 位，即 WPA 合法长度、去重）：

```
06 WPA 高频真实口令 TOP 447.txt
07 通用口令榜 TOP 3336.txt
08 年度高频口令 TOP 146.txt
```

共 3929 条。放宽了「跨设备通用」的高命中表，排在算法大表之前，
先跑更可能中的。`BuiltinDicts.RELEASE_VERSION` 升到 2，
已装用户下次启动会自动补发缺失的那三份（不覆盖你改过的文件）。

### 测试

`307` 项全过。

---

## [1.7.0] - 2026-10-12

**观测页加了三样东西，判定链一行没动。**

### 1. 同一 SSID 的多 AP 不再被丢掉

旧行为是「同名 SSID 只保留信号最强的一条」。单 AP 环境没问题，
但 Mesh / 宿舍多基站场景会把关键信息直接抹掉：

```
CMCC-密码   ch1    -84dBm     <- 旧逻辑只留 -55 那条
CMCC-密码   ch36   -55dBm
CMCC-密码   ch149  -61dBm
```

用户看不到「这网其实有三个 AP、5G 那条特别强」，而这恰恰是决定
该连哪个的依据。

现在 `Scanner` 全量保留，展示层由 `SsidGroup` 聚合：组头显示
「N 个 AP · 跨 M 个信道 · 信号差 X dB」，组内按信号降序。
单 AP 场景退化成原来的平铺列表，不多一个字。

判定链不受影响：它处理的始终是单个 `NetRecord`，只是候选变多了。

### 2. 带宽 / Wi-Fi 代际上界面

`ScanResult` 里的 `getWifiStandard()`（11n/ac/ax/be）与
`getChannelWidth()`（20/40/80/160/320MHz）以前全部丢弃，
列表上只留一个 `ch11` —— 用户看不出 20MHz 和 160MHz 的差别。

新增 `ScanDetail` 承载这些**纯展示**字段，按 BSSID 旁挂在 `Scanner` 上，
**刻意不塞进 `NetRecord`**：`NetRecord` 是判定链的载体，每多一个字段
就多一处可能出错的地方，展示字段不能污染判定。

API 30+ 才有的方法走反射 + 静默降级，拿不到就显示空，不猜、不崩。

### 3. 信道建议终于有字了

`ChannelAnalyzer` 早就算出 `recommended24` / `recommended5`，但结果
只喂给 `ChannelBars` 画图 —— 图上那条绿色高亮没有任何文字说明，
用户得自己悟「绿的是最空的」。

现在图下方直接给结论：换哪条信道、当前那条有几个 AP 占着。
某频段没有数据就不显示对应行，不用「无数据」占位。

### 回归防线：诱饵行测试（`DecoyLineTest`）

今天实拉 `cmd wifi status` 时抓到一条**每台机器每次都会出现**的诱饵行：

```
WifiInfo: SSID: "B212", ..., Supplicant state: COMPLETED, IP: /192.168.1.2, ..., Net ID: 666
NetworkCapabilities: [ ... TransportInfo: <SSID: <unknown ssid>, ..., IP: /192.168.1.2,
                       Supplicant state: COMPLETED, ..., Net ID: -1 ...> ... SSID: "B212" ...]
```

第二条是 `NetworkCapabilities` 内嵌的 `TransportInfo`，SSID 显示为
`<unknown ssid>`、Net ID 是 -1，**但它同样带 COMPLETED 和有效 IP**。
任何「扫全文找关键词」的判定都会被它骗到。历史上真出过事故
（系统弹「无法连接到 BTWIFL_0923」，App 却报「已连上」）。

新增 10 项测试钉死这个行为：诱饵行在前 / 在后 / 只有诱饵行 /
把 `<unknown ssid>` 当目标，四种情况 + null/空串安全。

### 测试

`273 -> 307` 项全过（新增 `DecoyLineTest` 10 项、`SsidGroupTest` 24 项）。

---

## [1.6.0] - 2026-10-11

## [1.5.0] - 2026-10-11

**只保留 Root 模式，去掉 Shizuku 与免 Root。**

Shizuku 通道的 `cmd wifi` 字符串命令不稳定（会被 shell 转义/ROM 差异吞掉，
导致 netId 建不出来）、免 Root 在 Android 13+ 基本不可用。与其让普通用户
在三者之间踩坑，不如只留一条验证过能用的路：**Root + cmd wifi**。

### 改动

- `Prefs.mode()` 恒返回 Root 模式
- 设置页移除三个模式按钮，改成一行「本版本仅支持 Root 模式」说明
- 连接动作统一走 `cmd wifi connect-network`（Root 下本机实测可用）

判定不变：status「SSID + COMPLETED + IP」为主，日志「associate 之后
Key negotiation completed」为辅。

测试 275 项全过。

---

## [1.4.1] - 2026-10-11

**Shizuku 模式改用 binder 直调，绕开 `cmd wifi` 字符串命令。**

你另一台手机的连接自测里，【5/6】显示「新建配置 netId: -1」——
命令发出去了，但系统根本没建出目标配置。实测「连一个不存在的 SSID」
配置都能建出来（netId 不会是 -1），所以 netId=-1 只可能是
`cmd wifi connect-network` 这条字符串命令**被 shell 转义/ROM 差异吞掉了**。

`cmd wifi` 在不同 ROM 上行为不一、对 SSID/密码里的特殊字符敏感。
这是最大不稳定源。

### 改动

Shizuku 模式下，连接动作从「shell 字符串命令」改成 **binder 直调**：

    IWifiManager.addOrUpdateNetwork(WifiConfiguration) → 返回 netId
    IWifiManager.enableNetwork(netId, true)             → 发起连接

这是参考项目 bszapp 的核心做法：把 WifiConfiguration 对象直接交给系统，
不经 shell 拼接，SSID/密码里有什么字符都不影响。netId 直接拿到。

Root 模式仍走 `cmd wifi`（本机实测可用）。

### 判定不变

仍是双保险：status 的「SSID + COMPLETED + IP」为主，
日志「associate 之后 Key negotiation completed」为辅。

测试 275 项全过。

---

## [1.4.0] - 2026-10-11

**这版的目标：让一个普通用户装上去就能用，不再需要懂 root / Shizuku。**

### 1. 执行模式自动选择（修「没 root 的手机默认 Root 模式」）

以前默认值是 Root 模式，没 root 的手机一装上就是错的模式，点开始必失败，
还以为是密码不对。现在**首次启动自动探测**，按可用性排序：

   Root 可用 → Root；否则 Shizuku 就绪 → Shizuku；否则免 Root

用户手动选过才尊重手动选择，不再有「默认值害人」。

### 2. 判定：去掉时序敏感的 netId 阻断

上一版要求「连接的 netId == 命令后 400ms 取到的 netId」，那个值取不到时
是 -1，会让**所有**候选永远判不成功（实机：密码正确但 12 秒超时）。
现在归因改由两道前置闸门保证（命令前配置已删净 + 命令前未连着目标），
netId 只作加分记录，不再阻断。

### 3. 恢复参考项目（bszapp）验证过的日志判据

- 成功加速：日志出现「Trying to associate with SSID 'X'」且随后
  「WPA: Key negotiation completed」→ 命中
- 失败加速：日志出现「4-Way Handshake failed」或「CTRL-EVENT-ASSOC-REJECT」
  → 直接判密码错，不用傻等超时

前提：命令发出前 `logcat -c`，读到的都是本次的行。

### 4. 连接自测改成「分 6 步报告」

设置页的「连接自测」现在逐步输出：权限 → 通道 → 清配置 → 断开 →
发命令 → 观察 15 秒（每秒 status 摘要），最后给结论。
用户报障时一眼看到卡在哪一步，不再只有一句笼统的成败。

### 判据汇总（双保险）

| 层 | 条件 |
|---|---|
| 主：status | SSID==目标 && Supplicant state: COMPLETED && IP 有效 |
| 辅：日志 | 目标 associate 之后 Key negotiation completed |

失败信号：4-Way Handshake failed / CTRL-EVENT-ASSOC-REJECT。

测试 275 项全过（16 个测试类）。

---

## [1.3.0] - 2026-10-11

**判定链推倒重来：只剩一条硬判据 —— 4-Way Handshake 完成。**

前面几个版本一直在「打补丁」：日志判据、网络回调判据、WifiInfo 判据、
netId 校验……每条都能被「系统回连别的已保存网络」满足，于是补丁越加越多，
假阳性还是漏。实机反复出现：自己的 2.4G 网、邻居家的网，都在第一条
`12345678` 上被判成功。

这次不再补，而是**砍到只剩一条**。

### 唯一判据

判定「密码对不对」的本质是 **4-Way Handshake 有没有完成**。
密码错误时握手必然失败，wpa_supplicant **永远到不了**
`Supplicant state: COMPLETED`。这是系统对「密钥协商成功」的宣告。

四个条件必须**同源于同一行 `WifiInfo`**，缺一不可：

| 条件 | 含义 |
|---|---|
| `SSID == 目标` | 是我们要连的那个网 |
| `Supplicant state: COMPLETED` | 4-Way Handshake 完成 = 密码正确 |
| `IP: /x.x.x.x`（非 0.0.0.0） | DHCP 成功 = 真的通了 |
| `Net ID == 本条新建的 netId` | 归因到本次候选，不是旧连接/回连 |

四者同行同源，**无法被「连上了别的网络」伪造**。

### 删掉了什么

- 判据 A（状态机日志）、C（NetworkWatcher）、D（WifiInfo）
  —— 全砍。它们在 Root 下能「加速」，但同样会被回连满足，是假阳性来源。
- 原则改为：**宁可漏判（把对的判成没连上），也绝不误判。**

### 新增

- `SupplicantParser`（纯逻辑，可离线测试）：解析 WifiInfo 行的
  SSID / state / IP / netId，并提供 `isTargetConnected()`
- `SupplicantParserTest` 25 项：含「回连别的网络不算命中」「握手未完成不算」
  「0.0.0.0 不算有效 IP」「含空格 SSID」等关键场景

### 实机验证（B214）

| 场景 | 结果 |
|---|---|
| 正确密码 12345678 | 800ms 命中，netId 642 == 642 → **判成功** |
| 错误密码 00000000 | 12 秒内从未出现「B214 + COMPLETED」，回连 B217 → **不判成功** |

测试：16 个测试类共 **275 项**全过。

---

## [1.2.5] - 2026-10-11

**修「假阳性」的最后一环：SSID 含空格时净检被静默绕过。**

用户和朋友实测：自己的 2.4G 网络、邻居家的网络，用不含正确密码的字典
跑，第一条 `12345678` 都被判成功 —— 实际上连不上。

### 真凶

`cmd wifi list-networks` 的输出是 `<netId>   <SSID>   <security>`。
解析一直用 `split("\\s+")` 取 `parts[1]` 当 SSID ——
**SSID 里只要有空格**（"My Net 5G"），就只能取到 "My"，匹配失败。

后果是连锁的：
- `netIdOfTarget()` 返回 -1 → 「旧配置没清干净」的净检**被静默绕过**
- `forgetTarget()` 同样匹配不到 → **配置根本没删掉**
- 系统随后拿**已保存的旧密码**连上（甚至只是保持原有连接）
- 判定看到「连着目标」→ 判成功 ✗

### 改动

- 新增 `ListNetworksParser`（纯逻辑，可离线测试）：
  取开头的数字当 netId，余下部分**按前缀匹配 SSID**，并要求 SSID 后
  紧跟空白（避免 "123" 误配 "1234..."）
- `CrackEngine` 与 `CrackService` 里两处重复的解析全部改走它
- **`forgetTarget` 收归引擎**（`forgetTargetByShell`）：
  以前由外部 `ShellChannel` 实现，服务里做了回读校验、
  自检和连接自测里却是**空实现**，行为不一致。现在谁调都一样，
  且删除失败时本条直接判 UNKNOWN
- `currentSsid()` 改为**优先走 `cmd wifi status`**：
  缺定位权限时 App 侧恒返回 `<unknown ssid>`，会让基线断开确认、
  「命令前不是目标」等防护静默失效
- 任务开始时**重置一次 WiFi**，清掉 WPA2 PMKSA 密钥缓存
  （缓存会让错误密码也可能连上）

### 测试

新增 `NetIdLineTest` 16 项（含空格 SSID、前缀误配、表头、垃圾行）。
全部 15 个测试类共 **250 项**通过。

---

## [1.2.4] - 2026-10-11

**修「不主动申请定位权限」。**

用户反馈：软件不会主动索取定位权限，有些手机上因此拿不到 SSID、
扫描为空、免 Root 连接直接 SecurityException。

原因：权限申请只在 `onCreate` 里跑一次。有些 ROM 在这个时机
Activity 还没真正可见，系统会把请求丢掉；用户一旦拒绝过，
后面再也不会弹窗，界面上也看不到任何提示。

### 改动

- **`onResume` 里复查并重新申请**：每次回到前台都检查一次，
  用户刚从系统设置里打开权限也能立刻生效
- **设置页新增「权限」区块**：
  - 逐项列出 `✓/✗ 定位`、`✓/✗ 附近设备（Android 13+）`
  - 提示「系统定位总开关也要打开」
  - 「申请缺失的权限」按钮 —— 强制申请一遍，不依赖之前问没问过
  - 「打开系统『应用信息』页」按钮 —— 用户勾过「不再询问」时，
    直接跳到系统设置手动开
- 权限请求回调里刷新状态行

### 为什么定位权限这么关键

- Android 12 及以下：**没有定位权限，扫描结果里拿不到 SSID/BSSID**
- Android 13+：`NEARBY_WIFI_DEVICES` 之外仍需要定位权限
  （本项目为了拿完整 SSID **刻意不加** `neverForLocation` 标志）
- 免 Root 模式的 `requestNetwork`：缺定位权限直接 `SecurityException`
- 判定层读 `WifiInfo.getSSID()`：缺权限永远返回 `<unknown ssid>`

测试 234 项全过。

---

## [1.2.3] - 2026-10-11

**堵住最后两条弱判据：watcher 与 wifiinfo 现在也必须过 netId 校验。**

用户新截图里关键一行：

   候选 password 已连上（依据: wifiinfo）

而系统弹窗明明白白写着「无法连接到 WLAN 网络 BTWIFL_0923」——
系统说连不上，App 却判了成功。问题出在判据 D（WifiInfo 本地 API）：

    WifiManager.getConnectionInfo().getSSID() 在「正在连接」时会瞬时
    指向目标网络名，这并不代表连上了。

它当时只看「当前 SSID == 目标 且 与命令前不同」，没有 netId 约束，
于是把一次失败的连接读成了成功。

### 改动

- **判据 D（wifiinfo）**：增加 `targetNetId > 0 && roundNetId == targetNetId`
- **判据 C（watcher）**：同上
- **`currentNetId()` 改为优先读 `cmd wifi status` 的 Net ID** —
  App 侧 `WifiInfo.getNetworkId()` 在连接中会返回 -1 或过期值，
  而判定最需要在那一刻拿到真实 netId
- 同一轮 `cmd wifi status` 的结果缓存复用（判据 B 读一次，C/D 直接用），
  避免每轮多起两次 shell

### 现在的四条判据，全部带 netId 硬校验

| 判据 | 条件 |
|---|---|
| A 状态机日志 | 日志出现目标 L2ConnectedState **且** status 的 Net ID == 本次 netId |
| B status | connected to 目标 **且** Net ID == 本次 netId |
| C watcher | 事件出现 **且** 本轮 Net ID == 本次 netId |
| D WifiInfo | 当前 SSID 是目标 **且** 本轮 Net ID == 本次 netId |

### 实机验证（B209 错误密码 00000000）

```
t=1..5s  Wifi is not connected        （status 里连 connected to 都没有）
t=6s     connected to "B214" / Net ID 631   （回连别的网络，≠ 目标 netId 635）
```

→ 四条判据全部无法成立 → 正确判为「未连上」。

测试 234 项全过。

---

## [1.2.2] - 2026-10-11

**修掉「4 个不同网络都在第 1 条 password 就判成功」的假阳性。**

用户实测截图：MIFI-4427 / BTWIFL_0923 / ChinaNet-3ad7 / 123，
四个毫不相关的网络，全部在第 1 条候选 `password` 上显示「已破解」。

根因是判定链上有三个口子，全在「当前状态」上做了过强的推断：

1. **「现在连着目标」直接判成功**
   系统回连、旧连接残留、缓存凭据……都会让这个条件为真，
   但它们跟当前候选毫无关系。**该分支已改为 UNKNOWN。**

2. **日志判据没有归因校验**
   只要日志里出现目标的 `L2ConnectedState` 就算命中。
   旧配置/缓存连上时同样会打这条日志。**现在必须再通过
   `status` + netId 交叉验证**（netId 必须是本次新建的）。

3. **netId 校验形同虚设**
   旧写法 `targetNetId < 0 || nowNetId < 0 || nowNetId == targetNetId`，
   任一边拿不到就放行。**现在要求 `targetNetId > 0 && nowNetId == targetNetId`。**

另外新增一道闸：**每条候选前回读验证目标配置已清除**
（`list-networks` 里目标不得还挂着 netId）。配置没清干净时，
系统会拿已保存的正确密码连上 —— 此时本条直接判 UNKNOWN，
不再污染结果。

### 现在的判据链（三条同时成立才算命中）

```
① 命令前：list-networks 里目标无 netId（配置确实清干净了）
② 命令后：list-networks 里目标的 netId = N（本次新建）
③ status：connected to "目标" 且 Net ID == N
```

这三条串起来，唯一可能的解释就是「本条候选创建的配置连上了目标」，
回连别的网络、旧配置复用都会被挡在外面。

### 实机验证（自家网络 B209，密码 12345678）

| 场景 | 命令前配置 | 命令后 netId | 最终 status | 判定 |
|---|---|---|---|---|
| 正确密码 | 0 | 633（新） | connected to "B209" / Net ID 633 | **成功** |
| 错误密码 00000000 | 0 | 634（新） | connected to "B214" / Net ID 631 | **不命中** |

测试 234 项全过。

---

## [1.2.0] - 2026-10-11

**新增「连接自测」与「导出日志」——一键定位卡在哪一层。**

用户反馈「诊断看着都正常，但正确密码仍判不成功」。光看结论分不清是
「命令没发出去」「连上了没识别到」还是「根本没连上」。这次给两个工具。

### 设置页新增两个按钮

- **连接自测**：填 SSID + 密码，真的发一次连接命令，然后输出：
  完整环境诊断、`verdict`（SUCCESS / AUTH_FAIL / UNKNOWN / ERROR）、
  **依据**（wifistate / status / watcher / wifiinfo）、耗时与详情
  （详情里带 `[层: 日志N/可读 · statusN/通 · 事件N]`）。
- **导出日志**：把最近一次运行日志复制到 Download 目录，直接可分享。

### 日志位置

`/sdcard/Android/data/io.github.wlmosv_png.wpaforge/files/logs/WPAForge-log-*.txt`

### 澄清

**Shamiko 是隐藏 root 的模块，与 WiFi 连接判定无关。** 没 root 的设备
只能用 Shizuku 或免 Root；Shizuku 服务未启动/未授权时通道就是不通的，
环境诊断会在「③ 执行通道」直接指出。

---

## [1.1.9] - 2026-10-11

**加了完整环境诊断，一眼看出卡在哪一层。**

### 新增 Emoji 环境诊断（`EnvDiagnostics`）

任务开始时自动跑一遍并写进日志，覆盖：
- **设备环境**：厂商/型号、Android 版本、API、安全补丁、ABI
- **应用权限**：定位 / 附近设备 / WiFi 状态 / 网络状态 逐项 ✔✗
- **执行通道**：
  - Shizuku 模式 → 是否安装、服务是否运行、**是否已授权本应用**、服务版本
  - Root 模式 → su 是否可用、返回的 uid
  - 免 Root 模式 → API 是否满足
- **shell 通道实测**：`cmd wifi status` 是否有输出、耗时；`logcat` 是否可读
  （决定能不能用「状态机日志」判据层）；`list-networks` 是否可用
- **判定信号**：WifiInfo 当前 SSID 是否读得到（缺定位权限会读不到）

自检入口不变：**设置页那行「执行方式: …」长按**，弹出的内容里现在包含完整诊断。

### 判定分层日志

每条候选结束后，未命中也会在候选流里带上该条的**分层证据**：
```
[层: 日志12/可读 · status8/通 · 事件0]
```
一眼看出是哪层没工作（比如 Shizuku 下「日志不可读」是正常的，
但若「status 断」就说明通道真的没通）。

### 重要说明

**Shamiko 是隐藏 root 的模块，与 WiFi 连接判定完全无关。**
没 root 的手机不能用 Root 模式，只能用 Shizuku 或免 Root；若 Shizuku
服务没启动或没授权，通道就是不通的 —— 新诊断会直接指着这一行告诉你。

---

## [1.1.8] - 2026-10-11

- 任务开始时打印完整环境诊断（见 1.1.9，功能同批加入）。

## [1.1.7] - 2026-10-11

**修掉「密码对却判不算命中」的真凶：复验一票否决 + 上一条连接残留造成的假阳性。**

### 移除「断开重连」式复验（关键）

之前命中后会再做一次复验：断开 → 用同一密码重连 → 只有这次也成功才算命中。
实测发现它弊大于利：

- 主判定用的是**四层硬证据**（状态机日志 / status+netId 绑定 / 网络事件 /
  WifiInfo），其中 netId 由系统分配、对不上就不算命中，归因已经足够严密；
- 复验要「断开再连一次」，**会打断用户当前的网络**；
- 部分路由器/AP 对短时间内的快速重连有退避，**正确密码也会「复验失败」** ——
  用户看到的就是「明明连上了却说不算命中」。实机日志：
  `候选 12345678 看起来连上了，正在复验… → ✗ 复验失败（14386ms）`。

现在：**命中即命中**，立刻结束并报成功；复验相关代码保留但不再调用。

### 修上一条连接残留导致的假阳性

`forget-target` 之后系统可能仍保持连接。此时对**错误密码**发命令，
判定看到「连着目标」就会误判成功。实机复现：B214 用正确密码 12345678
连上后，紧接着的 `88888888` 被判「看起来连上了」。

修法：每条尝试前，若当前正连着目标，**先真正断开并确认断开**
（`disconnectAndVerify`）再发命令；断不开则本条判 UNKNOWN 而不是成功。
正常情况（连着别的网络）零额外开销。

### 判定归类修正

- 通道活着（`cmd wifi status` 可读）时没连上 → `AUTH_FAIL`（密码错），
  不再一律 UNKNOWN；
- 连「尝试」都没有且通道无响应 → `UNKNOWN`（通道问题）。

### 诊断增强

- **每次任务自动写日志文件**：
  `/sdcard/Android/data/io.github.wlmosv_png.wpaforge/files/logs/WPAForge-log-*.txt`
- 命中时在候选流里显示**判定依据**（wifistate / status / watcher / wifiinfo）。
- 不再把 UNKNOWN 的 detail 覆盖成固定文案 —— 引擎算出的真实原因原样透出。

### 测试

234 项全部通过（新增 StatusParseTest 18 项、WifiStateLogTest 19 项）。
实机验证：正确密码 12345678 连上 B214；错误密码 88888888 不连上。

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
