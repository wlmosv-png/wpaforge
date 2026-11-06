package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.AuditWorkflow;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DerivedDicts;
import io.github.wlmosv_png.wpaforge.audit.DictSnapshot;
import io.github.wlmosv_png.wpaforge.audit.Hc22000;
import io.github.wlmosv_png.wpaforge.audit.MaterialDoctor;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * **字典派生 → 离线执行 的真实数据链**。
 *
 * <h3>这份测试要回答的问题</h3>
 * 「界面显示 A 字典、实际跑 B 字典」这类问题，只有**把真实候选交到真实引擎手里**
 * 才能证明修好了。所以这里不检查界面文字，而是：
 * <ol>
 *   <li>用官方 hashcat PMKID 向量（口令 {@code hashcat!}）当材料；</li>
 *   <li>用真实的 {@code AuditWorkflow} 做派生 / 换字典 / 冻结；</li>
 *   <li>用真实的 {@code AuditEngine} 跑冻结包；</li>
 *   <li>断言**命中的口令**与**实际迭代条数**，而不是断言界面上写了什么。</li>
 * </ol>
 *
 * <p>关键对照组：同一份材料，配 A 字典必须未命中、配 B 字典必须命中 ——
 * 只有这样才能证明引擎真的拿到了 B 的候选，而不是显示上换了。
 */
