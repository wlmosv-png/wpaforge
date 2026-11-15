package io.github.wlmosv_png.wpaforge.wps;

/**
 * PIN 退避的门禁（2026-11-15）。
 *
 * <p>这一块的错法只有两种，都很隐蔽：
 * <ol>
 *   <li><b>把「进展」当成「被拒」</b>（HALF_OK / M2D_THEN_MORE / SUCCESS）
 *       —— 那正是拆半攻击最有价值的信号，在那一刻罚停等于自断线索；</li>
 *   <li><b>把「根本没发出去」当成「AP 拒了我们」</b>（SKIPPED_INVALID
 *       —— 校验位不合法的 PIN 本地就被拦下了），会让计数虚高。</li>
 * </ol>
 * 所以下面逐类穷举这三族的边界。
 */
public class WpsPinBackoffTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    public static void main(String[] a) {
        System.out.println("== 归类：被拒 / 超时 ==");
        String[] rej = {"REJECTED", "PIN_REJECTED", "TIMEOUT", "NO_RESPONSE",
                "NO_ASSOC", "LINK_LOST", "CMD_FAIL", "ERROR"};
        boolean allRej = true;
        for (String s : rej) if (!WpsPinBackoff.isRejectOrTimeout(s)) allRej = false;
        ok("八种被拒/超时全认", allRej);
        ok("大小写无关", WpsPinBackoff.isRejectOrTimeout("pin_rejected")
                && WpsPinBackoff.isRejectOrTimeout(" rejected "));
        ok("null 安全", !WpsPinBackoff.isRejectOrTimeout(null)
                && !WpsPinBackoff.isRejectOrTimeout(""));

        System.out.println("== 归类：进展（绝不能被当成被拒）==");
        ok("SUCCESS 不算被拒", !WpsPinBackoff.isRejectOrTimeout("SUCCESS")
                && WpsPinBackoff.isProgress("SUCCESS"));
        ok("SUCCESS_UNVERIFIED 算进展", WpsPinBackoff.isProgress("SUCCESS_UNVERIFIED")
                && !WpsPinBackoff.isRejectOrTimeout("SUCCESS_UNVERIFIED"));
        ok("HALF_OK 算进展", WpsPinBackoff.isProgress("HALF_OK")
                && !WpsPinBackoff.isRejectOrTimeout("HALF_OK"));
        ok("M2D_THEN_MORE 算进展", WpsPinBackoff.isProgress("M2D_THEN_MORE")
                && !WpsPinBackoff.isRejectOrTimeout("M2D_THEN_MORE"));

        System.out.println("== 归类：没发出去（既不算被拒也不算进展）==");
        ok("SKIPPED_INVALID 不是被拒", !WpsPinBackoff.isRejectOrTimeout("SKIPPED_INVALID"));
        ok("SKIPPED_INVALID 不是进展", !WpsPinBackoff.isProgress("SKIPPED_INVALID"));
        ok("未知值两边都不沾", !WpsPinBackoff.isRejectOrTimeout("WEIRD")
                && !WpsPinBackoff.isProgress("WEIRD"));

        System.out.println("== 触发：关掉就永不暂停 ==");
        WpsPinBackoff off = new WpsPinBackoff(false, 60);
        long total = 0;
        for (int i = 0; i < 50; i++) total += off.observe("PIN_REJECTED");
        ok("关闭时不暂停", total == 0, "" + total);
        ok("关闭时仍记录连击", off.consecutive() == 50, "" + off.consecutive());

        System.out.println("== 触发：默认每被拒一次停一分钟 ==");
        WpsPinBackoff on = new WpsPinBackoff(true, 60);
        long first = on.observe("PIN_REJECTED");
        ok("第一次被拒就停 60 秒", first == 60_000L, "" + first);
        ok("暂停后连击清零", on.consecutive() == 0, "" + on.consecutive());
        ok("超时同样触发", on.observe("TIMEOUT") == 60_000L);
        ok("进展打断连击", on.observe("HALF_OK") == 0 && on.consecutive() == 0);

        System.out.println("== 触发：afterN = 连续 N 次才停 ==");
        WpsPinBackoff n3 = new WpsPinBackoff(true, 30, 3);
        ok("第 1 次不停", n3.observe("REJECTED") == 0);
        ok("第 2 次不停", n3.observe("REJECTED") == 0);
        ok("第 3 次停 30 秒", n3.observe("REJECTED") == 30_000L);
        ok("停完重新计数", n3.consecutive() == 0);
        ok("中间夹一条进展即打断", n3.observe("REJECTED") == 0
                && n3.observe("SUCCESS") == 0
                && n3.observe("REJECTED") == 0
                && n3.consecutive() == 1, "" + n3.consecutive());

        System.out.println("== 触发：非法候选不得虚增计数（也不得清零）==");
        WpsPinBackoff n2 = new WpsPinBackoff(true, 60, 2);
        ok("先来一条被拒", n2.observe("REJECTED") == 0 && n2.consecutive() == 1);
        ok("夹一条 SKIPPED_INVALID 不清零", n2.observe("SKIPPED_INVALID") == 0
                && n2.consecutive() == 1, "" + n2.consecutive());
        ok("下一条被拒即凑满 2 次 → 停", n2.observe("REJECTED") == 60_000L);

        System.out.println("== 时长夹取 ==");
        ok("下限 5 秒", WpsPinBackoff.clampPauseSec(0) == 5
                && WpsPinBackoff.clampPauseSec(-100) == 5);
        ok("上限 600 秒", WpsPinBackoff.clampPauseSec(99999) == 600);
        ok("区间内原样", WpsPinBackoff.clampPauseSec(120) == 120);
        ok("默认 60 秒", WpsPinBackoff.DEFAULT_PAUSE_SEC == 60);
        ok("预设档都是合法值", allPresetsLegal());
        WpsPinBackoff clamp = new WpsPinBackoff(true, 1);
        ok("构造时也夹", clamp.pauseSec() == WpsPinBackoff.MIN_PAUSE_SEC, "" + clamp.pauseSec());

        System.out.println("== 文案 ==");
        WpsPinBackoff t = new WpsPinBackoff(true, 60);
        ok("等待文案带秒数", t.waitText(42, true).contains("42"));
        ok("0 秒不显示文案", t.waitText(0, true).isEmpty() && t.waitText(-1, true).isEmpty());
        ok("英文文案非空", !t.waitText(42, false).isEmpty());

        System.out.println("== reset ==");
        WpsPinBackoff r = new WpsPinBackoff(true, 60, 5);
        r.observe("REJECTED");
        r.observe("REJECTED");
        r.reset();
        ok("reset 后连击归零", r.consecutive() == 0);
        ok("reset 后要重新数满 5 次", r.observe("REJECTED") == 0 && r.consecutive() == 1);

        System.out.println();
        System.out.println("=== PIN 退避: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static boolean allPresetsLegal() {
        boolean hasDefault = false;
        for (int s : WpsPinBackoff.PRESET_SEC) {
            if (s < WpsPinBackoff.MIN_PAUSE_SEC || s > WpsPinBackoff.MAX_PAUSE_SEC) return false;
            if (s == WpsPinBackoff.DEFAULT_PAUSE_SEC) hasDefault = true;
        }
        return WpsPinBackoff.PRESET_SEC.length > 0 && hasDefault;
    }
}
