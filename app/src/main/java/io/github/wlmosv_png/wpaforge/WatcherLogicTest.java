package io.github.wlmosv_png.wpaforge;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件判定的离线自测。
 *
 * {@link io.github.wlmosv_png.wpaforge.audit.NetworkWatcher} 本身依赖系统服务，
 * 这里把它**判定逻辑**（事件记录 + 按时间查询）抽出来独立验证 ——
 * 因为判定错一次就会误报一条密码，必须有回归保护。
 *
 * 核心规则：**只认「命令发出之后」出现的「目标可用」事件**。
 */
public class WatcherLogicTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 与 NetworkWatcher.Event 同构的最小记录 */
    private static final class Ev {
        final String ssid; final boolean up; final long at;
        Ev(String s, boolean u, long t) { ssid = s; up = u; at = t; }
    }

    /** 与 NetworkWatcher.firstAvailableAfter 同构 */
    private static long firstAvailableAfter(List<Ev> evs, String ssid, long sinceMs) {
        if (ssid == null) return -1;
        for (Ev e : evs) {
            if (e.at < sinceMs) continue;
            if (e.up && ssid.equals(e.ssid)) return e.at;
        }
        return -1;
    }

    public static void main(String[] args) {
        System.out.println("== 事件判定：只认命令之后的可用事件 ==");

        // 场景：命令在 t=1000 发出
        final long CMD = 1000;

        // 1) \u547d\u4ee4\u524d\u5c31\u6709\u7684\u8fde\u63a5 \u2192 \u4e0d\u7b97
        List<Ev> before = new ArrayList<>();
        before.add(new Ev("B214", true, 500));      // \u547d\u4ee4\u4e4b\u524d\u5c31\u8fde\u4e0a\u4e86
        check("命令前的可用事件 → 不算命中",
                firstAvailableAfter(before, "B214", CMD) < 0);

        // 2) \u547d\u4ee4\u4e4b\u540e\u7684\u53ef\u7528\u4e8b\u4ef6 \u2192 \u7b97
        List<Ev> after = new ArrayList<>();
        after.add(new Ev("B214", true, 500));
        after.add(new Ev("B214", true, 1500));      // \u547d\u4ee4\u4e4b\u540e
        check("命令后的可用事件 → 算命中",
                firstAvailableAfter(after, "B214", CMD) == 1500);

        // 3) \u65ad\u5f00\u540e\u53c8\u8fde\u4e0a\uff08\u7cfb\u7edf\u56de\u8fde\uff09\u2014\u2014\u8fd9\u4e2a\u786e\u5b9e\u4f1a\u88ab\u7b97\u4f5c\u547d\u4e2d
        //    \u6240\u4ee5\u9700\u8981 verifyHit \u590d\u9a8c\u624d\u80fd\u5206\u8fa8\u771f\u5047
        List<Ev> reconn = new ArrayList<>();
        reconn.add(new Ev("B214", false, 800));
        reconn.add(new Ev("B214", true, 1600));
        check("断开后系统自己回连 → 事件层会算命中（因此需要复验）",
                firstAvailableAfter(reconn, "B214", CMD) == 1600);

        // 4) \u5176\u4ed6 SSID \u7684\u4e8b\u4ef6\u4e0d\u5f71\u54cd
        List<Ev> other = new ArrayList<>();
        other.add(new Ev("B209", true, 1200));
        check("别的网络的可用事件 → 不算命中",
                firstAvailableAfter(other, "B214", CMD) < 0);

        // 5) \u7a7a\u5386\u53f2
        check("无事件 → 不算命中",
                firstAvailableAfter(new ArrayList<>(), "B214", CMD) < 0);

        // 6) null SSID
        check("null SSID → 不算命中",
                firstAvailableAfter(after, null, CMD) < 0);

        // 7) \u53ea\u6709\u4e22\u5931\u4e8b\u4ef6
        List<Ev> lostOnly = new ArrayList<>();
        lostOnly.add(new Ev("B214", false, 1500));
        check("只有丢失事件 → 不算命中",
                firstAvailableAfter(lostOnly, "B214", CMD) < 0);

        // 8) \u65f6\u95f4\u754c\u9650\uff1a\u6070\u597d\u7b49\u4e8e CMD \u7684\u4e8b\u4ef6\u5e94\u7b97
        List<Ev> exact = new ArrayList<>();
        exact.add(new Ev("B214", true, CMD));
        check("事件时刻 == 命令时刻 → 算命中（边界包含）",
                firstAvailableAfter(exact, "B214", CMD) == CMD);

        System.out.println("\n=== 事件判定测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
