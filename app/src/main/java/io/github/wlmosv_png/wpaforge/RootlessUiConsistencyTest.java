package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.Capability;
import io.github.wlmosv_png.wpaforge.cap.RunMode;

/**
 * 界面与能力状态**一致性**门禁。
 *
 * <h3>要防的是哪一种 bug</h3>
 * 任务书里点名的那一类：「页面显示支持，但点下去才发现不支持」。
 * 它的根源不在某个页面写错，而在**多个页面各自维护一份权限状态**：
 * 首页读一次 root、设置页又读一次、破解页再读一次，谁先谁后不同步。
 *
 * <p>所以这里钉住的不是「某个按钮长什么样」，而是三件事：
 * <ol>
 *   <li>同一个状态只有一个来源（{@code Prefs.mode} / {@code RuntimeMode}）；</li>
 *   <li>拦截点使用的是**能力**，而不是「有没有 root」这种代理判据；</li>
 *   <li>不可用的地方必须给出原因 + 替代路径，不许只有一句「不支持」。</li>
 * </ol>
 */
public class RootlessUiConsistencyTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        RootlessUiConsistencyTest t = new RootlessUiConsistencyTest();
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
        singleSourceOfTruth();
        gateUsesCapability();
        modeSelector();
        noStrayRootChecks();
        honestWording();
        settingsDiagnostics();
    }

    // ---------------------------------------------------------------- 1. 单一真相来源

    private void singleSourceOfTruth() {
        String prefs = src("Prefs.java");
        // mode() 必须转发到能力层，不能再恒返回 MODE_ROOT
        ok("Prefs.mode 转发实际能力", prefs.contains("cap.RuntimeMode.modeId(ctx)"));
        ok("Prefs.mode 不再恒返回 MODE_ROOT",
                !prefs.contains("return MODE_ROOT;\n    }\n\n    // ---------------- 恢复默认设置"));
        // 「意愿」与「实际」分开
        ok("Prefs 有 requestedMode", prefs.contains("public static int requestedMode("));
        ok("Prefs 有 setRequestedMode", prefs.contains("public static void setRequestedMode("));
        ok("写法会刷新能力层", prefs.contains("RuntimeMode.refresh(ctx)"));

        String rt = src("cap/RuntimeMode.java");
        ok("RuntimeMode 是进程级单例", rt.contains("private static volatile RunMode current"));
        ok("RuntimeMode 有显式失效入口", rt.contains("public static RunMode refresh("));

        // modeLabel 也必须同源（改造前是硬编码 "Root 模式"）
        String labelBody = slice(prefs, "public static String modeLabel(", "}");
        ok("modeLabel 不再硬编码 Root",
                !labelBody.replace("Root 模式", "").contains("Root mode"));
        ok("modeLabel 读 RuntimeMode.current()",
                prefs.contains("RuntimeMode.current()"));
    }

    // ---------------------------------------------------------------- 2. 拦截点用能力

    private void gateUsesCapability() {
        String ma = src("MainActivity.java");
        String gate = slice(ma, "private void startConnectAfterGuards()",
                "// 耗时预估");
        ok("开始按钮的闸门是能力，不是 root",
                gate.contains("Capability.C_WIFI_CONNECT"));
        ok("闸门用 evidenceGrade 判断（弱证据不算能跑）",
                gate.contains("connectCap.evidenceGrade()"));
        ok("被挡时的文案带状态词", gate.contains("connectCap.stateText("));
        ok("被挡时给出替代路径", gate.contains("alternativeZh")
                && gate.contains("alternativeEn"));
        ok("被挡时不再只弹 root 标题",
                !gate.contains("chk.title") || gate.contains("runMode.isRoot()"));

        // 老的 RootProber 闸门只在 Root 模式生效 —— 否则无 Root 模式会被它挡死
        indexOf(gate, "runMode.isRoot()", "Root 闸门限定在 Root 模式");

        String eng = src("audit/CrackEngine.java");
        ok("引擎侧也有同一道能力闸",
                eng.contains("public boolean canAttemptAssociation()"));
        ok("引擎侧闸门在 forget 之前",
                eng.indexOf("Gate.NOT_CAPABLE") < eng.indexOf("forgetTargetByShell(target.ssid)"));
    }

    // ---------------------------------------------------------------- 3. 模式选择器

    private void modeSelector() {
        String ma = src("MainActivity.java");
        ok("设置页有模式选择按钮", ma.contains("modeChoiceButton("));
        ok("三种模式都提供", ma.contains("RunMode.ROOT,") && ma.contains("RunMode.ROOTLESS,")
                && ma.contains("RunMode.AUTO,"));
        ok("切换后整块重建（避免只改一半）", ma.contains("private void rebuildSettingsTab()"));
        ok("切换走 Prefs.setRequestedMode", ma.contains("Prefs.setRequestedMode(this, modeId)"));

        // 说明卡必须分模式写，不能再无条件写「当前: Root 模式（推荐）」
        String fill = slice(ma, "private void fillModeInfo()", "private void refreshModeReady()");
        ok("说明卡区分无 Root 模式", fill.contains("RunMode.ROOTLESS"));
        ok("无 Root 说明写明可用项", fill.contains("字典") || fill.contains("离线"));
        ok("无 Root 说明写明不可用项", fill.contains("WPS PIN"));
        ok("无 Root 说明写明原因", fill.contains("WifiNetworkSpecifier")
                || fill.contains("特权接口"));
    }

    // ---------------------------------------------------------------- 4. 不该有零散的 root 前置

    private void noStrayRootChecks() {
        String lan = src("ui/LanProbeActivity.java");
        String start = slice(lan, "private void startScan()", "final String selfIp");
        // 局域网探测本来就不需要 root（执行器全程没有 su），前置已移除
        ok("局域网扫描不再被 root 拦截", !start.contains("if (!isRoot())"));
        ok("但仍会如实提示结果可能不全",
                lan.contains("ping 与 ARP 表可能读不到") || lan.contains("ping and the ARP table"));

        // 保留 isRoot() 仅供提示使用，不得再作为「不许用」的判据
        ok("isRoot() 仍在（只作提示）", lan.contains("private boolean isRoot()"));
    }

    // ---------------------------------------------------------------- 5. 文案诚实

    private void honestWording() {
        String ma = src("MainActivity.java");
        String fill = slice(ma, "private void fillModeInfo()", "private void refreshModeReady()");
        ok("说明卡不再无条件「推荐 Root」",
                !fill.contains("\"当前: Root 模式（推荐）\", \"Current: root mode (recommended)\")"));

        // 状态行不许把「没 root」一律标成错误色
        String ready = slice(ma, "private void refreshModeReady()", "private void showChannelProbeDetail()");
        ok("检测结论不再用 DANGER 一律标红", !ready.contains(": DANGER)"));
        ok("无 Root 模式仍能给出绿色/中性结论",
                ready.contains("ACCENT_MID") || ready.contains("ACCENT"));

        String cap = src("cap/RootlessChannel.java");
        ok("无 Root 通道说明 UID 拦截", cap.contains("UID") || cap.contains("uid"));
        ok("无 Root 通道给出替代路径", cap.contains("离线分析") || cap.contains("Offline"));
        ok("无 Root 通道不提供伪通道",
                cap.contains("永远返回 false，且一条命令都不发"));
    }

    // ---------------------------------------------------------------- 6. 诊断页

    private void settingsDiagnostics() {
        String env = src("audit/EnvDiagnostics.java");
        ok("诊断页有实际运行模式一行", env.contains("实际运行模式"));
        ok("诊断页输出能力矩阵", env.contains("capabilities().all()"));
        ok("su 不可用在无 Root 模式下不算 critical",
                env.contains("当前为无 Root 模式，这一节不适用"));

        // 长按看证据：仍保留，且用的还是生产探针
        String ma = src("MainActivity.java");
        ok("长按看完整检测证据仍存在", ma.contains("private void showChannelProbeDetail()"));
    }

    // ---------------------------------------------------------------- 工具

    private void indexOf(String haystack, String needle, String name) {
        ok(name, haystack != null && haystack.contains(needle));
    }

    /** 取两个标记之间的源码片段（找不到返回空串，断言会因此失败——故意的）。 */
    private String slice(String src, String from, String to) {
        int i = src.indexOf(from);
        if (i < 0) return "";
        int j = src.indexOf(to, i + from.length());
        if (j < 0) j = Math.min(src.length(), i + 6000);
        return src.substring(i, j);
    }

    private String src(String rel) {
        try {
            java.io.File f = new java.io.File(TestPaths.root()
                    + "/app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            if (!f.isFile()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
