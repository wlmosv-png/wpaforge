# Root 与 Shizuku 通道能力对照（2026-11-20 实测）

> 设备：OnePlus PJX110 / Android 15 / ColorOS 15 / Shizuku 13.6.0
> 方法：Root = `uid 0 + u:r:magisk:s0`；Shizuku 真实身份 = `uid 2000 + u:r:shell:s0`
> 原则：**不为了让表格好看而把未知写成等价**

## 1. 能力矩阵

| 能力 | Root | Shizuku (uid2000+shell域) | 等价 | 证据 |
|---|---|---|---|---|
| `cmd wifi status` | ✅ | ✅ | **是** | 都返回 `Wifi is enabled` + 完整 WifiInfo |
| `cmd wifi list-networks` | ✅ | ✅ | **是** | 都返回 Network Id 表 |
| `cmd wifi connect-network` | ✅ 存在 | ✅ 存在 | **是** | 都返回 `Invalid args for connect-network`（=子命令存在） |
| `cmd wifi forget-network` | ✅ 存在 | ✅ 存在 | **是** | 都返回 `Invalid args`；传 netId 999 返回 `Forget failed`（命令已分发） |
| `cmd wifi list-scan-results` | ✅ | ✅ | **是** | 都返回 BSSID 表 |
| `dumpsys wifi` | ✅ | ✅ | **是** | 都返回配置转储 |
| `WifiConfigStore.xml` 读取 | ✅ 可读 | ❌ **Permission denied** | **否** | root 读到 XML 头；shell 域被 SELinux 拒 |
| `logcat -s wpa_supplicant` | 0 行 | 0 行 | **是\*** | 本机 ColorOS15 不输出该 tag |
| `logcat -s ClientModeImpl` | 0 行 | 0 行 | **是\*** | 同上 |
| WifiInfo（BSSID/netId/IP） | ✅ | ✅ | **是** | 都含 `Net ID: 89` / IP / BSSID |

\* 等价的原因是「两者都不可用」，而非「两者都可用」。

## 2. 对判定链的影响

### 2.1 AT3 归因是否成立？

AT3 要求「SSID + Supplicant state: COMPLETED + IP + Net ID **同行**」，且净检已确认。

- `cmd wifi status` 在 **两个通道都可用** → 四条件同行可取得 ✅
- 净检走 `forget-network` + `list-networks` 回读，**不依赖 WifiConfigStore.xml** → 两个通道都可做 ✅
- 因此 **Shizuku 的 AT3 与 Root 完全等价**，不需要降级

### 2.2 supplicant 日志

本机 ColorOS 15 **两个通道都读不到**，所以它不是 Shizuku 的缺陷。
代码里 supplicant 日志只用于「失败加速」与「诊断」，**不是 SUCCESS 的必要条件**
（SUCCESS 只认 status 四条件同行 + netId 绑定）。

### 2.3 WifiConfigStore.xml

只有 Root 能读。但它**不在判定链上** —— 引擎读它只是为了「系统已保存网络」这一展示项。
Shizuku 下该项应显示 UNAVAILABLE，不影响破解判定。

## 3. 架构结论

```
        Candidate Pipeline / Attempt Manager
                    ↓
          ConnectChannel（业务语义，唯一一份）
                    ↓
              RootChannel
                    ↓
            Executor（唯一差异点）
          ┌─────────┴─────────┐
      su 执行器          Shizuku 执行器
   （默认，原链冻结）    （注入，仅换系统调用）
          ↓                   ↓
   Evidence Collection / Attribution
                    ↓
              Adjudicator
                    ↓
        EvidenceGrade Guard
                    ↓
          Attempt.isSuccess()
```

**差异只在 Executor，判定层完全同一份代码。**

## 4. 未验证的部分（必须声明）

| 项 | 状态 |
|---|---|
| 纯无 Root 设备上 Shizuku（`u:r:shell:s0` 启动）| ❌ **未验证** |
| 本机 Shizuku 实际由 root 启动（`u:r:magisk:s0`）| ⚠️ 无法代表纯 shell 场景 |
| 其他厂商 ROM 的 supplicant 日志 | ❌ 未知 |
| Android 13 设备上的完整链路 | ⚠️ 用户实测过，本轮改动未复验 |

本机 `shizuku_server` 的 SELinux 上下文实测为 `u:r:magisk:s0`，
说明它是 **root 启动**的 —— 因此本机上的 Shizuku 通道实际继承了 root 的
SELinux 权限，**不能证明纯 shell 启动的 Shizuku 也具备同等能力**。

上表中的 Shizuku 列是**用 `uid 2000 + u:r:shell:s0` 主动模拟**得到的，
这才是纯无 Root 设备上 Shizuku 的真实身份。
