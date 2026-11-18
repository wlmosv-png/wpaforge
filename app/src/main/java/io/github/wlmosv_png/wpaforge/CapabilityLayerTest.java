package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.Capability;
import io.github.wlmosv_png.wpaforge.cap.CapabilityProbe;
import io.github.wlmosv_png.wpaforge.cap.RunMode;

import java.util.List;

/**
 * 能力层门禁：把「能力状态」的语义钉死。
 *
 * <h3>为什么这些用例值得单独存在</h3>
 * 无 Root 适配最容易犯的错不是「功能没做」，而是**把状态说错**：
 * <ul>
 *   <li>「没探测」被当成「可用」→ 点了才发现不支持；</li>
 *   <li>「问不到」被当成「本机没有」→ 好机器被挡在门外；</li>
 *   <li>「降级可用」被当成「可用」→ 弱证据当强证据用；</li>
 *   <li>「需要授权」被写成「该设备不支持」→ 用户以为换设备才行。</li>
 * </ul>
 * 本类逐条钉住这些边界，全部是纯逻辑（不碰设备、不起进程）。
 *
 * <p>纯逻辑、可离线测。
 */
public class CapabilityLayerTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        CapabilityLayerTest t = new CapabilityLayerTest();
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
        stateSemantics();
        set0Semantics();
        rootExplainUsageUnknown();
        rootExplainUsageKnown();
        rootExplainNoSu();
        rootlessExplain();
        modeParsing();
        noFlattery();
    }

    // ------------------------------------------------------------ 状态语义

    private void stateSemantics() {
        Capability avail = Capability.available(Capability.C_SHELL, "ok", "ok");
        ok("available.usable", avail.usable());
        ok("available.evidenceGrade", avail.evidenceGrade());
        ok("available.supported", avail.supportedOnThisDevice());

        Capability deg = Capability.degraded(Capability.C_WPS_CAP, "弱", "weak");
        ok("degraded.usable", deg.usable());
        // 核心契约：降级能力不得产出证据级结论
        ok("degraded 不产证据级结论", !deg.evidenceGrade());
        ok("degraded.supported", deg.supportedOnThisDevice());
        ok("degraded 有默认替代文案", deg.alternativeZh != null && !deg.alternativeZh.isEmpty());

        Capability grant = Capability.needsGrant(Capability.C_SCAN, "缺权限", "missing");
        ok("needsGrant.usable=false", !grant.usable());
        ok("needsGrant.evidenceGrade=false", !grant.evidenceGrade());
        // 「需要授权」≠「本机不支持」：这是最容易说错的一条
        ok("needsGrant 仍算本机支持", grant.supportedOnThisDevice());

        Capability uns = Capability.unsupported(Capability.C_WIFI_CONNECT, "做不到", "cannot");
        ok("unsupported.usable=false", !uns.usable());
        ok("unsupported.supported=false", !uns.supportedOnThisDevice());

        Capability unk = Capability.unknown(Capability.C_WPA_LOG, "未测", "not probed");
        // 未探测绝不能是「可用」—— 否则界面会先显示能点、点了才说不行
        ok("unknown.usable=false", !unk.usable());
        ok("unknown.evidenceGrade=false", !unk.evidenceGrade());
        ok("unknown.state", Capability.STATE_UNKNOWN.equals(unk.state));

        // 每档都必须有人话状态，不允许空
        for (String st : new String[]{avail.state, deg.state, grant.state, uns.state, unk.state}) {
            ok("状态文案非空:" + st, st != null && !st.isEmpty());
        }
        ok("状态文案互不相同",
                avail.stateText(true).equals("可用")
                        && uns.stateText(true).equals("本机不支持")
                        && grant.stateText(true).equals("需要授权"));
    }

    // ------------------------------------------------------------ 集合语义

    private void set0Semantics() {
        java.util.List<Capability> items = new java.util.ArrayList<>();
        items.add(Capability.available(Capability.C_SHELL, "ok", "ok"));
        items.add(Capability.unsupported(Capability.C_WIFI_CONNECT, "no", "no"));
        Capability.Set0 s = new Capability.Set0("root", 123L, items);

        ok("set.modeId", "root".equals(s.modeId()));
        ok("set.atMs", s.atMs() == 123L);
        ok("set.has", s.has(Capability.C_SHELL));
        // 没探测过的项取出来必须是 UNKNOWN 对象，不能是 null
        Capability missing = s.get(Capability.C_KEEPALIVE);
        ok("set 未探测项不是 null", missing != null);
        ok("set 未探测项是 UNKNOWN", Capability.STATE_UNKNOWN.equals(missing.state));
        ok("set.all 覆盖全部能力", s.all().size() >= Capability.ALL.length);

        List<String> ev = s.evidenceGradeIds();
        ok("set 证据级只含可用项", ev.contains(Capability.C_SHELL)
                && !ev.contains(Capability.C_WIFI_CONNECT));

        String summary = s.summary(true);
        ok("set 摘要点名不支持项", summary.contains("静默关联"));
        ok("set 摘要不用「异常」笼统收尾", !summary.contains("异常"));

        // 空集：不许崩，全部落到 UNKNOWN
        Capability.Set0 empty = new Capability.Set0("rootless", 0L, null);
        ok("空集 get 不崩", empty.get(Capability.C_SHELL) != null);
        ok("空集证据级为空", empty.evidenceGradeIds().isEmpty());
    }

    // ------------------------------------------------------------ Root 解释：用法表问不到

    private void rootExplainUsageUnknown() {
        // su 可用，但 cmd wifi 的用法表拿不到（help 被 ROM 截断 / 输出被吞）
        List<Capability> out = CapabilityProbe.explainRoot("uid=0(root)", null, true, true);
        Capability connect = find(out, Capability.C_WIFI_CONNECT);
        ok("用法表问不到 → connect 判 UNKNOWN 而不是不支持",
                Capability.STATE_UNKNOWN.equals(connect.state));
        ok("用法表问不到 → connect 不产证据级", !connect.evidenceGrade());

        // 空串等同于「问不到」，不能当成「查过且没有」
        List<Capability> out2 = CapabilityProbe.explainRoot("uid=0(root)", "   ", true, true);
        ok("空用法表也按未知处理",
                Capability.STATE_UNKNOWN.equals(find(out2, Capability.C_WIFI_CONNECT).state));

        // 但日志/净检这些不依赖用法表的能力，su 可用就应判可用
        ok("su 可用 → 日志能力可用",
                find(out, Capability.C_WPA_LOG).evidenceGrade());
        ok("su 可用 → 特权扫描可用",
                find(out, Capability.C_SCAN_PRIV).evidenceGrade());
    }

    // ------------------------------------------------------------ Root 解释：用法表拿到了

    private void rootExplainUsageKnown() {
        String usage = "Wi-Fi (wifi) commands:\n"
                + "  status\n  list-networks\n  connect-network\n  forget-network\n";
        List<Capability> out = CapabilityProbe.explainRoot("uid=0(root)", usage, true, true);
        ok("有 connect-network → 可用",
                find(out, Capability.C_WIFI_CONNECT).evidenceGrade());
        ok("有 forget-network → 可用",
                find(out, Capability.C_WIFI_FORGET).evidenceGrade());
        ok("有 status → 读取可用",
                find(out, Capability.C_WIFI_READ).evidenceGrade());

        // Android 9 形态：只有 4 条调试命令，没有 status/connect/forget
        String pie = "Wi-Fi (wifi) commands:\n"
                + "  set-ipreach-disconnect <enabled|disabled>\n"
                + "  get-ipreach-disconnect\n";
        List<Capability> p = CapabilityProbe.explainRoot("uid=0(root)", pie, true, true);
        Capability pc = find(p, Capability.C_WIFI_CONNECT);
        ok("Android 9 → connect 判不支持", Capability.STATE_UNSUPPORTED.equals(pc.state));
        ok("Android 9 → 不支持项必须有替代路径",
                pc.alternativeZh != null && !pc.alternativeZh.isEmpty());
        Capability pf = find(p, Capability.C_WIFI_FORGET);
        ok("Android 9 → forget 判不支持", Capability.STATE_UNSUPPORTED.equals(pf.state));
        // 没有 forget 就必须说清「删不掉旧配置就别信成功」
        ok("forget 缺失时原因点明净检", pf.reasonZh.contains("净检"));
        Capability pr = find(p, Capability.C_WIFI_READ);
        ok("Android 9 → status 缺失时降级而非判死",
                Capability.STATE_DEGRADED.equals(pr.state));
        ok("status 降级不产证据级", !pr.evidenceGrade());
    }

    // ------------------------------------------------------------ Root 解释：没有 su

    private void rootExplainNoSu() {
        List<Capability> out = CapabilityProbe.explainRoot(null, null, true, true);
        Capability sh = find(out, Capability.C_SHELL);
        // 没有 su 不是「本机不支持」，是「没授权」—— 用户补授权就能用
        ok("无 su → shell 判需要授权", Capability.STATE_NEEDS_GRANT.equals(sh.state));
        ok("无 su → shell 仍算本机支持", sh.supportedOnThisDevice());
        ok("无 su → connect 不判死",
                !Capability.STATE_UNSUPPORTED.equals(find(out, Capability.C_WIFI_CONNECT).state));
        // 无 su 时绝不能有任何特权能力被判成「可用」
        ok("无 su → 日志能力不可用", !find(out, Capability.C_WPA_LOG).usable());
        ok("无 su → WPS PIN 不可用", !find(out, Capability.C_WPS_PIN).usable());
        ok("无 su → 已保存网络不可读", !find(out, Capability.C_SAVED_CFG).usable());

        // su 执行了但没拿到 root：同样按「没授权」
        List<Capability> denied = CapabilityProbe.explainRoot("uid=10123(u0_a123)", null, true, true);
        ok("su 未提到 root → 需要授权",
                Capability.STATE_NEEDS_GRANT.equals(find(denied, Capability.C_SHELL).state));

        // 扫描权限缺失：需要授权，不是不支持
        List<Capability> noPerm = CapabilityProbe.explainRoot("uid=0(root)", null, true, false);
        Capability sc = find(noPerm, Capability.C_SCAN);
        ok("扫描缺权限 → 需要授权", Capability.STATE_NEEDS_GRANT.equals(sc.state));
        ok("扫描缺权限 → 说明补哪个权限", sc.alternativeZh.contains("附近的设备"));

        // 没有 Wi-Fi 服务：这才是不支持
        List<Capability> noWifi = CapabilityProbe.explainRoot("uid=0(root)", null, false, true);
        ok("无 Wi-Fi 服务 → 扫描不支持",
                Capability.STATE_UNSUPPORTED.equals(find(noWifi, Capability.C_SCAN).state));
    }

    // ------------------------------------------------------------ 无 Root 解释

    private void rootlessExplain() {
        CapabilityProbe.Facts f = new CapabilityProbe.Facts();
        f.suId = null;                 // 没 root
        f.wifiManager = true;
        f.scanPermission = true;
        f.wpaLogNonEmpty = false;      // 读不到 supplicant 日志
        List<Capability> out = CapabilityProbe.explainRootless(f);

        // 无 Root 模式的核心结论：逐条关联做不到
        Capability c = find(out, Capability.C_WIFI_CONNECT);
        ok("无 Root → 关联判不支持", Capability.STATE_UNSUPPORTED.equals(c.state));
        ok("无 Root → 关联不产证据级", !c.evidenceGrade());
        ok("无 Root → 关联必须给替代路径",
                c.alternativeZh != null && c.alternativeZh.contains("离线"));
        ok("无 Root → 关联原因说明弹框",
                c.reasonZh.contains("确认框") || c.reasonZh.contains("Specifier"));

        ok("无 Root → 净检不支持",
                Capability.STATE_UNSUPPORTED.equals(find(out, Capability.C_WIFI_FORGET).state));
        ok("无 Root → 日志不支持",
                Capability.STATE_UNSUPPORTED.equals(find(out, Capability.C_WPA_LOG).state));
        ok("无 Root → WPS PIN 不支持",
                Capability.STATE_UNSUPPORTED.equals(find(out, Capability.C_WPS_PIN).state));
        ok("无 Root → WPS 能力识别降级",
                Capability.STATE_DEGRADED.equals(find(out, Capability.C_WPS_CAP).state));
        ok("无 Root → 扫描可用", find(out, Capability.C_SCAN).evidenceGrade());
        ok("无 Root → 读当前连接可用", find(out, Capability.C_WIFI_READ).evidenceGrade());

        // 任何「无 Root 下可用于证据级」的能力里，都不许出现特权项
        Capability.Set0 set = new Capability.Set0("rootless", 0L, out);
        List<String> ev = set.evidenceGradeIds();
        ok("无 Root 证据级不含关联", !ev.contains(Capability.C_WIFI_CONNECT));
        ok("无 Root 证据级不含净检", !ev.contains(Capability.C_WIFI_FORGET));
        ok("无 Root 证据级不含日志", !ev.contains(Capability.C_WPA_LOG));
        ok("无 Root 证据级不含 WPS", !ev.contains(Capability.C_WPS_PIN));

        // su 其实可用但用户选了无 Root：说明白，别偷偷升级
        CapabilityProbe.Facts f2 = new CapabilityProbe.Facts();
        f2.suId = "uid=0(root)";
        f2.wifiManager = true;
        f2.scanPermission = true;
        Capability sh = find(CapabilityProbe.explainRootless(f2), Capability.C_SHELL);
        ok("su 可用但选了无 Root → 明说选了无 Root",
                sh.reasonZh.contains("无 Root"));
        // 即使 su 在，仍不许把关联判成可用（模式就是模式）
        ok("su 可用也不改无 Root 的关联结论",
                Capability.STATE_UNSUPPORTED.equals(
                        find(CapabilityProbe.explainRootless(f2), Capability.C_WIFI_CONNECT).state));

        // 扫描权限缺失
        CapabilityProbe.Facts f3 = new CapabilityProbe.Facts();
        f3.wifiManager = true;
        f3.scanPermission = false;
        ok("无 Root + 缺权限 → 扫描判需要授权",
                Capability.STATE_NEEDS_GRANT.equals(
                        find(CapabilityProbe.explainRootless(f3), Capability.C_SCAN).state));

        // 有非空日志输出才允许「降级可用」
        CapabilityProbe.Facts f4 = new CapabilityProbe.Facts();
        f4.wifiManager = true;
        f4.scanPermission = true;
        f4.wpaLogNonEmpty = true;
        ok("真读到日志 → 降级可用而非不支持",
                Capability.STATE_DEGRADED.equals(
                        find(CapabilityProbe.explainRootless(f4), Capability.C_WPA_LOG).state));
    }

    // ------------------------------------------------------------ 模式解析

    private void modeParsing() {
        ok("MODE 常量不撞车",
                RunMode.ROOT != RunMode.ROOTLESS
                        && RunMode.AUTO != RunMode.ROOT
                        && RunMode.AUTO != RunMode.ROOTLESS);

        // 历史队列值：1=免 Root / 2=Shizuku 一律当 Root，绝不落到无 Root 通道
        ok("历史值 0 → Root", RunMode.parseStoredMode(0) == RunMode.ROOT);
        ok("历史值 1 → Root", RunMode.parseStoredMode(1) == RunMode.ROOT);
        ok("历史值 2 → Root", RunMode.parseStoredMode(2) == RunMode.ROOT);
        ok("新标记 → 无 Root", RunMode.parseStoredMode(RunMode.ROOTLESS_MARKER) == RunMode.ROOTLESS);
        ok("往返：Root", RunMode.parseStoredMode(RunMode.storedModeFor(RunMode.ROOT)) == RunMode.ROOT);
        ok("往返：Rootless",
                RunMode.parseStoredMode(RunMode.storedModeFor(RunMode.ROOTLESS)) == RunMode.ROOTLESS);
        // 存储标记不能与历史值撞
        ok("无 Root 标记不复用历史值 1/2",
                RunMode.ROOTLESS_MARKER != 1 && RunMode.ROOTLESS_MARKER != 2);

        ok("modeName(root)", "root".equals(RunMode.nameOf(RunMode.ROOT)));
        ok("modeName(rootless)", "rootless".equals(RunMode.nameOf(RunMode.ROOTLESS)));
        ok("modeName(auto)", "auto".equals(RunMode.nameOf(RunMode.AUTO)));

        // 降级提示：必须带原因，不能空
        RunMode m = RunMode.of(RunMode.ROOTLESS, "root", RunMode.SRC_FALLBACK,
                "已切到无 Root 模式", "switched", "su 不可用", "no su",
                java.util.Collections.<Capability>emptyList());
        ok("降级模式有提示", m.hasNotice());
        ok("降级模式说明原因", m.why(true).contains("su"));
        ok("降级模式如实报实际模式", m.isRoot() == false && "rootless".equals(m.key()));
        ok("Root 模式无提示", !RunMode.root("root", RunMode.SRC_USER,
                java.util.Collections.<Capability>emptyList()).hasNotice());
    }

    // ------------------------------------------------------------ 文案诚实性

    private void noFlattery() {
        // 能力不可用时，原因必须说清「哪一环」，不许只有一句笼统结论
        List<Capability> rl = CapabilityProbe.explainRootless(new CapabilityProbe.Facts());
        for (Capability c : rl) {
            if (!c.usable()) {
                ok("不可用项必有人话原因:" + c.id, !c.reasonZh.isEmpty());
            }
            if (Capability.STATE_UNSUPPORTED.equals(c.state)) {
                ok("不支持项必有替代路径:" + c.id, !c.alternativeZh.isEmpty());
            }
            ok("能力名非空:" + c.id, !Capability.name(c.id, true).isEmpty());
            ok("能力名英文非空:" + c.id, !Capability.name(c.id, false).isEmpty());
        }
        // 不许出现笼统措辞
        StringBuilder all = new StringBuilder();
        for (Capability c : rl) all.append(c.line(true)).append('\n');
        ok("不出现笼统「设备异常」", !all.toString().contains("设备异常"));
        ok("不出现笼统「破解失败」", !all.toString().contains("破解失败"));
    }

    private static Capability find(List<Capability> list, String id) {
        for (Capability c : list) if (id.equals(c.id)) return c;
        return Capability.unknown(id, "缺失", "missing");
    }
}
