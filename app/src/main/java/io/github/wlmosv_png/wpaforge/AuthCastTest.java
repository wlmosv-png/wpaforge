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

        // clear()：发命令前清零跨轮残留（2026-11-01 漏判审计新增）
        WifiAuthFailureWatcher w = new WifiAuthFailureWatcher(null);
        // 模拟上一条候选（错误密码）留下一次失败广播（反射写入私有字段）
        try {
            java.lang.reflect.Field fAt = WifiAuthFailureWatcher.class.getDeclaredField("lastErrorAt");
            fAt.setAccessible(true);
            fAt.setLong(w, start + 999_000L);
            java.lang.reflect.Field fSsid = WifiAuthFailureWatcher.class.getDeclaredField("lastErrorSsid");
            fSsid.setAccessible(true);
            fSsid.set(w, "B214");
        } catch (Throwable t) {
            throw new RuntimeException("反射置位失败: " + t, t);
        }
        check("残留广播已记录 → lastErrorAt 非零", w.lastErrorAt() > 0L);
        w.clear();
        check("clear 后 lastErrorAt 归零", w.lastErrorAt() == 0L);
        check("clear 后 SSID 残留清空", w.lastErrorSsid() == null);
        check("clear 后旧失败不再触发早退",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKey(w.lastErrorAt(), start + 1_000_000L, false));

        // ---- 代际校验（A3）：迟到广播必须被丢弃
        long gen = 7L;
        check("代际一致 + 命令后失败 -> 采信",
                WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                        start + 350, start, false, gen, gen));
        check("**代际落后（迟到广播）-> 不采信**",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                        start + 350, start, false, gen - 1, gen));
        check("代际超前（不可能的输入）-> 不采信",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                        start + 350, start, false, gen + 1, gen));
        check("代际一致但已有成功证据 -> 不采信",
                !WifiAuthFailureWatcher.authErrorImpliesWrongKeyGen(
                        start + 350, start, true, gen, gen));

        // ---- 严格归属（A4）：读不到 SSID 时不得放行
        check("严格归属：SSID 逐字一致 -> true",
                WifiAuthFailureWatcher.ssidOwnedByTarget("B214", "B214"));
        check("**严格归属：读不到 SSID（null）-> false（不放行）**",
                !WifiAuthFailureWatcher.ssidOwnedByTarget(null, "B214"));
        check("**严格归属：空 SSID -> false（不放行）**",
                !WifiAuthFailureWatcher.ssidOwnedByTarget("", "B214"));
        check("严格归属：别的网络 -> false",
                !WifiAuthFailureWatcher.ssidOwnedByTarget("211", "B214"));
        check("严格归属：目标是空 -> false",
                !WifiAuthFailureWatcher.ssidOwnedByTarget("B214", ""));

        System.out.println("广播判据: pass=" + pass + " fail=" + fail);
        if (fail > 0) throw new RuntimeException("广播判据有用例失败");
    }
}
