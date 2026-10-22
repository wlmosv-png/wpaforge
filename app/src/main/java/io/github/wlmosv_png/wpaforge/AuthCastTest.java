package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.WifiAuthFailureWatcher;

/**
 * 「系统广播认证失败」判据的离线自测。
 *
 * 依据（2026-10-23 真机定论）：系统在 WPA 握手失败时发
 * SUPPLICANT_STATE_CHANGED_ACTION + ERROR_AUTHENTICATING（系统弹「密码错误」对话框靠它），
 * 这条信号**不经过 logcat**，因此不受厂商日志配额影响。
 *
 * 只测纯函数，不碰 Android API。
 */
public class AuthCastTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 早退判据：系统广播认证失败 ==");

        long start = 100_000L;

        // 1) 事件在命令之后 → 判密码错
        check("命令后收到认证失败 → 早退",
                WifiAuthFailureWatcher.authErrorImpliesWrongKey(start + 350, start, false));
        // 2) 事件在命令之前（上一条候选的残留）→ 不算
        check("命令前的旧广播 → 不早退",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(start - 500, start, false));
        // 3) 已见成功证据 → 不早退（成功优先）
        check("已有成功证据 → 不早退",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(start + 350, start, true));
        // 4) 没有起点时刻 → 宁可不判
        check("未记录起点 → 不早退",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(start + 350, 0, false));
        // 5) 从未收到广播 → 不早退
        check("从未收到广播(0) → 不早退",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(0, start, false));

        // SSID 归属
        check("读不到 SSID 时不过滤（放行）",
                WifiAuthFailureWatcher.ssidMatches(null, "B214"));
        check("空 SSID 时不过滤（放行）",
                WifiAuthFailureWatcher.ssidMatches("", "B214"));
        check("SSID 一致 → 放行",
                WifiAuthFailureWatcher.ssidMatches("B214", "B214"));
        check("SSID 不一致（别的网络失败）→ 不放行",
                !WifiAuthFailureWatcher.ssidMatches("211", "B214"));

        System.out.println("广播判据: pass=" + pass + " fail=" + fail);
        if (fail > 0) throw new RuntimeException("广播判据有用例失败");
    }
}
