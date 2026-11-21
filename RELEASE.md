<!-- GitHub Release 正文。标题填：WPAForge 1.9.64 · 判定证据链整改（接入 dumpsys 认证失败证据） -->
<!-- tag: v1.9.64   target: main -->

## 这一版不放松判定标准，只把被丢弃的证据接回来

1.9.63 的 `WifiDumpsProbe` 类注释断言：

> `dumpsys wifi` **不能**证明「密码错误」：`reasonCode` 本机实测值恒为 3
> （WIFI_DISABLED_BY_SYSTEM），**不是** supplicant 的 `reason=WRONG_KEY`。

前半句是对的。由它推出「整份 dump 没用」是错的 —— 真机复核在**同一份输出**
里找到了三条独立证据链，而 1.9.63 一条都没用。

### 真机证据（PJX110 / ColorOS 15，4 MB dump 全量）

| 证据 | 实测条数 | 归属信息 |
|---|---|---|
| `WifiMetrics.mConnectionEvents` | 63 条，**16 条 `AUTH_FAILURE_WRONG_PSWD`** | SSID + BSSID + 毫秒时间 + 发起者 uid |
| `WifiConfigManager - Log` | 73 ADD / 147 SELECTION，**30 条 `DISABLED_BY_WRONG_PASSWORD`** | configKey + 配置创建者 uid + 毫秒时间 |
| 内嵌 system log | 2 条 `reason=WRONG_KEY` | 时间戳 + ssid |

对照：`logcat -s wpa_supplicant` 在本机**恒为 0 行**（ColorOS 配额丢弃）。
1.9.63 唯一依赖的失败通道在本机根本不存在。

### 对照实验（同一台机、同一个 uid=0 通道）

| 目标 | framework 记录 | 1.9.63 判定 |
|---|---|---|
| `2.4G-3`（密码确实错） | 连续 14 条 `AUTH_FAILURE_WRONG_PSWD`，duration 4182–4295ms | `UNKNOWN / netid-conflict`，永久重试 |
| `whoami`（无失败证据） | 10 条 `NEW_CONNECTION_ATTEMPT` | `UNKNOWN`（**这一条判对了**） |

第二行同样重要：它证明这个字段有真实区分能力，不是恒值；
也证明「UNKNOWN 多」不等于「证据被漏读」—— 不能硬造结论。

### 新增判据 D

```
① 命令前目标配置已删净并回读（前置闸门保证）
② 窗口内出现 configKey="<目标SSID>" 的 uid=0 的 ADD_OR_UPDATE
③ 同一窗口内该 configKey 被 framework 标为 DISABLED_BY_WRONG_PASSWORD
④ 或 mConnectionEvents 里同 SSID、uid=0、connectionResult=0、
   level2FailureReason=AUTH_FAILURE_WRONG_PSWD
```

`①②③` 同时成立 = AT3 → `AUTH_FAIL + DIRECT`（可永久排除）。
`④` 同样要求 `②`，防止旧同名配置的失败被算到本条头上。

查询时刻：命令后 2600ms / 3600ms 各一次（真机失败记录 4182–4295ms 才写入；
`dumpsys wifi` 整份 356ms，解析本身只要 7ms/15ms）。

### 三处修正

- **解析器漏 11/63 条**：真机 `startTime` 不补前导零（`1:0:6.775`、`18:18:4.284`），
  原正则要求两位会静默丢弃。已改 `\d{1,2}`。
- **`netid-conflict` 的 UI 在误导**：1.9.63 提示「目标曾连上并取得 IP，请人工复核」，
  而真机 `2.4G-3` 连续 14 次 `connectionResult=0`，**一次都没连上过**。
  现在改为「连接归属存疑（netId 与本次配置不符）」。
- **收尾重复跑 `dumpsys wifi`**（+356ms/条）已删除，改为复用判据 D 的采集结果。

### 测试

新增 5 个测试类、**297** 个用例；全量门禁 **8786 通过 / 0 失败 / 233 类**。

三条核心不变式穷举核对（不是抽查）：

1. `WRONG_KEY` 在任何来源 × 归属 × 生命周期组合下都不产 SUCCESS
2. `AUTH_FAIL + DIRECT` 只可能来自 `WRONG_KEY + AT3 + TERMINATED`
3. `persistable` / `advancesCursor` 当且仅当 `DIRECT`

### 仍然做不到的

- `mConnectionEvents` 只留最近 63 条、`WifiConfigManager - Log` 只留 122 行，
  所以必须每次尝试结束就查，不能攒到最后。
- 若某 ROM 不输出 `uid` 字段，判据 D 会退化为「查不到」→ 保持 UNKNOWN，不误判。
