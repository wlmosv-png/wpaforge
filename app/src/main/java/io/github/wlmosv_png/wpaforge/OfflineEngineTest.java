package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.Hc22000;
import io.github.wlmosv_png.wpaforge.audit.MaterialDoctor;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线校验引擎的判定正确性 + 取消语义。
 *
 * <p>覆盖：官方 hashcat 向量（PMKID / EAPOL 正反向）、材料不完整必须被拒绝
 * 而不是当成"密码错"、取消不得被报成未命中、取消后可重新运行。
 */
public class OfflineEngineTest {

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

    // 官方 hashcat 示例（wiki 22000 行**原样**）。不要手拼 ——
    // 手拼过一次多写了 16 个 0，整条材料变形，命中判定全错。
    private static final String OFF_PMKID =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*686173686361742d657373"
            + "6964***";
    private static final String OFF_EAPOL_HEAD =
            "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa*54502d4c494e4b5f484153"
            + "484341545f54455354*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e*";
    private static final String OFF_FRAME =
            "0103007502010a0000000000000000000148ce2ccba9c1fda130ff2fbbfb4fd3b063d1a93920b0f7df54a5cb"
            + "f787b16171000000000000000000000000000000000000000000000000000000000000000000000000000000"
            + "000000000000000000001630140100000fac040100000fac040100000fac028000";
    private static final String OFF_EAPOL = OFF_EAPOL_HEAD + OFF_FRAME + "*a2";

