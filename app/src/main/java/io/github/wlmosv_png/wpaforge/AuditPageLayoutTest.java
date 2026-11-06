package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * 审计页 5 步工作台的**结构契约**（静态源码检查）+ 结果模型 v1/v2 兼容。
 *
 * <h3>为什么用静态检查</h3>
 * 「说明文案真的进了 UI」「开始与停止是两个独立动作」「清空只清一个频道」
 * 这类要求没法用单元测试跑起来验证 —— 它们要么需要真机渲染，要么只是接线。
 * 把它们写成对源码的结构断言，改坏了会立刻判红，比人工看截图可靠。
 */
public class AuditPageLayoutTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static String read(String rel) {
        File f = new File(TestPaths.javaSrcRoot(), rel);
        if (!f.isFile()) return null;
        Reader r = null;
        try {
            r = new InputStreamReader(new FileInputStream(f), "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (r != null) try {
                r.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int countOf(String s, String sub) {
        if (s == null || sub == null || sub.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /**
     * 只统计**双引号字符串字面量内部**的 {@code **} 次数。
     *
     * <p>逐字符状态机：代码 / 字符串 / 字符 / 行注释 / 块注释 五态。
     * 不能用「先用正则去掉注释再数」—— 代码里存在「星号 + 斜杠」组成的 MIME
     * 字面量，朴素的块注释正则会把它当注释终点，吃掉后面的真实代码，
     * 于是得出一个**假的"干净"**结论。
     */
    private static int countStarsInsideStrings(String src) {
        if (src == null) return 0;
        int n = 0;
        int st = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char d = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            switch (st) {
                case 0:
                    if (c == '"') st = 1;
                    else if (c == '\'') st = 2;
                    else if (c == '/' && d == '/') { st = 3; i++; }
                    else if (c == '/' && d == '*') { st = 4; i++; }
                    break;
                case 1:
                    if (c == '\\') i++;
                    else if (c == '"') st = 0;
                    else if (c == '*' && d == '*') { n++; i++; }
                    break;
                case 2:
                    if (c == '\\') i++;
                    else if (c == '\'') st = 0;
                    break;
                case 3:
                    if (c == '\n') st = 0;
                    break;
                default:
                    if (c == '*' && d == '/') { st = 0; i++; }
                    break;
            }
        }
        return n;
    }

    /** 取一个方法/构造块的正文（从签名行起按花括号配对）。 */
    private static String body(String src, String signature) {
        if (src == null) return "";
        int i = src.indexOf(signature);
        if (i < 0) return "";
        int open = src.indexOf('{', i);
        if (open < 0) return "";
        int depth = 0;
        for (int p = open; p < src.length(); p++) {
            char c = src.charAt(p);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, p + 1);
            }
        }
        return "";
    }

    private static OfflineVerdict v1() {
        return new OfflineVerdict(OfflineVerdict.State.MISS, "a.hc22000", "PMKID",
                "s", "AA:BB:CC:DD:EE:FF", "d", 100, 100, 50, 1793900000000L, "", "");
    }

    private static OfflineVerdict v2() {
        return new OfflineVerdict(OfflineVerdict.State.HIT, "b.hc22000", "EAPOL",
                "s2", "11:22:33:44:55:66", "07 通用口令榜", 3336, 2, 15, 1793900000000L,
                "hashcat!", "", "file", "AB12CD34EF56", "/data/x/d.txt", "FE98DC76BA54");
    }

    public static void main(String[] args) {
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");
        if (ma == null) {
            System.out.println("  N/RUN MainActivity.java 不可读");
            System.out.println("=== 审计页结构测试: NOT RUN ===");
            return;
        }

        System.out.println("== A. 五步工作台的骨架 ==");
        for (String sig : new String[]{
                "private void buildAuditTab()",
                "private void renderAudit()",
                "private void renderAuditFlow()",
                "private void renderAuditMaterial()",
                "private void renderAuditDiag()",
                "private void renderAuditDict()",
                "private void renderAuditRun()",
                "private void renderAuditIntro()",
                "private void renderChannelRecords()",
                "private void renderChannelChips()",
                "private void renderRecords(",
                "private void showRecordDetail(",
                "private void startOfflineRun()",
                "private void stopOfflineRun()",
                "private void restoreDictIfPossible()",
                "private void syncWorkbenchDict()",
                "private void updateLiveProgress("}) {
            ok("存在 " + sig, ma.contains(sig));
        }
        String build = body(ma, "private void buildAuditTab()");
        ok("审计页重建前先清空容器（幂等）", build.contains("pageAudit.removeAllViews();"));
        ok("审计页只建五个步骤卡 + 锚点数组",
                ma.contains("private final View[] auditStepCards = new View[5];"));
        ok("状态条可点跳转步骤",
                ma.contains("chip.setOnClickListener(v -> scrollToAuditStep(idx))"));
        ok("总览行来自工作台（不在界面里自己拼）",
                ma.contains("workbench.overview(lastVerdict, pending)"));

        System.out.println("== B. 五步标题与「离线校验是什么」文案真的进了 UI ==");
        String[] titles = {
                "① 导入 Wi-Fi 握手材料", "② 检查材料是否可以使用",
                "③ 选择候选口令字典", "④ 开始离线校验", "⑤ 离线校验结果"
        };
        for (String t : titles) ok("步骤标题: " + t, ma.contains(t));
        for (String bullet : new String[]{
                "将从你有权测试的 Wi-Fi 网络获取的握手材料",
                "与本机字典中的候选口令逐条比对",
                "不需要连接目标 Wi-Fi",
                "不会向目标网络发起连接请求",
                "材料和候选口令在本地进行处理",
                "需要准备有效的握手材料",
                "找到匹配结果才表示当前字典中存在对应口令",
                "未命中不代表密码绝对安全"}) {
            ok("说明文案含: " + bullet, ma.contains(bullet));
        }
        ok("状态条用 ①..⑤ 圆圈序号", ma.contains("\\u2460") && ma.contains("\\u2464"));
        ok("说明块可折叠且状态持久化",
                ma.contains("Prefs.setAuditIntroOpen(this, offIntroOpen)"));

        System.out.println("== C. 步骤 ①：导入要展示的六项信息 ==");
        String mat = body(ma, "private void renderAuditMaterial()");
        for (String k : new String[]{"materialName()", "materialBytes()", "materialSha256()",
                "materialRecords()", "materialPmkid()", "materialEapol()"}) {
            ok("材料卡展示 " + k, mat.contains(k));
        }
        ok("材料信息来自工作台（不是界面自己记）", mat.contains("workbench."));
        ok("导入走系统文件选择器", body(ma, "private void showOfflinePicker()")
                .contains("pickOfflineFileViaSaf()"));
        ok("备用扫描通道单独一行（不再挡在主路径前）",
                ma.contains("private void showDownloadScan()"));

        System.out.println("== D. 步骤 ②：四条状态 + 展开详情 ==");
        String diag = body(ma, "private void renderAuditDiag()");
        ok("用诊断器的状态标签", diag.contains("rep.statusLabel()"));
        ok("把「缺什么」写出来", diag.contains("rep.whyNot()"));
        ok("展开详情显示字段", diag.contains("rep.facts"));
        ok("支持每条独立展开", diag.contains("diagOpen"));
        ok("可切换本次校验条目", diag.contains("selectMaterialEntry("));
        ok("选中状态交给工作台", body(ma, "private void selectMaterialEntry(int index)")
                .contains("workbench.selectEntry("));
        ok("步骤②渲染条数有上限（上万条材料不会拖死界面）",
                diag.contains("MAX_DIAG_CARDS") && ma.contains("MAX_DIAG_CARDS = 40"));
        ok("选条目对话框也有上限", ma.contains("MAX_PICK_ITEMS = 200")
                && ma.contains("private void showAllEntriesPicker()"));
        ok("超出上限时明确写出来（不静默截断）", diag.contains("这里只画前"));

        System.out.println("== E. 步骤 ③：字典快照（名字/来源/条数/两个指纹）==");
        String dict = body(ma, "private void renderAuditDict()");
        for (String k : new String[]{"s.name", "auditSourceLabel(s.source)", "s.count",
                "s.fingerprint()", "s.fileId()", "s.onDisk()"}) {
            ok("字典卡展示 " + k, dict.contains(k));
        }
        ok("字典卡只读工作台快照（不再自己拼 currentDict*）",
                dict.contains("workbench.dictionary()"));
        ok("派生完成后按材料 SSID 提供入口（需确认）",
                ma.contains("confirmDeriveFromMaterial()") && ma.contains("private void deriveForSsid("));
        ok("按材料 SSID 派生会落盘（saveDictToFile）",
                body(ma, "private void deriveForSsid(final String ssid)")
                        .contains("saveDictToFile(fresh, nm)"));
        ok("按材料 SSID 派生不按别的网络的 triedSet 过滤（避免静默丢料）",
                ma.contains("**不**按本目标的 triedSet 过滤"));

        System.out.println("== F. 字典写入只有一条路 ==");
        String apply = body(ma, "private void applyDict(java.util.List<Candidate> list, "
                + "String name, String path, String source)");
        ok("applyDict 同步工作台", apply.contains("workbench.setDictionary("));
        ok("applyDict 持久化快照", apply.contains("DictSnapshotStore.save("));
        ok("applyDict 空字典走显式清空",
                apply.contains("workbench.clearDictionary();")
                        && apply.contains("DictSnapshotStore.clear("));
        eq("currentDict 的赋值点收敛到 3 处（applyDict + 两处权威重建）",
                countOf(ma, "currentDict = "), 3);
        eq("权威重建的两处都同步工作台", countOf(ma, "syncWorkbenchDict();"), 2);
        ok("快照持久化入口存在", read("io/github/wlmosv_png/wpaforge/DictSnapshotStore.java") != null);

        System.out.println("== G. 步骤 ④：冻结 + 开始/停止是两个动作 ==");
        String run = body(ma, "private void runOfflineOne(final io.github.wlmosv_png.wpaforge"
                + ".audit.Hc22000.Entry e)");
        ok("先冻结再跑", run.contains("workbench.beginRun()"));
        ok("跑的是冻结包", run.contains("frozen.run(engine, e, cb)"));
        ok("不再直接抓 currentDict 交给引擎",
                !run.contains("engine.runPmkid(") && !run.contains("engine.runHandshake("));
        ok("冻结留痕（快照名字进日志）", run.contains("snap.name"));
        ok("开始按钮独立", ma.contains("auditStartBtn = AmberTheme.action("));
        ok("停止按钮独立", ma.contains("auditStopBtn = AmberTheme.action("));
        ok("有实时进度行", ma.contains("进行中: %d / %d"));
        ok("进度按百分比写记录（不刷爆记录）", ma.contains("if (tt % (step * 10L) == 0)"));
        ok("条件不满足时说清原因", body(ma, "private void startOfflineRun()")
                .contains("workbench.blocker()"));
        String stop = body(ma, "private void stopOfflineRun()");
        ok("停止调真正的 engine.cancel()", stop.contains("eng.cancel();"));
        ok("停止不假报成功（写明等待退出）", stop.contains("等待引擎退出"));

        System.out.println("== H. 步骤 ⑤：结果卡（可追溯 + 不泄露口令）==");
        String vo = body(ma, "private io.github.wlmosv_png.wpaforge.audit.OfflineVerdict verdictOf(");
        for (String k : new String[]{"s.source", "s.fingerprint()", "s.filePath", "s.fileId()"}) {
            ok("结果卡记录 " + k, vo.contains(k));
        }
        String rv = body(ma, "private void renderOfflineVerdict()");
        for (String k : new String[]{"字典指纹", "文件指纹", "字典来源", "结果标识"}) {
            ok("结果卡展示「" + k + "」", rv.contains(k));
        }
        ok("未命中给出明确解释", rv.contains("不代表目标密码不存在于其他字典"));
        ok("取消给出明确解释", rv.contains("既不是命中，也不是未命中"));
        ok("命中默认掩码 + 可显示/复制",
                rv.contains("hitRow(v)") && ma.contains("private View hitRow("));
        eq("命中口令只交给结果卡一次（不进日志）", countOf(run, "r.hitPassword"), 1);
        ok("命中日志写明不写明文", run.contains("口令明文不写入任何日志"));

        System.out.println("== I. 运行记录：四频道 + 结构化条目 ==");
        ok("appendLog 落到「其他」频道（不再污染离线校验）",
                ma.contains("runLog.other.add(System.currentTimeMillis(),"));
        ok("auditLine 落离线频道",
                ma.contains("runLog.offline.add(System.currentTimeMillis(), kind, subject, "
                        + "text, detail);"));
        ok("dictLine 落字典频道",
                ma.contains("runLog.dict.add(System.currentTimeMillis(), kind, subject, "
                        + "text, detail);"));
        ok("strengthLine 落强度频道",
                ma.contains("runLog.strength.add(System.currentTimeMillis(), kind, subject, "
                        + "text, detail);"));
        String rec = body(ma, "private void renderRecords(io.github.wlmosv_png.wpaforge.audit"
                + ".RunLog.Channel ch,");
        ok("记录行含时间", rec.contains("ln.clock()"));
        ok("记录行含状态", rec.contains("ln.status()"));
        ok("记录行含操作对象", rec.contains("ln.subject"));
        ok("记录行含简短结果", rec.contains("ln.text"));
        ok("可点开详情", rec.contains("showRecordDetail(line)"));
        ok("只有本来在底部才自动滚底", rec.contains("isNearBottom(scroller)"));
        ok("清空只清当前频道", ma.contains("runLog.clearChannel(logChannel)"));
        eq("审计页不整锅清日志（不调 clearAll）", countOf(ma, "runLog.clearAll()"), 0);

        System.out.println("== J. 离线校验与口令强度彻底分开 ==");
        ok("强度评估有独立记录区", ma.contains("strengthRecBox"));
        ok("强度区写明与离线校验无关", ma.contains("不是对握手材料执行离线校验"));
        ok("强度记录用独立频道渲染",
                body(ma, "private void renderStrengthLog()").contains("runLog.strength"));

        System.out.println("== K. 恢复状态仍折叠、不删能力 ==");
        ok("恢复卡默认折叠", ma.contains("restoreList.setVisibility(View.GONE);"));
        ok("展开/收起按钮仍在", ma.contains("展开详情   \\u2304"));
        ok("恢复能力未删（仍调 refreshRestoreCard）", ma.contains("refreshRestoreCard();"));

        System.out.println("== L. 结果模型 v1 / v2 兼容 ==");
        OfflineVerdict back1 = OfflineVerdict.decode(v1().encode());
        ok("v1 旧数据仍能读（升级不丢上一次结果）", back1.hasResult());
        eq("v1 状态", back1.state, OfflineVerdict.State.MISS);
        eq("v1 字典条数", back1.dictSize, 100L);
        eq("v1 没有字典指纹（旧数据没有这个字段）", back1.dictFingerprint, "");
        ok("v1 覆盖写回后变成 v2（带指纹）",
                OfflineVerdict.decode(v1().encode()).encode().startsWith("v2|"));

        OfflineVerdict src = v2();
        OfflineVerdict back2 = OfflineVerdict.decode(src.encode());
        eq("v2 状态", back2.state, OfflineVerdict.State.HIT);
        eq("v2 字典名", back2.dictName, src.dictName);
        eq("v2 字典来源", back2.dictSource, "file");
        eq("v2 字典指纹", back2.dictFingerprint, "AB12CD34EF56");
        eq("v2 字典路径", back2.dictPath, "/data/x/d.txt");
        eq("v2 文件指纹", back2.dictFileId, "FE98DC76BA54");
        eq("v2 命中口令往返（只在结果卡里）", back2.hitPassword, "hashcat!");
        ok("v2 自洽", back2.consistent());
        ok("none() 的字典指纹为空", OfflineVerdict.none().dictFingerprint.isEmpty());
        ok("乱码仍退回 none（不抛）", !OfflineVerdict.decode("v2|garbage").hasResult());
        ok("不自洽的 v2 退回 none",
                !OfflineVerdict.decode(new OfflineVerdict(
                        OfflineVerdict.State.MISS, "f", "PMKID", "s", "a", "d",
                        100, 3, 10, 1, "", "",
                        "file", "fp", "/p", "fi").encode()).hasResult());

        System.out.println("== M. 不许给 addView 传 null 作为 LayoutParams ==");
        // 真机事故（2026-11-06）：addView(v, null) 在 Android 15 上会走
        // checkLayoutParams(null) -> generateLayoutParams(null) -> new LayoutParams(null)
        // 直接 NPE —— 点审计页即闪退。这里把它变成门禁里可判红的结构约束。
        int badAdd = 0;
        int from = 0;
        while ((from = ma.indexOf("addView(", from)) >= 0) {
            int end = ma.indexOf(';', from);
            if (end > 0) {
                String call = ma.substring(from, end);
                if (call.contains(", null)") || call.contains("? null :")
                        || call.contains(", null ,")) {
                    badAdd++;
                    System.out.println("       -> " + call.replace('\n', ' '));
                }
            }
            from += 8;
        }
        eq("MainActivity 里没有「addView(x, null)」这种写法", badAdd, 0);
        ok("总览第一行用 topGap(0) 而不是 null",
                ma.contains("topGap(i == 0 ? 0 : 5)"));

        System.out.println("== N. 审计页的文案不许出现 Markdown 星号（TextView 会原样显示）==");
        // 真机实测：`**你输入的口令**` 在卡片上就是两个星号 + 文字 + 两个星号。
        int auditStart = ma.indexOf("private void buildAuditTab()");
        int auditEnd = ma.indexOf("// ---------------- 试连页 ----------------");
        ok("能切出审计页源码区间", auditStart > 0 && auditEnd > auditStart);
        String auditSection = (auditStart > 0 && auditEnd > auditStart)
                ? ma.substring(auditStart, auditEnd) : "";
        // 注释里当然有 **（javadoc 强调），只看**双引号字符串字面量内部**。
        // 这里不能用「先去掉注释再数」—— 代码里有 i.setType("*/*") 这种字面量，
        // 朴素的块注释正则会误吃代码，得出假的"干净"。
        eq("审计页的字符串字面量里没有 Markdown 星号",
                countStarsInsideStrings(auditSection), 0);
        ok("强度评估的说明已去掉星号",
                ma.contains("这是对你输入的口令做强度与位次分析"));
        ok("字典来源为空时写「未记录」（不是「未选择」）",
                ma.contains("private String auditSourceLabel(String code)")
                        && ma.contains("return L.s(\"未记录\", \"not recorded\")"));
        ok("快照时间不叫「建立时间」（避免被当成文件创建时间）",
                !ma.contains("L.s(\"建立时间\", \"Created\")")
                        && ma.contains("L.s(\"快照时间\", \"Snapshot at\")"));

        System.out.println("== O. 旧结构痕迹已清除 ==");
        ok("不再有单独的总览 TextView 字段", !ma.contains("private TextView auditOverview;"));
        ok("不再有旧的按钮刷新方法", !ma.contains("private void refreshOfflineButton()"));
        ok("不再有旧的大块文本日志渲染入口",
                !ma.contains("private void renderOfflineLog() {\n        renderLog("));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
