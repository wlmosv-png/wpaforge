package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.Capability;
import io.github.wlmosv_png.wpaforge.ui.CapabilityCenter;

/**
 * 权限与能力中心的**状态映射**门禁（任务书第六部分）。
 *
 * <p>核心约束：**UNKNOWN 不得被染成「不支持」的颜色/文案**。
 * 这是历史上最贵的一类误读 —— 把「问不到」显示成「做不到」，
 * 用户会去换设备，而实际只是探测没拿到结果。
 */
public class CapabilityCenterTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        CapabilityCenterTest t = new CapabilityCenterTest();
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
        unknownIsGrayNotRed();
        stateLabelsDistinct();
        degradedIsNotUnsupported();
        needsGrantIsNotUnsupported();
    }

    /** UNKNOWN 必须是「灰」而不是「红」—— 视觉上不能读成不支持。 */
    private void unknownIsGrayNotRed() {
        int unknown = CapabilityCenter.stateColor(Capability.STATE_UNKNOWN);
        int unsupported = CapabilityCenter.stateColor(Capability.STATE_UNSUPPORTED);
        ok("UNKNOWN 与 UNSUPPORTED 颜色不同", unknown != unsupported);
        // 灰应该比红更接近中性：用亮度粗略判断不可靠，直接断言等于 TEXT_FAINT
        ok("UNKNOWN 用 TEXT_FAINT（中性灰）",
                unknown == io.github.wlmosv_png.wpaforge.ui.AmberTheme.TEXT_FAINT);
        ok("UNSUPPORTED 用 DANGER（红）",
                unsupported == io.github.wlmosv_png.wpaforge.ui.AmberTheme.DANGER);
    }

    /** 五种状态的标签必须两两不同（不能糊成一句话）。 */
    private void stateLabelsDistinct() {
        String[] states = {
                Capability.STATE_AVAILABLE, Capability.STATE_DEGRADED,
                Capability.STATE_NEEDS_GRANT, Capability.STATE_UNSUPPORTED,
                Capability.STATE_UNKNOWN
        };
        java.util.Set<String> zh = new java.util.HashSet<>();
        java.util.Set<String> en = new java.util.HashSet<>();
        for (String s : states) {
            zh.add(CapabilityCenter.stateLabel(s, true));
            en.add(CapabilityCenter.stateLabel(s, false));
        }
        ok("中文标签五态互异", zh.size() == 5);
        ok("英文标签五态互异", en.size() == 5);
        // 关键：UNKNOWN 的标签不能是「不支持」
        ok("UNKNOWN 标签不含「不支持」",
                !CapabilityCenter.stateLabel(Capability.STATE_UNKNOWN, true).contains("不支持"));
        ok("UNKNOWN 标签含「未知」",
                CapabilityCenter.stateLabel(Capability.STATE_UNKNOWN, true).contains("未知"));
    }

    /** DEGRADED 不能显示成 UNSUPPORTED。 */
    private void degradedIsNotUnsupported() {
        int d = CapabilityCenter.stateColor(Capability.STATE_DEGRADED);
        int u = CapabilityCenter.stateColor(Capability.STATE_UNSUPPORTED);
        ok("DEGRADED 与 UNSUPPORTED 颜色不同", d != u);
        ok("DEGRADED 标签不是「不支持」",
                !CapabilityCenter.stateLabel(Capability.STATE_DEGRADED, true).equals("不支持"));
    }

    /** NEEDS_GRANT（BLOCKED）不能显示成 UNSUPPORTED。 */
    private void needsGrantIsNotUnsupported() {
        int n = CapabilityCenter.stateColor(Capability.STATE_NEEDS_GRANT);
        int u = CapabilityCenter.stateColor(Capability.STATE_UNSUPPORTED);
        ok("NEEDS_GRANT 与 UNSUPPORTED 颜色不同", n != u);
        ok("NEEDS_GRANT 标签不是「不支持」",
                !CapabilityCenter.stateLabel(Capability.STATE_NEEDS_GRANT, true).equals("不支持"));
    }
}