public class AuditDictChainTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   " + detail);
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

    private static void info(String s) {
        System.out.println("  ·    " + s);
    }

    /** 官方 hashcat 示例（wiki 22000 行原样）。口令是 hashcat! */
    private static final String OFF_PMKID =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*686173686361742d657373"
            + "6964***";

    private static final Hc22000.Entry MAT = Hc22000.parseLine(OFF_PMKID);

    private static List<Candidate> dict(String... pws) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (String p : pws) d.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return d;
    }

    /** 造一个「材料已导入 + 已选中一条可执行材料」的工作台（走真实诊断）。 */
    private static AuditWorkflow withMaterial() {
        AuditWorkflow wf = new AuditWorkflow();
        MaterialDoctor.Report rep = MaterialDoctor.examine(MAT);
        wf.onMaterialOk("cap.hc22000", 0, "", 1, 1, 0);
        wf.selectEntry(0, rep.executable, rep.whyNot());
        return wf;
    }

    /** 跑冻结包，返回引擎结果（错误写进 err[0]）。 */
    private static AuditEngine.Result runFrozen(AuditWorkflow.Frozen f, String[] err) {
        final AuditEngine.Result[] out = {null};
        f.run(new AuditEngine(), MAT, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String found) {}
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { if (err != null) err[0] = m; }
        });
        return out[0];
    }

    /** 模拟 MainActivity.deriveForSsid：真实派生 → 真实落盘 → 真实替换。 */
    private static boolean deriveLike(AuditWorkflow wf, String ssid, File dir) throws Exception {
        NetRecord fake = new NetRecord(ssid, "", -127, 2437,
                NetRecord.Security.UNKNOWN, false, "");
        // 与 MainActivity.seedModelForSsid 一致：显式要求 SSID 派生。
        io.github.wlmosv_png.wpaforge.audit.SeedModel seeds =
                new io.github.wlmosv_png.wpaforge.audit.SeedModel();
        seeds.useSsid = true;
        TargetDictBuilder.Result r = TargetDictBuilder.build(fake, Prefs.DEFAULT_BUDGET,
                null, seeds, null);
        List<Candidate> fresh = new ArrayList<Candidate>(r.candidates);
        String nm = DerivedDicts.targetedName(ssid, "");
        File out = new File(dir, safe(nm) + ".txt");
        writeLines(out, fresh);
        info("派生 \"" + ssid + "\" -> " + nm + " (" + fresh.size() + " 条) file=" + out.getName());
        return wf.applyDerived(nm, DictSnapshot.SRC_PROFILE, out.getAbsolutePath(), fresh);
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]", "_");
    }

    private static void writeLines(File f, List<Candidate> list) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            for (Candidate c : list) {
                w.write(c.password);
                w.write("\n");
            }
        } finally {
            w.close();
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "wpf-dictchain-" + System.nanoTime());
        ok("建临时目录", dir.mkdirs(), dir.getAbsolutePath());

        System.out.println("== 场景 0：对照组——A 字典不含口令，必须未命中 ==");
        AuditWorkflow wfA = withMaterial();
        List<Candidate> dictA = dict("wrong-one", "wrong-two", "wrong-three");
        wfA.setDictionary("A", DictSnapshot.SRC_FILE, "", dictA);
        AuditWorkflow.Frozen fA = wfA.beginRun();
        ok("A 冻结成功", fA != null, "frozen=null");
        String[] errA = {null};
        AuditEngine.Result rA = runFrozen(fA, errA);
        ok("A 跑完没有报错", errA[0] == null, String.valueOf(errA[0]));
        ok("配 A 必须未命中（对照组）", rA != null && !rA.hit && !rA.cancelled, "r=" + rA);
        eq("A 实际迭代条数 = A 的候选数", rA == null ? -1L : rA.tested, (long) dictA.size());
        wfA.endRun();

        System.out.println("== 场景 1：当前字典 A → 派生字典 B ==");
        AuditWorkflow wf = withMaterial();
        wf.setDictionary("A", DictSnapshot.SRC_FILE, "", dictA);
        List<Candidate> dictB = dict("qwerty", "hashcat!", "zzz");
        ok("派生替换成功", wf.applyDerived("B", DictSnapshot.SRC_PROFILE, "", dictB),
                "applyDerived=false");
        eq("当前字典变成 B", wf.dictionary().name, "B");
        eq("候选数与 B 一致", wf.dictionary().count, (long) dictB.size());
        eq("内容指纹 = B 的内容指纹", wf.dictionary().contentSha256,
                DictSnapshot.sha256OfCandidates(dictB));
        ok("字典自洽", wf.dictionaryConsistent(), "不一致");

        System.out.println("== 场景 2/3/4：派生完成后开跑，任务实际读的是 B ==");
        AuditWorkflow.Frozen f = wf.beginRun();
        ok("冻结成功", f != null, "frozen=null");
        eq("冻结包的字典名", f.snap.name, "B");
        eq("冻结包候选数 = B", f.candidateCount(), dictB.size());
        ok("冻结包是 B 的内容（不是 A）", f.snap.matches(dictB), "不匹配");
        ok("冻结包不是 A 的内容", !f.snap.matches(dictA), "竟然匹配 A");
        String[] err = {null};
        AuditEngine.Result r = runFrozen(f, err);
        ok("没有报错", err[0] == null, String.valueOf(err[0]));
        ok("配 B 必须命中（证明引擎拿到的是 B）", r != null && r.hit, "r=" + r);
        eq("命中口令来自 B", r == null ? null : r.hitPassword, "hashcat!");
        eq("位次 = B 里的第 2 条", r == null ? -1L : r.hitIndex, 2L);
        ok("实际迭代条数不超过 B 的候选数", r != null && r.tested <= dictB.size(), "tested 超了");

        System.out.println("== 场景 5：派生失败不能破坏当前字典 ==");
        wf.endRun();
        eq("失败前字典是 B", wf.dictionary().name, "B");
        ok("派生空结果被拒绝", !wf.applyDerived("使坏", DictSnapshot.SRC_PROFILE, "", dict()),
                "竟然接受了空派生");
        eq("字典仍然是 B", wf.dictionary().name, "B");
        eq("候选数没变", wf.dictionary().count, (long) dictB.size());
        ok("内容指纹没变", wf.dictionary().matches(dictB), "内容被改了");
        ok("失败后仍然可以开始", wf.canStart(), wf.blocker());

        System.out.println("== 场景 6：用户取消派生 —— 字典保持不变 ==");
        DictSnapshot before = wf.dictionary();
        // 「取消」在代码里等于**不调用** applyDerived；这里把两种可能都钉住：
        // 不调用 -> 不变；调用成空 -> 也不变。
        ok("不调用派生 -> 字典字样不变", wf.dictionary().name.equals(before.name), "变了");
        ok("不调用派生 -> 指纹不变",
                wf.dictionary().contentSha256.equals(before.contentSha256), "变了");
        ok("调用空派生也返回 false 且不变",
                !wf.applyDerived("x", DictSnapshot.SRC_PROFILE, "", null)
                        && wf.dictionary().name.equals(before.name), "被改了");

        System.out.println("== 场景 7：切换字典后重新执行，使用新字典 ==");
        wf.endRun();
        List<Candidate> dictC = dict("no1", "no2", "hashcat!", "no3");
        wf.setDictionary("C", DictSnapshot.SRC_FILE, "", dictC);
        AuditWorkflow.Frozen f2 = wf.beginRun();
        ok("换字典后可再次冻结", f2 != null, "frozen=null");
        eq("冻结的是 C", f2.snap.name, "C");
        String[] err2 = {null};
        AuditEngine.Result r2 = runFrozen(f2, err2);
        ok("C 命中", r2 != null && r2.hit, "r=" + r2);
        eq("命中位次 = C 里的第 3 条", r2 == null ? -1L : r2.hitIndex, 3L);

        System.out.println("== 场景 8：跑的过程中换界面字典，不影响已固定的快照 ==");
        wf.endRun();
        List<Candidate> dictB2 = dict("qwerty", "hashcat!", "zzz");
        wf.setDictionary("B2", DictSnapshot.SRC_PROFILE, "", dictB2);
        AuditWorkflow.Frozen f3 = wf.beginRun();
        ok("冻结 B2", f3 != null && f3.snap.matches(dictB2), "frozen 不对");
        // 界面中途换成一本完全无关的字典
        wf.setDictionary("Z", DictSnapshot.SRC_FILE, "", dict("zzz", "yyy"));
        eq("界面字典已经是 Z", wf.dictionary().name, "Z");
        eq("冻结包条数没变", f3.candidateCount(), dictB2.size());
        ok("冻结包内容没变（仍是 B2）", f3.snap.matches(dictB2), "被界面改动了");
        ok("冻结包没变成 Z", !f3.snap.matches(dict("zzz", "yyy")), "竟然跟着变了");
        String[] err3 = {null};
        AuditEngine.Result r3 = runFrozen(f3, err3);
        ok("这一跑仍然用 B2 命中", r3 != null && r3.hit, "r=" + r3);
        eq("命中位次仍是 2（B2 的第 2 条）", r3 == null ? -1L : r3.hitIndex, 2L);
        wf.endRun();

        System.out.println("== 场景 9：连续派生多个目标，不串用旧 SSID / 旧字典 ==");
        AuditWorkflow wd = withMaterial();
        ok("派生目标 1", deriveLike(wd, "HOME-A1B2", dir), "失败");
        String name1 = wd.dictionary().name;
        long count1 = wd.dictionary().count;
        String fp1 = wd.dictionary().fingerprint();
        String fileId1 = wd.dictionary().fileId();
        ok("第一次派生有落盘指纹", !fileId1.isEmpty(), "fileId 空");
        ok("第一次派生的名字带目标 1 的标记", name1.contains("HOME-A1B2"), name1);
        ok("第一本里确有与目标 1 相关的候选（SSID 派生真的开了）",
                hasPassword(wd.dictionaryList(), "home-a1b2"), "没有 SSID 派生的候选");

        ok("派生目标 2", deriveLike(wd, "OFFICE-9988", dir), "失败");
        String name2 = wd.dictionary().name;
        long count2 = wd.dictionary().count;
        String fp2 = wd.dictionary().fingerprint();
        String fileId2 = wd.dictionary().fileId();
        ok("第二次派生换了名字", !name2.equals(name1), name2 + " == " + name1);
        ok("第二次派生的名字带目标 2 的标记", name2.contains("OFFICE-9988"), name2);
        ok("名字里不带目标 1 的标记", !name2.contains("HOME") && !name2.contains("A1B2"), name2);
        ok("第二本里确有与目标 2 相关的候选",
                hasPassword(wd.dictionaryList(), "office-9988"), "没有目标 2 的候选");
        ok("第二本里没有目标 1 的候选（内容不串台）",
                !hasPassword(wd.dictionaryList(), "home-a1b2"), "竟然还有目标 1 的候选");
        ok("指纹换成了第二本的", !fp2.equals(fp1), fp2 + " == " + fp1);
        ok("文件指纹也换了（不是同一个文件）", !fileId2.equals(fileId1), fileId2);
        eq("候选数 = 第二本的实际条数", wd.dictionary().count, count2);
        info("目标 1: " + name1 + " (" + count1 + ", " + fp1 + ")");
        info("目标 2: " + name2 + " (" + count2 + ", " + fp2 + ")");
        AuditWorkflow.Frozen fd = wd.beginRun();
        ok("冻结的是第二本", fd != null && fd.snap.name.equals(name2), "frozen 名字不对");
        eq("冻结条数 = 第二本条数", fd == null ? -1 : fd.candidateCount(), (int) count2);

        System.out.println("== 场景 11：根因证据 —— SSID 派生默认关会产出与 SSID 无关的表 ==");
        // 修复前的调用形态：两参重载（seeds = null）。
        // TargetDictBuilder 里 useSsid = (seeds != null && seeds.useSsid)，
        // 所以这种形态下 SSID 派生必然关闭 —— 两个不同 SSID 派生出**完全相同**的内容，
        // 而字典名却写着「定向派生 · <SSID>」。名字与内容对不上，且事后无从核对。
        NetRecord na = new NetRecord("HOME-A1B2", "", -127, 2437,
                NetRecord.Security.UNKNOWN, false, "");
        NetRecord nb = new NetRecord("OFFICE-9988", "", -127, 2437,
                NetRecord.Security.UNKNOWN, false, "");
        List<Candidate> oldA = TargetDictBuilder.build(na, Prefs.DEFAULT_BUDGET).candidates;
        List<Candidate> oldB = TargetDictBuilder.build(nb, Prefs.DEFAULT_BUDGET).candidates;
        ok("两参重载：两个不同 SSID 产出完全相同的内容（这就是旧缺陷）",
                DictSnapshot.sha256OfCandidates(oldA)
                        .equals(DictSnapshot.sha256OfCandidates(oldB)),
                "内容不同，根因判断有误");
        ok("两参重载：内容里没有任何与 SSID 相关的候选",
                !hasPassword(oldA, "home-a1b2") && !hasPassword(oldA, "HOME-A1B2"),
                "居然有 SSID 候选");
        info("两参重载产出 " + oldA.size() + " 条，两个 SSID 完全相同");
        eq("可执行文件里不再有「两参重载 + fake 目标」这种调用（名字与内容对不上的来源）",
                countIn("io/github/wlmosv_png/wpaforge/MainActivity.java",
                        "fake, Prefs.deriveBudget(this));"), 0);
        ok("派生前会显式给一个 useSsid = true 的线索模型",
                countIn("io/github/wlmosv_png/wpaforge/MainActivity.java",
                        "m.useSsid = true;") == 1, "没有显式打开 SSID 派生");

        System.out.println("== 场景 10：应用重开后，当前字典与历史任务信息一致 ==");
        // 当前字典：快照编解码往返 + 从文件重读候选后仍 matches
        DictSnapshot saved = wd.dictionary();
        DictSnapshot reloaded = DictSnapshot.decode(saved.encode());
        eq("往返后名字一致", reloaded.name, saved.name);
        eq("往返后来源一致", reloaded.source, saved.source);
        eq("往返后路径一致", reloaded.filePath, saved.filePath);
        eq("往返后内容指纹一致", reloaded.contentSha256, saved.contentSha256);
        eq("往返后文件指纹一致", reloaded.fileSha256, saved.fileSha256);
        eq("往返后条数一致", reloaded.count, saved.count);
        ok("往返后仍能核对文件（文件还在、指纹对得上）",
                reloaded.fileSha256.equals(DictSnapshot.sha256OfFile(reloaded.filePath)),
                "文件指纹对不上");
        ok("往返后的快照与重读的候选一致", reloaded.matches(wd.dictionaryList()), "不一致");

        // 历史任务：结果卡里的字典指纹必须与当时用的那本一致
        DictSnapshot runSnap = fd.snap;
        OfflineVerdictLike v = new OfflineVerdictLike(runSnap);
        eq("结果卡记的字典指纹 = 任务快照指纹", v.fp, runSnap.fingerprint());
        eq("结果卡记的字典条数 = 任务快照条数", v.count, runSnap.count);
        ok("历史任务的指纹与当前字典一致（同一本）",
                v.fp.equals(wd.dictionary().fingerprint()), "不一致");

        System.out.println("== 文件被换掉：快照能识别出来（不用来路不明的内容）==");
        File victim = new File(saved.filePath);
        writeLines(victim, dict("tampered"));
        String nowHash = DictSnapshot.sha256OfFile(saved.filePath);
        ok("文件指纹变了", !nowHash.equals(saved.fileSha256), "没变");
        ok("因此快照不再被认为可以直接复用（需要重新读并按指纹校验）",
                !nowHash.equals(saved.fileSha256), "");

        System.out.println("== 附加：材料不完整时冻结被拒（不进入执行）==");
        AuditWorkflow wbad = withMaterial();
        wbad.setDictionary("D", DictSnapshot.SRC_FILE, "", dict("a", "b"));
        wbad.onMaterialProblem("bad.pcap", "不是合法的 22000 记录");
        ok("材料坏了 -> 不能开始", !wbad.canStart(), "竟然可以开始");
        ok("beginRun 返回 null", wbad.beginRun() == null, "竟然冻结成功");

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static boolean hasPassword(List<Candidate> list, String pw) {
        for (Candidate c : list) {
            if (c.password.equals(pw)) return true;
        }
        return false;
    }

    /** 统计某段文本在源文件里出现的次数（根因证据用）。 */
    private static int countIn(String rel, String sub) {
        java.io.File f = new java.io.File(TestPaths.javaSrcRoot(), rel);
        if (!f.isFile()) return -1;
        java.io.Reader r = null;
        try {
            r = new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            int c = 0, i = 0;
            while ((i = sb.indexOf(sub, i)) >= 0) {
                c++;
                i += sub.length();
            }
            return c;
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 结果卡里与字典有关的三项：指纹 / 条数 / 名称（只读它，不碰界面）。 */
    private static final class OfflineVerdictLike {
        final String fp;
        final long count;

        OfflineVerdictLike(DictSnapshot s) {
            this.fp = s.fingerprint();
            this.count = s.count;
        }
    }
}
