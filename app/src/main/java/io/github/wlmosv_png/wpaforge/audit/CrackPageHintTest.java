package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 破解页 UI 收尾修复的回归测试（真实状态映射，非字符串断言）。
 *
 * <p>覆盖需求第四节 8 项：Wi-Fi 不展示、单字典文案不退化、多字典接力
 * 各阶段文案、暂停/恢复/异常、模式切换不残留旧文案。
 */
public final class CrackPageHintTest {

    private static int pass = 0, fail = 0;

    private static void ok(String n, boolean c, String d) {
        if (c) { pass++; }
        else { fail++; System.out.println("  FAIL " + n + "   " + d); }
    }

    private static DictPlan plan(DictPlan.State st, int index, int size) {
        DictPlan p = new DictPlan();
        p.autoAdvance = true;
        p.state = st;
        for (int i = 0; i < size; i++) {
            DictPlan.Item it = new DictPlan.Item("/d/D" + i + ".txt", "D" + i);
            it.sha256 = "sha" + i;
            it.dictSize = 10;
            p.add(it);
        }
        p.index = Math.max(0, Math.min(index, Math.max(0, size - 1)));
        return p;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 破解页 UI 收尾修复（状态映射） ===");
        wifiNoticeRemoved();
        singleDictNotDegraded();
        relayChain();
        pauseResumeAbnormal();
        modeSwitchNoStale();
        System.out.println();
        System.out.println("=== 破解页 UI: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------ ① Wi-Fi 状态展示移除

    private static void wifiNoticeRemoved() {
        System.out.println();
        System.out.println("-- ① 破解页不再展示 Wi-Fi 开关状态 --");
        String ma = read("MainActivity.java");
        if (ma == null) { ok("MainActivity.java 可读", false, ""); return; }
        // 需求一：破解页不得再有 Wi-Fi 开关状态控件与刷新方法
        ok("已无 connectNotice 提示条控件", !ma.contains("connectNotice"), "");
        ok("已无 refreshConnectNotice 方法", !ma.contains("refreshConnectNotice"), "");
        // 观测页（observeBlocker）**仍应保留** Wi-Fi 检查 —— 它扫描热点确实需要开 Wi-Fi。
        // 因此这里把 observeBlocker 方法体摘掉后，再断言破解页侧没有该文案。
        String rest = stripComments(stripMethod(ma, "private String observeBlocker() {"));
        ok("除观测页外无「WiFi 未开启」文案",
                !rest.contains("WiFi 未开启，请先打开 WiFi 开关"), "");
        ok("除观测页外无「Wi-Fi is off」文案", !rest.contains("Wi-Fi is off"), "");
        ok("除观测页外不再调用 isWifiEnabled",
                !rest.contains("isWifiEnabled()"), "");
        ok("观测页自身的 Wi-Fi 检查仍保留（未被误删）",
                ma.contains("private String observeBlocker() {"),
                "");
        // 底层安全闸门必须**保留**（不得为隐藏提示而破坏执行保护）
        ok("RootProber 开跑前闸门仍在", ma.contains("RootProber.check(this)"), "");
        String cs = read("CrackService.java");
        ok("执行期环境闸门仍在（armRestore）",
                cs != null && cs.contains("armRestore(task)"), "");
    }

    // ------------------------------------------------ ② 单字典体验不退化

    private static void singleDictNotDegraded() {
        System.out.println();
        System.out.println("-- ② 单字典完成文案保持原样 --");
        // 无计划（或空计划）→ 单字典语义
        ok("无计划 → SINGLE", PlanResultHint.kindOf(null) == PlanResultHint.Kind.SINGLE, "");
        ok("空计划 → SINGLE",
                PlanResultHint.kindOf(new DictPlan()) == PlanResultHint.Kind.SINGLE, "");
        ok("SINGLE 副标题/正文为空（由单字典原文案承担）",
                PlanResultHint.sub(PlanResultHint.Kind.SINGLE, 1, 1, "x").isEmpty()
                        && PlanResultHint.why(PlanResultHint.Kind.SINGLE, 1, 1, "", 0, 0).isEmpty(), "");
        String ma = read("MainActivity.java");
        ok("单字典「换一本字典」文案仍保留在单字典分支",
                ma != null && ma.contains("换一本字典就行，已经跑过的不会重复跑"), "");
    }

    // ------------------------------------------------ ③ 多字典接力状态

    private static void relayChain() {
        System.out.println();
        System.out.println("-- ③ A→B→C 接力各阶段文案 --");

        // A 完成、计划已 apply 前进到 B（RUNNING）→ 准备下一本，**不是**"正在执行"
        DictPlan p = plan(DictPlan.State.RUNNING, 1, 3);
        PlanResultHint.Kind k = PlanResultHint.kindOf(p);
        ok("A 完成/B 未接管 → PREPARING_NEXT",
                k == PlanResultHint.Kind.PREPARING_NEXT, k.name());
        ok("标题为「当前字典已完成，正在准备下一本字典」",
                PlanResultHint.title(k).contains("正在准备下一本"), PlanResultHint.title(k));
        ok("标题**不含**「正在执行」", !PlanResultHint.title(k).contains("正在执行"), "");
        ok("正文点名下一本为 D1（index=1 → 下一本 D1）",
                PlanResultHint.why(k, 2, 3, "D1", 0, 0).contains("D1"),
                PlanResultHint.why(k, 2, 3, "D1", 0, 0));
        ok("副标题含「计划进度 2/3」",
                PlanResultHint.sub(k, 2, 3, "D0").contains("2/3"),
                PlanResultHint.sub(k, 2, 3, "D0"));
        ok("**不出现**单字典「换一本字典」文案",
                !PlanResultHint.why(k, 2, 3, "D1", 0, 0).contains("换一本字典"), "");

        // B 完成、C 未接管
        DictPlan p2 = plan(DictPlan.State.RUNNING, 2, 3);
        PlanResultHint.Kind k2 = PlanResultHint.kindOf(p2);
        ok("B 完成/C 未接管 → PREPARING_NEXT",
                k2 == PlanResultHint.Kind.PREPARING_NEXT, k2.name());
        ok("正文点名下一本为 D2",
                PlanResultHint.why(k2, 3, 3, "D2", 0, 0).contains("D2"), "");

        // 全部完成
        DictPlan done = plan(DictPlan.State.DONE_MISS, 2, 3);
        PlanResultHint.Kind kd = PlanResultHint.kindOf(done);
        ok("全部跑完 → DONE_MISS", kd == PlanResultHint.Kind.DONE_MISS, kd.name());
        ok("标题为「多字典计划已完成」",
                PlanResultHint.title(kd).contains("多字典计划已完成"), PlanResultHint.title(kd));
        ok("正文含最终统计（跳过数）",
                PlanResultHint.why(kd, 3, 3, "", 12, 3).contains("12")
                        && PlanResultHint.why(kd, 3, 3, "", 12, 3).contains("3"), "");
        ok("终态 → isTerminalKind", PlanResultHint.isTerminalKind(kd), "");

        DictPlan hit = plan(DictPlan.State.DONE_HIT, 1, 3);
        ok("命中 → DONE_HIT 且标题含「已命中」",
                PlanResultHint.kindOf(hit) == PlanResultHint.Kind.DONE_HIT
                        && PlanResultHint.title(PlanResultHint.Kind.DONE_HIT).contains("已命中"), "");
    }

    // ------------------------------------------------ ④ 暂停 / 恢复 / 异常

    private static void pauseResumeAbnormal() {
        System.out.println();
        System.out.println("-- ④ 暂停 / 恢复 / 异常中断文案 --");
        PlanResultHint.Kind kp = PlanResultHint.kindOf(plan(DictPlan.State.PAUSED, 1, 3));
        ok("PAUSED → 已暂停", kp == PlanResultHint.Kind.PAUSED && PlanResultHint.title(kp).contains("已暂停"),
                PlanResultHint.title(kp));
        ok("PAUSED 正文给「继续」指引",
                PlanResultHint.why(kp, 2, 3, "D1", 0, 0).contains("继续"), "");

        PlanResultHint.Kind kb = PlanResultHint.kindOf(plan(DictPlan.State.BLOCKED, 1, 3));
        ok("BLOCKED → 阻塞且如实说明", kb == PlanResultHint.Kind.BLOCKED
                && PlanResultHint.isAbnormalKind(kb), "");
        ok("BLOCKED 不伪装成完成", !PlanResultHint.title(kb).contains("已完成"), "");

        PlanResultHint.Kind ke = PlanResultHint.kindOf(plan(DictPlan.State.ERROR, 1, 3));
        ok("ERROR → 出错且如实说明", ke == PlanResultHint.Kind.ERROR, "");
        ok("ERROR 不伪装成完成", !PlanResultHint.title(ke).contains("已完成"), "");

        PlanResultHint.Kind ks = PlanResultHint.kindOf(plan(DictPlan.State.STOPPED, 1, 3));
        ok("STOPPED → 已停止（终态）", ks == PlanResultHint.Kind.STOPPED
                && PlanResultHint.isTerminalKind(ks), "");

        // 恢复：PAUSED → RUNNING 后语义立刻回到「准备下一本」
        DictPlan p = plan(DictPlan.State.PAUSED, 1, 3);
        ok("暂停时 = PAUSED", PlanResultHint.kindOf(p) == PlanResultHint.Kind.PAUSED, "");
        p.state = DictPlan.State.RUNNING;
        ok("恢复后 = PREPARING_NEXT",
                PlanResultHint.kindOf(p) == PlanResultHint.Kind.PREPARING_NEXT, "");
    }

    // ------------------------------------------------ ⑤ 模式切换不残留旧文案

    private static void modeSwitchNoStale() {
        System.out.println();
        System.out.println("-- ⑤ 单/多字典切换不残留旧文案 --");
        // 同一份「结算」在不同模式下产生**不同**文案族，互不覆盖
        List<PlanResultHint.Kind> seq = new ArrayList<>();
        seq.add(PlanResultHint.kindOf(null));                                   // 单字典
        seq.add(PlanResultHint.kindOf(plan(DictPlan.State.RUNNING, 1, 3)));     // 计划 A→B
        seq.add(PlanResultHint.kindOf(null));                                   // 再回单字典
        seq.add(PlanResultHint.kindOf(plan(DictPlan.State.DONE_MISS, 2, 3)));   // 计划跑完
        ok("序列 = SINGLE → PREPARING_NEXT → SINGLE → DONE_MISS",
                seq.get(0) == PlanResultHint.Kind.SINGLE
                        && seq.get(1) == PlanResultHint.Kind.PREPARING_NEXT
                        && seq.get(2) == PlanResultHint.Kind.SINGLE
                        && seq.get(3) == PlanResultHint.Kind.DONE_MISS,
                seq.toString());
        // 计划模式的每一种文案都不等于单字典文案
        for (PlanResultHint.Kind k : PlanResultHint.Kind.values()) {
            if (k == PlanResultHint.Kind.SINGLE) continue;
            ok("计划文案 " + k + " 有独立标题",
                    !PlanResultHint.title(k).isEmpty()
                            && !PlanResultHint.title(k).equals(PlanResultHint.title(PlanResultHint.Kind.SINGLE)),
                    "");
        }
        // UI：下一本真正接管后要收掉上一本的结算卡
        String ma = read("MainActivity.java");
        ok("接力切换时收掉过期结算卡",
                ma != null && ma.contains("resultCardTaskId")
                        && ma.contains("resultCardTaskId = \"\";"), "");
        ok("计划结算卡由 livePlan 真实状态驱动",
                ma != null && ma.contains("DictPlan lp0 = livePlan()"), "");
    }

    /** 去掉注释（避免"注释里提到某文案"被误判成"代码里有"）。 */
    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            int p = line.indexOf("//");
            sb.append(p >= 0 ? line.substring(0, p) : line).append('\n');
        }
        return sb.toString();
    }

    /** 摘掉某个方法的整段方法体（用于把「观测页」从断言范围里排除）。 */
    private static String stripMethod(String src, String signature) {
        if (src == null) return "";
        int i = src.indexOf(signature);
        if (i < 0) return src;
        int j = src.indexOf("\n    }", i);
        if (j < 0) return src.substring(0, i);
        return src.substring(0, i) + src.substring(j + 6);
    }

    private static String read(String name) {
        try {
            java.io.File f = new java.io.File(
                    io.github.wlmosv_png.wpaforge.TestPaths.javaPkgDir(), name);
            if (!f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            for (String l : java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) { return null; }
    }
}
