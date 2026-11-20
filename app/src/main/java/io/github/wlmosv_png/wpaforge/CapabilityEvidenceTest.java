package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.Capability;
import io.github.wlmosv_png.wpaforge.cap.CapabilityProbe;

import java.util.List;

/**
 * 能力检测**证据完整性**门禁（任务书第二节：「不要只保存一个 Boolean」）。
 *
 * <p>每一项检测必须能回答：检测对象、通道、时间、原始返回、判断依据、
 * 是否可重试、影响哪些功能。本类钉住这些字段真的被填、且不会被
 * 悄悄退化成「只有一句 state」。
 */
public class CapabilityEvidenceTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        CapabilityEvidenceTest t = new CapabilityEvidenceTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        fiveStates();
        evidenceFields();
        diagnosticLineSafe();
        rootConnectCarriesEvidence();
        unknownIsNotUnsupported();
    }

    /** 五态必须齐全，且互不相等。 */
    private void fiveStates() {
        String[] all = {Capability.STATE_AVAILABLE, Capability.STATE_NEEDS_GRANT,
                Capability.STATE_UNSUPPORTED, Capability.STATE_UNKNOWN, Capability.STATE_DEGRADED};
        java.util.Set<String> uniq = new java.util.HashSet<>();
        for (String s : all) uniq.add(s);
        ok("五态齐全且互异", uniq.size() == 5);
        // 关键语义：UNKNOWN 不得等于 UNSUPPORTED
        ok("UNKNOWN ≠ UNSUPPORTED",
                !Capability.STATE_UNKNOWN.equals(Capability.STATE_UNSUPPORTED));
    }

    /** withEvidence 必须真的把字段写进去。 */
    private void evidenceFields() {
        Capability c = Capability.available("x", "可用", "available")
                .withEvidence("cmd wifi status", "Shizuku", 12345L,
                        "Wifi is enabled", "实际调用返回状态文本", true,
                        Capability.C_WIFI_READ);
        ok("subject 已填", "cmd wifi status".equals(c.subject));
        ok("channel 已填", "Shizuku".equals(c.channel));
        ok("probedAtMs 已填", c.probedAtMs == 12345L);
        ok("rawEvidence 已填", c.rawEvidence.contains("Wifi is enabled"));
        ok("basis 已填", !c.basis.isEmpty());
        ok("retryable 已填", c.retryable);
        ok("impacts 已填", c.impacts.length == 1
                && Capability.C_WIFI_READ.equals(c.impacts[0]));
        ok("hasEvidence 为真", c.hasEvidence());

        // 没挂证据的项必须如实报告「没有证据」，不能假装有
        Capability bare = Capability.unsupported("y", "不支持", "unsupported");
        ok("裸构造 hasEvidence 为假", !bare.hasEvidence());
        ok("裸构造 subject 为空", bare.subject.isEmpty());
    }

    /** diagnosticLine 必须能还原判断过程，且绝不含口令。 */
    private void diagnosticLineSafe() {
        Capability c = Capability.unsupported("wifi_connect",
                "本机没有 connect-network", "no connect-network here")
                .withEvidence("cmd wifi connect-network", "root shell", 999L,
                        "Unknown command: connect-network",
                        "实测返回 Unknown command", true, Capability.C_WIFI_CONNECT);
        String line = c.diagnosticLine(true);
        ok("诊断行含 state", line.contains("[UNSUPPORTED]"));
        ok("诊断行含对象", line.contains("cmd wifi connect-network"));
        ok("诊断行含通道", line.contains("通道=root shell"));
        ok("诊断行含原始返回", line.contains("Unknown command"));
        ok("诊断行含可重试", line.contains("可重试="));
        ok("诊断行含影响", line.contains("影响=wifi_connect"));
        ok("诊断行不含口令形态", !line.matches(".*\\b\\d{8,}\\b.*"));
    }

    /** Root 侧 explainRoot 在 connect 可用时必须带证据。 */
    private void rootConnectCarriesEvidence() {
        CapabilityProbe.Facts f = new CapabilityProbe.Facts();
        f.probedAtMs = 777L;
        f.cmdConnectProbe = "Invalid args for connect-network: Argument expected";
        List<Capability> items = CapabilityProbe.explainRoot("uid=0(root)", null, f, true, true);
        Capability c = null;
        for (Capability x : items) {
            if (Capability.C_WIFI_CONNECT.equals(x.id)) c = x;
        }
        ok("explainRoot 产出 connect 能力", c != null);
        if (c != null) {
            ok("connect 是 AVAILABLE", Capability.STATE_AVAILABLE.equals(c.state));
            ok("connect 带检测时刻", c.probedAtMs == 777L);
            ok("connect 带原始返回", c.rawEvidence.contains("Invalid args"));
            ok("connect 带通道", !c.channel.isEmpty());
        }
    }

    /** 「问不到」必须是 UNKNOWN，不能变成 UNSUPPORTED。 */
    private void unknownIsNotUnsupported() {
        // 三个探测全空 = 问不到
        CapabilityProbe.Facts f = new CapabilityProbe.Facts();
        f.probedAtMs = 1L;
        List<Capability> items = CapabilityProbe.explainRoot("uid=0(root)", null, f, true, true);
        for (Capability c : items) {
            if (Capability.C_WIFI_CONNECT.equals(c.id)) {
                ok("问不到 → UNKNOWN（不是 UNSUPPORTED）",
                        Capability.STATE_UNKNOWN.equals(c.state));
            }
            if (Capability.C_WIFI_FORGET.equals(c.id)) {
                ok("forget 问不到 → UNKNOWN",
                        Capability.STATE_UNKNOWN.equals(c.state));
            }
        }

        // 明确 Unknown command → 才是 UNSUPPORTED
        CapabilityProbe.Facts g = new CapabilityProbe.Facts();
        g.probedAtMs = 2L;
        g.cmdConnectProbe = "Unknown command: connect-network";
        List<Capability> items2 = CapabilityProbe.explainRoot("uid=0(root)", null, g, true, true);
        for (Capability c : items2) {
            if (Capability.C_WIFI_CONNECT.equals(c.id)) {
                ok("Unknown command → UNSUPPORTED",
                        Capability.STATE_UNSUPPORTED.equals(c.state));
            }
        }
    }
}
