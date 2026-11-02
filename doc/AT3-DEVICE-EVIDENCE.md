# AT3 真机证据（2026-11-02）

设备：OnePlus PJX110 / Android 15 / ColorOS 15，Magisk root。
测试目标：本机热点 `whoami`（BSSID `c6:d0:0e:dc:24:94`，WPA2-PSK）。
未触碰任何其它网络，未重启设备 / SystemUI / 系统服务。

## 一、实测数据

连续三轮「删净 → connect 错误密码 → 观察 9 秒」：

| 轮次 | Android netId（`cmd wifi list-networks`） | 日志 `TEMP-DISABLED id=` |
|---|---|---|
| 1 | 1573 | 0 |
| 2 | 1574 | 0 |
| 3 | 1575 | 0 |

原文（示例）：

```
11-02 16:47:19.730  9457  9457 I wpa_supplicant: wlan0: CTRL-EVENT-SSID-TEMP-DISABLED
    id=0 ssid="whoami" auth_failures=1 duration=5 reason=WRONG_KEY
```

`cmd wifi list-networks` 同刻：

```
1572         whoami                           wpa2-psk
1572         whoami                           wpa3-sae^
```

## 二、结论（硬事实）

1. **两个 ID 空间不同。** `TEMP-DISABLED id=N` 的 N 是 wpa_supplicant
   内部网络块索引，**恒为 0**；Android Net ID 由 framework 分配，每次都递增。
2. **AT3 在本机不可达。** 判据 `id == targetNetId` 永不成立。
3. **降级方向安全。** 失败证据落到 `AT1` → 裁决为
   `NETWORK_AUTH_FAIL / NONE`：
   - 不写永久 triedSet；
   - 不推进 cursor；
   - 不计入「已证实失败」；
   - 不喂退避样本。

   代价：错误候选不会被永久排除，续跑会重复尝试它们
   （用正确的方向换掉了「正确密码可能被永久锁死」）。
4. **成功判定同样不能依赖日志里的 `[id=N]`。** 真机
   `CTRL-EVENT-CONNECTED ... [id=0]` 也是 supplicant 内部号，
   与本机 netId（131 / 1573…）不同体系。成功归属仍必须靠
   **status 四条件同行 + 前置闸门**。

## 三、代码侧的对应事实（已核对）

`CrackEngine.tryOneShell` 的三支：

```java
boolean logBoundFail  = logWinUsable
        && WpaLogReader.sawWrongKeyForConfig(slice, ssid, bssid, targetNetId); // AT3
boolean logAttemptFail = logWinUsable && !logBoundFail
        && WpaLogReader.sawWrongKeyForAttempt(slice, ssid, bssid);             // AT1
```

真机原文喂进函数实测：

```
sawWrongKeyForConfig(netId=1573) = false     <- AT3 不成立，符合实测
sawWrongKeyForAttempt            = true      <- AT1 成立
sawWrongKeyFor(仅 SSID)          = true      <- 弱判据，已不用于定案
```

裁决结果：`NETWORK_AUTH_FAIL / NONE(network-auth-fail)`。

## 四、固化

`AT3DeviceEvidenceTest`（9 项）用**真机原文**逐条断言上述结论，
包括「传 netId=0 时才命中」的反证，防止将来把判据改回硬要求 netId 相等，
或反过来误以为 AT3 可达而放宽证据门槛。

## 五、未验证项

- AT3 是否在**其它厂商/内核**可达（例如 supplicant id 与 netId 同源的设备）：
  **未验证**，本机结论不能外推。
- 本机热点只有 2.4G，未测 5G / WPA3-SAE 场景下的日志形态差异。
