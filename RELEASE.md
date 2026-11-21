<!-- GitHub Release 正文。标题填：WPAForge 1.9.66 · 破解页重设计 + 采集窗口修正 -->
<!-- tag: v1.9.66   target: main -->

## 这一版做了两件事：页面重排，和把「查得太早」彻底修掉

### 一、破解页：从「一堆数字」变成能读懂的面板

**统计区**改成四项互斥的核心指标，并把「已执行 / 总数」下沉为进度层的一行：

| 指标 | 含义 |
|---|---|
| 已确认成功 | SUCCESS + DIRECT |
| 已证实错误 | AUTH_FAIL + DIRECT |
| 连接未完成 | TIMEOUT / INCOMPLETE / TERMINATED_UNKNOWN / ABORTED |
| 尚无有效判定 | 疑似 + 网络级 + 无法判断 + 环境异常 |

「尚无有效判定」的细分独立成一行（疑似密码错误 / 网络认证失败 / 无法判断 / 环境异常），
零值隐藏、数字与标签分离。四项之和恒等于总数，细分之和恒等于「尚无有效判定」。

**攻击流**改成三层，不再同一句话说两遍：

```
已证实密码错误 · 第 5–21 条 · 4.2s   ×17  03:35–03:36
  系统观察到归属于本次尝试的错误密钥 —— 已记录，不再重试。
  （点「详情」后）attempt=run#3#21 · verdict=AUTH_FAIL · grade=DIRECT
                · reason=wrong-key · src=dumpsys · triedSet=写入 · cursor=推进
```

同时修掉一个真实缺陷：服务端曾先调 5 参回调、再调 7 参回调，7 参又回调 5 参，
**每条结果被追加两次**。现在只有一个落地入口。

**文案与颜色**：`UNKNOWN` 不再叫「密码错误」，`INCOMPLETE` 不再叫「密码错误」，
`INFERRED` 不冒充 `DIRECT`。成功绿 / 已证实红 / 疑似橙 / 未完成中性灰 /
无有效判定中性灰 / 环境紫灰；品牌琥珀只承担强调。

### 二、判定链：窗口按「落盘时刻」算，兜底补采覆盖 UNKNOWN

`mConnectionEvents` 的 `startTime` 是连接**开始**时刻，记录要等
`startTime + durationMillis` 才写进 dump（真机实测 3.8~4.9 秒）。
1.9.65 只用 startTime 卡窗口终点，于是「本次开始、但几秒后才落盘」的
`AUTH_FAILURE_WRONG_PSWD` 被判成窗口外 —— 这仍是漏判。

现在：

- **窗口**：开始时刻落在窗口内，**或**连接生命期与窗口有交叠，都认；
  开始时刻晚于窗口终点的下一次尝试仍不认（防串线）。
- **兜底补采**：`TERMINATED` 与 `UNKNOWN` 都触发（读不到状态时事件可能还没落盘）；
  `ACTIVE` 不触发（连接还在跑，INCOMPLETE 才对）。
- **防重复消费**：同一条连接事件不会被两个 attempt 先后认领。

**安全边界一字未动**：只有 `TERMINATED` 才可能落到 `AUTH_FAIL + DIRECT`；
UNKNOWN 下补采到的证据最多是网络级；只有 DIRECT 才写 TriedSet、推进 Cursor。

### 三、验证

- 全量门禁全部通过（测试类与用例数见仓库 CHANGELOG）。
- 新增：`DumpsWindowSemanticsTest`（窗口含延迟落盘）、`DumpsGraceCoverageTest`
  （兜底覆盖与持久化边界）、`ResultCopyTest`、`StreamFormatterTest`、
  `CrackStatsModelTest`。
- **未做真机错误密码/正确密码实跑**：需要真实连接 Wi-Fi，留给使用者按报告步骤执行。
