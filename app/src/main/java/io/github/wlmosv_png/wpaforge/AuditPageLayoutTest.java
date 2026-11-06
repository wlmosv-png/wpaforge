package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * 审计页的**结构契约**（静态源码检查）+ 结果模型 v1/v2 兼容。
 *
 * <h3>为什么用静态检查</h3>
 * 「说明真的收进了『?』」「材料那一条只有一个动作」「开始与停止是两个独立按钮」
 * 这类要求没法用单元测试跑起来验证 —— 它们要么需要真机渲染，要么只是接线。
 * 把它们写成对源码的结构断言，改坏了会立刻判红，比人工看截图可靠。
 *
 * <h3>1.9.45 的变化（真机反馈驱动）</h3>
 * 页面上不再允许有整段说明：全部收进「?」；材料由两张卡合成一张，
 * 每条材料只有一个动作（点整行 = 选中并展开）。
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

    /** 取一个方法/构造块的正文（从签名行起按花括号配对，含前置 javadoc）。 */
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
                if (depth == 0) return src.substring(i, p + 1);
            }
        }
        return "";
    }

    /**
     * 只统计**双引号字符串字面量内部**的 {@code **} 次数。
     *
     * <p>逐字符状态机：代码 / 字符串 / 字符 / 行注释 / 块注释 五态。
     * 不能用「先用正则去掉注释再数」—— 代码里存在「星号 + 斜杠」组成的 MIME
     * 字面量，朴素的块注释正则会把它当注释终点，吃掉后面的真实代码。
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
        int auditStart = ma.indexOf("private void buildAuditTab()");
        int auditEnd = ma.indexOf("// ---------------- 试连页 ----------------");
        String auditSection = (auditStart > 0 && auditEnd > auditStart)
                ? ma.substring(auditStart, auditEnd) : "";

        System.out.println("== A. 骨架：四步 / 一张材料卡 / 说明入口 ==");
        for (String sig : new String[]{
                "private void buildAuditTab()",
                "private void renderAudit()",
                "private void renderAuditFlow()",
                "private void renderMaterialCard()",
                "private View materialRow(",
                "private void onMaterialRowTap(",
                "private void renderDictCard()",
                "private void renderRunCard()",
                "private void renderChannelRecords()",
                "private void renderChannelChips()",
                "private void renderRecords(",
                "private void showRecordDetail(",
                "private void startOfflineRun()",
                "private void stopOfflineRun()",
                "private void restoreDictIfPossible()",
                "private void syncWorkbenchDict()",
                "private void updateLiveProgress(",
                "private void showAuditHelp()",
                "private void openDictManager()",
                "private void selectMaterialEntry(int index)",
                "private void clearMaterialAction()",
                "private void showAllEntriesPicker()",
                "private static String shortId("}) {
            ok("存在 " + sig, ma.contains(sig));
        }
        String build = body(ma, "private void buildAuditTab()");
        ok("重建前先清空容器（幂等）", build.contains("pageAudit.removeAllViews();"));
        eq("状态条锚点是 4 个（原来 5 个）",
                countOf(ma, "private final View[] auditStepCards = new View[4];"), 1);
        ok("状态条可点跳转步骤",
                ma.contains("chip.setOnClickListener(v -> scrollToAuditStep(idx))"));
        ok("总览行来自工作台（不在界面里自己拼）",
                ma.contains("workbench.overview(lastVerdict, pending)"));

        System.out.println("== B. 说明统一收进「?」（和破解页同一个做法）==");
        ok("「?」用 DictIcons 的问号图标",
                build.contains("io.github.wlmosv_png.wpaforge.ui.DictIcons.Kind.HELP"));
        ok("「?」放在第一张卡的顶行", build.contains("help.setOnClickListener(v -> showAuditHelp());"));
        ok("「?」有内容描述（无障碍）", build.contains("setContentDescription(L.s(\"说明\", \"Help\"))"));
        String help = body(ma, "private void showAuditHelp()");
        for (String s : new String[]{"① 怎么用（四步）", "② 离线校验是什么", "③ 材料那四种状态",
                "④ 结果有五种，别混", "⑤ 字典指纹", "⑥ 未命中不代表密码绝对安全", "⑦ 边界"}) {
            ok("说明分节含「" + s + "」", help.contains(s));
        }
        for (String s : new String[]{"不需要连接目标 Wi-Fi", "不会向目标网络发起连接请求",
                "材料和候选口令在本地处理", "找到匹配才表示当前字典里有对应口令",
                "文件本身是坏的", "字典确实跑满了", "跑了一半被你停掉",
                "不代表密码绝对安全"}) {
            ok("说明正文含「" + s + "」", help.contains(s));
        }
        ok("说明里直接指出结果卡的第一行是什么",
                help.contains("结果卡第一条就是"));
        ok("说明末尾给两个跳转（去导入 / 去选字典）",
                help.contains("去导入材料") && help.contains("去选字典"));

        System.out.println("== C. 页面上不许再有整段说明 ==");
        eq("审计页区间内不再调用 hintBox", countOf(auditSection, "hintBox("), 0);
        ok("不再有「离线校验是什么？」折叠块", !ma.contains("auditIntroToggle")
                && !ma.contains("offIntroOpen"));
        for (String gone : new String[]{"private void renderAuditIntro()",
                "private void renderAuditMaterial()", "private void renderAuditDiag()",
                "private void renderAuditDict()", "private void renderAuditRun()",
                "private View hintBox(String text)"}) {
            ok("旧方法已删除: " + gone, !ma.contains(gone));
        }

        System.out.println("== D. ① 材料：摘要 + 每行一个动作 ==");
        String mat = body(ma, "private void renderMaterialCard()");
        for (String k : new String[]{"workbench.materialName()", "workbench.materialRecords()",
                "workbench.materialPmkid()", "workbench.materialEapol()",
                "workbench.materialBytes()", "workbench.materialSha256()",
                "workbench.materialAt()", "MAX_DIAG_CARDS"}) {
            ok("材料摘要展示 " + k, mat.contains(k));
        }
        ok("导入按钮文案随状态切换（导入 / 更换）",
                mat.contains("L.s(\"更换材料\", \"Change material\")")
                        && mat.contains("L.s(\"导入材料（.hc22000 / .22000）\""));
        ok("清除材料按钮只在有材料时出现",
                mat.contains("auditClearMatBtn.setVisibility(imported || failed ? View.VISIBLE")
                        || mat.contains("auditClearMatBtn.setVisibility(imported ? View.VISIBLE"));
        ok("失败时给独立块而不是混进摘要", mat.contains("L.s(\"读取失败\", \"Read failed\")"));
        ok("超上限时明确写出来（不静默截断）", mat.contains("这里只画前"));

        String row = body(ma, "private View materialRow(");
        ok("一条材料只用一行标题 + 一行副标题",
                row.contains("e.isPmkid() ? \"PMKID\" : \"EAPOL\"") && row.contains("hexMac(e.apMac)"));
        ok("状态直接取诊断器的标签", row.contains("rep.statusLabel()"));
        ok("不可执行时副标题就地写第一条问题", row.contains("firstIssue(rep)"));
        ok("展开后给全部字段", row.contains("rep.facts"));
        ok("展开后写明为什么不能跑", row.contains("rep.whyNot()"));
        ok("选中态用琥珀竖条而不是整行大按钮", row.contains("selected ? ACCENT : 0x00000000"));
        ok("arrow 表示可展开", row.contains("\"\\u2303\"") || row.contains("\"\\u2304\"")
                || row.contains("2303"));
        String tap = body(ma, "private void onMaterialRowTap(");
        ok("点整行 = 没选中就选中并展开", tap.contains("diagOpen.add(key);")
                && tap.contains("selectMaterialEntry(idx);"));
        ok("已选中时再点 = 展开/收起", tap.contains("diagOpen.remove(key);"));
        // 只数「真正的按钮文案」—— 注释里说明历史时会提到这两个旧按钮
        eq("材料行不再有「选这条做离线校验」这条整行按钮",
                countOf(auditSection, "L.s(\"选这条做离线校验\""), 0);
        eq("材料行也不再单独摆一条「展开详情」按钮",
                countOf(body(ma, "private View materialRow("), "展开详情"), 0);

        System.out.println("== E. ② 字典：名称 · 条数 · 指纹 + 三行操作 ==");
        String dict = body(ma, "private void renderDictCard()");
        for (String k : new String[]{"s.name", "s.count", "s.fingerprint()", "s.fileId()",
                "s.onDisk()", "auditSourceLabel(s.source)", "workbench.dictionary()"}) {
            ok("字典卡展示 " + k, dict.contains(k));
        }
        ok("按目标派生走审计分支", build.contains("pickTargetForDerive(true)"));
        ok("字典管理是第一行、直达、且用词与提示语一致",
                build.contains("L.s(\"字典管理（新建 / 导入 / 清理）\"")
                        && build.contains("dManage.setOnClickListener(v -> openDictManager());"));
        ok("字典管理入口直接进 DictActivity",
                body(ma, "private void openDictManager()").contains("DictActivity.class"));
        ok("非 UTF-8 的 SSID 不提供派生入口（只接受 ssidExact）",
                body(ma, "private String materialSsid()").contains("lastEntry.ssidExact"));
        ok("非 UTF-8 时给明确解释，而不是静默产出乱码候选",
                body(ma, "private void confirmDeriveFromMaterial()")
                        .contains("这条 SSID 不能用于派生")
                        && body(ma, "private void confirmDeriveFromMaterial()")
                                .contains("!e.ssidExact"));
        ok("空字典时给直达按钮（不是只写一句话）",
                body(ma, "private void renderDictCard()")
                        .contains("L.s(\"去字典管理新建 / 导入\", \"Open dictionary management\")"));
        ok("空字典时把「更换字典」藏起来（点进去只能看到空列表）",
                body(ma, "private void renderDictCard()")
                        .contains("auditChangeDictBtn.setVisibility(s.empty() ? View.GONE"));
        ok("字典选择器空状态改成可点入口（不再只弹一句 Toast）",
                body(ma, "private void showDictPicker()")
                        .contains("L.s(\"打开字典管理\", \"Open dictionary management\")"));

        System.out.println("== F. ③ 执行：摘要 + 进度条 + 开始/停止 ==");
        String run = body(ma, "private void renderRunCard()");
        ok("摘要用三行（材料 / 字典 / 完整性）",
                run.contains("L.s(\"材料  \", \"Material  \")")
                        && run.contains("L.s(\"字典  \", \"Dictionary  \")")
                        && run.contains("L.s(\"完整性  \", \"Integrity  \")"));
        ok("进度条只在跑的时候出现",
                run.contains("auditProgress.setVisibility(offlineBusy ? View.VISIBLE : View.GONE)"));
        ok("用的是现成的 ThinProgress",
                build.contains("new io.github.wlmosv_png.wpaforge.ui.ThinProgress(this)"));
        String live = body(ma, "private void updateLiveProgress(long tested, long total, "
                + "long elapsedMs)");
        ok("进度给条数", live.contains("auditProgress.setText("));
        ok("进度给百分比与耗时/速率", live.contains("setSubText(") && live.contains("条/秒"));
        ok("进度条刷新后请求重新测量", live.contains("requestLayout()"));
        ok("开始按钮独立", build.contains("auditStartBtn = AmberTheme.action("));
        ok("停止按钮独立", build.contains("auditStopBtn = AmberTheme.action("));
        ok("条件不满足时说清原因",
                body(ma, "private void startOfflineRun()").contains("workbench.blocker()"));
        String stop = body(ma, "private void stopOfflineRun()");
        ok("停止调真正的 engine.cancel()", stop.contains("eng.cancel();"));
        ok("停止不假报成功（写明等待退出）", stop.contains("等待引擎退出"));

        System.out.println("== G. ④ 结果：先自证来源，再谈结论 ==");
        String vo = body(ma, "private io.github.wlmosv_png.wpaforge.audit.OfflineVerdict verdictOf(");
        for (String k : new String[]{"s.source", "s.fingerprint()", "s.filePath", "s.fileId()"}) {
            ok("结果卡记录 " + k, vo.contains(k));
        }
        String rv = body(ma, "private void renderOfflineVerdict()");
        ok("结果卡顶部先给一致性徽标", rv.contains("matchBadge(match, v)"));
        ok("不一致时给「再跑一次」入口",
                rv.contains("用当前选择再跑一次") && rv.contains("startOfflineRun()"));
        ok("材料那一栏写明是「结果用的材料」",
                rv.contains("L.s(\"结果用的材料\", \"Material used\")"));
        String badge = body(ma, "private View matchBadge(");
        for (String k : new String[]{"MATERIAL_CHANGED", "DICT_CHANGED", "BOTH_CHANGED", "SAME"}) {
            ok("徽标覆盖 " + k, badge.contains(k));
        }
        ok("徽标把两边都写出来", badge.contains("现在选中的是") && badge.contains("结果来自"));
        ok("判定走纯逻辑工作台",
                ma.contains("workbench.matchOf(v,")
                        && ma.contains("private io.github.wlmosv_png.wpaforge.audit"
                                + ".AuditWorkflow.Match resultMatch("));
        ok("未命中给出明确解释", rv.contains("不代表目标密码不存在于其他字典"));
        ok("取消给出明确解释", rv.contains("既不是命中，也不是未命中"));
        ok("命中默认掩码 + 可显示/复制",
                rv.contains("hitRow(v)") && ma.contains("private View hitRow("));

        System.out.println("== H. 运行记录：四频道 + 结构化条目 ==");
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

        System.out.println("== I. 字典写入只有一条路 ==");
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

        System.out.println("== J. 恢复状态仍折叠、能力未删 ==");
        ok("恢复卡默认折叠", ma.contains("restoreList.setVisibility(View.GONE);"));
        ok("展开/收起按钮仍在", ma.contains("展开详情   \\u2304"));
        ok("仍调 refreshRestoreCard", ma.contains("refreshRestoreCard();"));

        System.out.println("== K. 结果模型 v1 / v2 兼容 ==");
        OfflineVerdict back1 = OfflineVerdict.decode(v1().encode());
        ok("v1 旧数据仍能读（升级不丢上一次结果）", back1.hasResult());
        eq("v1 状态", back1.state, OfflineVerdict.State.MISS);
        eq("v1 没有字典指纹", back1.dictFingerprint, "");
        ok("v1 覆盖写回后变成 v2", OfflineVerdict.decode(v1().encode()).encode().startsWith("v2|"));
        OfflineVerdict src = v2();
        OfflineVerdict back2 = OfflineVerdict.decode(src.encode());
        eq("v2 状态", back2.state, OfflineVerdict.State.HIT);
        eq("v2 字典来源", back2.dictSource, "file");
        eq("v2 字典指纹", back2.dictFingerprint, "AB12CD34EF56");
        eq("v2 文件指纹", back2.dictFileId, "FE98DC76BA54");
        eq("v2 命中口令往返", back2.hitPassword, "hashcat!");
        ok("v2 自洽", back2.consistent());
        ok("乱码仍退回 none", !OfflineVerdict.decode("v2|garbage").hasResult());

        System.out.println("== L. 执行控制与状态：本轮审计修掉的那几条要有结构约束 ==");
        // ① 结果卡的材料身份不能再读可变字段
        eq("MainActivity 里不再有 offlineFileName（结果卡材料名由开跑那一刻固定）",
                countOf(ma, "offlineFileName"), 0);
        ok("verdictOf 带 materialName 参数",
                ma.contains("String materialName,") && ma.contains("frozenMaterialName, snap,"));
        // ② applyDict 必须"两边一起成功或一起不动"
        String ap = body(ma, "private void applyDict(java.util.List<Candidate> list, "
                + "String name, String path, String source)");
        ok("先让工作台接受、被拒就整体不动",
                ap.contains("boolean accepted;") && ap.contains("if (!accepted) {")
                        && ap.contains("return;"));
        ok("setDictionary 的返回值被使用（不再忽略）",
                ap.contains("accepted = workbench.setDictionary("));
        ok("每次成功替换都递增代际 dictEpoch++", ap.contains("dictEpoch++;"));
        // ③ 异步恢复要校验代际，别覆盖用户刚选的
        String rs = body(ma, "private void restoreDictIfPossible()");
        ok("恢复线程记录代际", rs.contains("final long epoch = dictEpoch;"));
        ok("回填前校验代际与占用", rs.contains("dictEpoch != epoch"));
        // ④ 开始按钮不隐藏（双击第二下会打到"停止"）
        String rb = body(ma, "private void refreshOfflineButtons()");
        ok("开始按钮始终占位（View.VISIBLE）",
                rb.contains("auditStartBtn.setVisibility(View.VISIBLE)"));
        ok("不再把开始按钮设成 GONE",
                !rb.contains("auditStartBtn.setVisibility(offlineBusy ? View.GONE"));
        // ⑤ 进程级运行守卫 + 落盘痕迹
        String run2 = body(ma, "private void runOfflineOne(final io.github.wlmosv_png.wpaforge"
                + ".audit.Hc22000.Entry e)");
        ok("开跑前拿进程级独占", run2.contains("OfflineRunGuard.tryAcquire(runToken)"));
        ok("拿不到就拒绝开跑并说明", run2.contains("进程内已有一次比对在进行"));
        ok("结束时释放独占", run2.contains("OfflineRunGuard.release(runToken)"));
        ok("开跑落盘痕迹", run2.contains("OfflineRunStore.saveInflight("));
        ok("结束清掉痕迹", run2.contains("OfflineRunStore.clearInflight(this)"));
        ok("有痕迹渲染入口", ma.contains("private void renderInflight()"));
        ok("总览/执行卡体现进程占用",
                ma.contains("OfflineRunGuard.held()"));
        // ⑥ 取消看门狗
        String stop2 = body(ma, "private void stopOfflineRun()");
        ok("停止时记录取消时刻", stop2.contains("OfflineRunGuard.markCancel("));
        ok("停止时启动看门狗", stop2.contains("startStallWatchdog()"));
        ok("看门狗存在且会强制解锁",
                ma.contains("private final Runnable stallTick")
                        && ma.contains("OfflineRunGuard.forceRelease()"));
        ok("强制解锁写成「已取消」而不是命中/未命中",
                ma.contains("引擎在 %d 秒内没有退出")
                        && ma.contains("OfflineVerdict.State.CANCELLED"));
        // ⑦ 材料自动选中要扫全部
        ok("自动选中扫描全部条目（不再只看前 40 条）",
                ma.contains("int scan = entries.size();"));
        // ⑧ 缺 SSID 的处理与上报
        ok("导入判定有 INCOMPLETE 分支", ma.contains("case INCOMPLETE:"));
        ok("OK 时把缺 SSID 被跳过的条数报出来", ma.contains("r.noSsid > 0"));
        // ⑨ 结果记录损坏与「没跑过」分开
        ok("结果读回失败要能识别",
                ma.contains("OfflineRunStore.lastDecodeFailed(this)")
                        && ma.contains("verdictLoadFailed"));

        System.out.println("== M. 文案不许出现 Markdown 星号 / addView 不许传 null ==");
        eq("审计页字符串字面量里没有 Markdown 星号",
                countStarsInsideStrings(auditSection), 0);
        int badAdd = 0;
        int from = 0;
        while ((from = ma.indexOf("addView(", from)) >= 0) {
            int end = ma.indexOf(';', from);
            if (end > 0) {
                String call = ma.substring(from, end);
                if (call.contains(", null)") || call.contains("? null :")) {
                    badAdd++;
                    System.out.println("       -> " + call.replace('\n', ' '));
                }
            }
            from += 8;
        }
        eq("MainActivity 里没有「addView(x, null)」这种写法", badAdd, 0);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