    private static List<Candidate> dict(String... pws) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (String p : pws) d.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return d;
    }

    private static List<Candidate> bigDict(int n) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (int i = 0; i < n; i++) d.add(new Candidate("cand" + i, Candidate.Origin.USER_DICT));
        return d;
    }

    /** 同步跑一次 PMKID，返回结果或 null（出错时错误写进 err[0]）。 */
    private static AuditEngine.Result runPmkid(String line, List<Candidate> d, String[] err) {
        Hc22000.Entry e = Hc22000.parseLine(line);
        final AuditEngine.Result[] out = {null};
        new AuditEngine().runPmkid(Hc22000.toPmkid(e), d, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { if (err != null) err[0] = m; }
        });
        return out[0];
    }

    private static AuditEngine.Result runEapol(String line, List<Candidate> d, String[] err) {
        Hc22000.Entry e = Hc22000.parseLine(line);
        final AuditEngine.Result[] out = {null};
        new AuditEngine().runHandshake(Hc22000.toHandshake(e), d, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { if (err != null) err[0] = m; }
        });
        return out[0];
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 官方向量：PMKID 正向 ==");
        String[] e1 = {null};
        AuditEngine.Result r1 = runPmkid(OFF_PMKID, dict("nope", "hashcat!", "other"), e1);
        ok("没有报错", e1[0] == null);
        ok("命中", r1 != null && r1.hit);
        eq("命中口令", r1 == null ? null : r1.hitPassword, "hashcat!");
        eq("位次 = 2", r1 == null ? -1L : r1.hitIndex, 2L);
        ok("不是取消", r1 != null && !r1.cancelled);

        System.out.println("== 官方向量：EAPOL 正向 ==");
        String[] e2 = {null};
        AuditEngine.Result r2 = runEapol(OFF_EAPOL, dict("nope", "hashcat!", "other"), e2);
        ok("没有报错", e2[0] == null);
        ok("命中", r2 != null && r2.hit);
        eq("命中口令", r2 == null ? null : r2.hitPassword, "hashcat!");
        ok("不是取消", r2 != null && !r2.cancelled);

        System.out.println("== 官方向量：反向（错误密码必须未命中）==");
        String[] e3 = {null};
        AuditEngine.Result r3 = runEapol(OFF_EAPOL, dict("hashcat", "12345678", "password"), e3);
        ok("没有报错", e3[0] == null);
        ok("未命中", r3 != null && !r3.hit);
        ok("不是取消", r3 != null && !r3.cancelled);
        ok("确实跑满了字典（exhausted）", r3 != null && r3.exhausted());
        eq("完成数 = 字典长度", r3 == null ? -1L : r3.tested, 3L);

        AuditEngine.Result r4 = runPmkid(OFF_PMKID, dict("hashcat", "12345678", "password"), null);
        ok("PMKID 错误密码也未命中", r4 != null && !r4.hit && r4.exhausted());

        System.out.println("== 材料不完整：必须拒绝，不能算未命中 ==");
        // 帧短于 17+32，key nonce 取不出来
        String shortFrame = "WPA*02*024022795224bffca545276c3762686f*6466b38ec3fc*225edc49b7aa"
                + "*54502d4c494e4b5f484153484341545f54455354"
                + "*10e3be3b005a629e89de088d6a2fdc489db83ad4764f2d186b9cde15446e972e"
                + "*0103007502*a2";
        Hc22000.Entry bad = Hc22000.parseLine(shortFrame);
        ok("短帧在解析阶段就被拒（返回 null，没有变成半截材料）", bad == null);

        // 手工构造一份"能过解析、但诊断判不可执行"的材料：MIC 字段没置零
        // MIC 字段在帧的 offset 81..96，即 hex 的第 162..194 个字符
        String dirtyFrame = OFF_FRAME.substring(0, 162)
                + "00112233445566778899aabbccddeeff" + OFF_FRAME.substring(194);
        String micDirty = OFF_EAPOL.replace(OFF_FRAME, dirtyFrame);
        Hc22000.Entry dirty = Hc22000.parseLine(micDirty);
        ok("MIC 未置零的材料仍能解析", dirty != null);
        MaterialDoctor.Report rep = MaterialDoctor.examine(dirty);
        ok("诊断判为不可执行", !rep.executable);
        ok("给出了明确原因", !rep.blockers.isEmpty());
        System.out.println("       原因: " + rep.summary());

        String[] e4 = {null};
        AuditEngine.Result r5 = runEapol(micDirty, dict("hashcat!"), e4);
        ok("引擎给出错误而不是'未命中'", e4[0] != null && r5 == null);

        System.out.println("== 取消语义 ==");
        final AuditEngine engine = new AuditEngine();
        Hc22000.Entry pm = Hc22000.parseLine(OFF_PMKID);
        List<Candidate> many = bigDict(6000);
        final AuditEngine.Result[] rc = {null};
        final boolean[] sawProgress = {false};
        engine.runPmkid(Hc22000.toPmkid(pm), many, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {
                sawProgress[0] = true;
                if (t >= 300) engine.cancel();
            }
            @Override public void onDone(AuditEngine.Result r) { rc[0] = r; }
            @Override public void onError(String m) {}
        });
        ok("取消后仍然回调 onDone（不静默）", rc[0] != null);
        ok("cancelled = true", rc[0] != null && rc[0].cancelled);
        ok("不是命中", rc[0] != null && !rc[0].hit);
        ok("**不是未命中**（exhausted = false）", rc[0] != null && !rc[0].exhausted());
        ok("完成数小于总数", rc[0] != null && rc[0].tested < many.size());
        ok("完成数为正（确实跑了）", rc[0] != null && rc[0].tested > 0);

        System.out.println("== 取消后可以重新运行（不得残留取消态）==");
        final AuditEngine.Result[] rr = {null};
        engine.runPmkid(Hc22000.toPmkid(pm), dict("x", "hashcat!"), new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { rr[0] = r; }
            @Override public void onError(String m) {}
        });
        ok("重跑结果没有被上一次取消污染", rr[0] != null && !rr[0].cancelled);
        ok("重跑正常命中", rr[0] != null && rr[0].hit);
        eq("重跑命中位次 = 2", rr[0] == null ? -1L : rr[0].hitIndex, 2L);

        System.out.println("== 取消不推进游标（tested 不超总数）==");
        final AuditEngine engine2 = new AuditEngine();
        final AuditEngine.Result[] rc2 = {null};
        engine2.runHandshake(Hc22000.toHandshake(Hc22000.parseLine(OFF_EAPOL)),
                bigDict(6000), new AuditEngine.Progress() {
                    @Override public void onProgress(long t, long total, String f) {
                        if (t >= 200) engine2.cancel();
                    }
                    @Override public void onDone(AuditEngine.Result r) { rc2[0] = r; }
                    @Override public void onError(String m) {}
                });
        ok("EAPOL 取消也拿到结果", rc2[0] != null);
        ok("EAPOL 取消 marked", rc2[0] != null && rc2[0].cancelled);
        ok("完成数 ≤ 总数", rc2[0] != null && rc2[0].tested <= 6000);

        System.out.println("== 取消状态不串台（新引擎）==");
        final AuditEngine.Result[] rn = {null};
        new AuditEngine().runPmkid(Hc22000.toPmkid(pm), dict("hashcat!"), new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { rn[0] = r; }
            @Override public void onError(String m) {}
        });
        ok("全新引擎跑第一条即命中", rn[0] != null && rn[0].hit && rn[0].hitIndex == 1);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
