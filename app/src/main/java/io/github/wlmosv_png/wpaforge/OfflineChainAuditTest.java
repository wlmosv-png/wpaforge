package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.AuditWorkflow;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictSnapshot;
import io.github.wlmosv_png.wpaforge.audit.Hc22000;
import io.github.wlmosv_png.wpaforge.audit.MaterialDoctor;
import io.github.wlmosv_png.wpaforge.audit.OfflineVerdict;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.audit.WpaCore;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线校验**全链路**审计回归 —— 把本轮审计里"确认过的缺陷"逐条钉死。
 *
 * <h3>本类覆盖的每一个向量都由独立实现生成</h3>
 * 全部用 Python 的 hashlib/hmac 手算（PBKDF2-HMAC-SHA1/MD5、PRF-512、PMKID、MIC），
 * **不复用 WPAForge 自身代码** —— 否则就是"用被测实现验证被测实现"。
 *
 * <h3>覆盖清单</h3>
 * <ol>
 *   <li><b>PBKDF2/PMK 公开向量</b>：RFC 6070 两条 + 802.11i 的 PMK 向量；</li>
 *   <li><b>非 UTF-8（GBK）SSID</b>：修前必然是假「未命中」，修后必须命中，
 *       并保留反证（走 String 重编码必然失败）；</li>
 *   <li><b>Key Version 1（HMAC-MD5）</b>：修前一律假未命中，修后命中；</li>
 *   <li><b>Key Version 3（AES-CMAC）</b>：必须被拦下，绝不能被报成「未命中」；</li>
 *   <li><b>结果编解码</b>：字段以反斜杠结尾时不能再丢结果；</li>
 *   <li><b>派生字典 → 离线执行</b>：顺序、内容、命中位次逐项对齐，
 *       证明"每个实际执行的候选都对应正确的原始候选"。</li>
 * </ol>
 */
public class OfflineChainAuditTest {

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

    private static void info(String s) {
        System.out.println("  ·    " + s);
    }

    private static List<Candidate> dict(String... pws) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (String p : pws) d.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return d;
    }

    // ================= 独立实现生成的向量（脚本见审计报告） =================
    // 全部由 Python hashlib/hmac 手算（PBKDF2 → PRF-512 → MIC / PMKID），
    // **不复用本仓库代码**；常量由脚本生成、不手抄（手抄过一位，整条材料就废了）。

    /** GBK 的「小卖部WiFi」= 10 字节原始 ESSID，口令 qwerty12，Key Version 2。 */
    private static final String GBK_SSID_HEX = "d0a1c2f4b2bf57694669";
    private static final String GBK_L1 =
            "WPA*01*7c20619cb5c5ea59510a29f9b4a69788*02aabbccdd11*02aabbccdd12*d0"
            + "a1c2f4b2bf57694669***";
    private static final String GBK_L2 =
            "WPA*02*4a2a162ff8487123b3d5875961daf868*02aabbccdd11*02aabbccdd12*d0"
            + "a1c2f4b2bf57694669*5ea941e2800b211b697ce7eaad964d9071b4ac3fe60de59b2"
            + "97d00e1e769cbd3*02030075020102001000000000000000011477d6d951a696b044"
            + "77151b8aab1a82c8aa5c7bd45e93291dbffde4b6d5cb3f0000000000000000000000"
            + "00000000000000000000000000000000000000000000000000000000000000000000"
            + "000000001630140100000fac040100000fac040100000fac028000*a2";

    /** SSID "WPAForge-KV1-TEST"，口令 qwerty12，Key Version 1（HMAC-MD5）。 */
    private static final String KV1_L1 =
            "WPA*01*082c8fe34dd8105f02a891a57160e1ed*02aabbccdd21*02aabbccdd22*57"
            + "5041466f7267652d4b56312d54455354***";
    private static final String KV1_L2 =
            "WPA*02*5185d21b42ccc6bbb1b6a04d6216669c*02aabbccdd21*02aabbccdd22*57"
            + "5041466f7267652d4b56312d54455354*e79b70342b0753352f3449bdaa63cd9b2b1"
            + "006900451dcb8768e0987e4c17c87*0203007502010100100000000000000001f1d8"
            + "6a4c6b0bae74c8f424363bdf8328d7b1ae4f27c1632c53b41e7036f2d3dd00000000"
            + "00000000000000000000000000000000000000000000000000000000000000000000"
            + "00000000000000000000001630140100000fac040100000fac040100000fac028000"
            + "*a2";

    /** SSID "WPAForge-KV3-TEST"，Key Version 3（AES-CMAC）：本应用不支持。 */
    private static final String KV3_L2 =
            "WPA*02*4145e06a6e68f2377334e1098b302255*02aabbccdd31*02aabbccdd32*57"
            + "5041466f7267652d4b56332d54455354*6881f3e7b101c816561d31275830818fd37"
            + "cd3fd9548db440af0722947a6dc71*0203007502010300100000000000000001a639"
            + "03e9c228efb706912e2cff22034d670d58dc067922ed79746b7ec93abfb600000000"
            + "00000000000000000000000000000000000000000000000000000000000000000000"
            + "00000000000000000000001630140100000fac040100000fac040100000fac028000"
            + "*a2";
    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 同步跑一条 PMKID 材料。 */
    private static AuditEngine.Result runPmkid(Hc22000.Entry e, List<Candidate> d,
                                               String[] err) {
        final AuditEngine.Result[] out = {null};
        new AuditEngine().runPmkid(Hc22000.toPmkid(e), d, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { if (err != null) err[0] = m; }
        });
        return out[0];
    }

    private static AuditEngine.Result runEapol(Hc22000.Entry e, List<Candidate> d,
                                               String[] err) {
        final AuditEngine.Result[] out = {null};
        new AuditEngine().runHandshake(Hc22000.toHandshake(e), d, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { if (err != null) err[0] = m; }
        });
        return out[0];
    }

    public static void main(String[] args) {
        System.out.println("== 1. PBKDF2 / PMK：公开向量交叉验证 ==");
        eq("RFC 6070 P=password S=salt c=4096 dkLen=20",
                hex(WpaCore.pbkdf2HmacSha1("password".getBytes(), "salt".getBytes(), 4096, 20)),
                "4b007901b765489abead49d926f721d065a429c1");
        eq("RFC 6070 长向量 c=4096 dkLen=25",
                hex(WpaCore.pbkdf2HmacSha1(
                        "passwordPASSWORDpassword".getBytes(),
                        "saltSALTsaltSALTsaltSALTsaltSALTsalt".getBytes(), 4096, 25)),
                "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038");
        eq("802.11i PMK：passphrase=password SSID=IEEE",
                hex(WpaCore.derivePmk("password", "IEEE")),
                "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e");

        System.out.println("== 2. 非 UTF-8（GBK）SSID：修前必然假未命中 ==");
        Hc22000.Entry g = Hc22000.parseLine(GBK_L1);
        ok("能解析出这条 PMKID 材料", g != null);
        eq("原始 SSID 字节", hex(g.ssidBytes), GBK_SSID_HEX);
        ok("标记为非 UTF-8（展示时不假装可读）", !g.ssidExact);
        ok("展示文案明确标注非 UTF-8", g.ssidLabel().contains("非 UTF-8"));
        MaterialDoctor.Report gre = MaterialDoctor.examine(g);
        eq("诊断=可执行（不是「信息不完整」）", gre.status(),
                MaterialDoctor.Status.RUNNABLE);
        // 反证：如果按 String 重编码当 salt，真口令也必然不匹配
        ok("反证：走 String 重编码的旧路径必然失败",
                !WpaCore.checkPmkid("qwerty12", g.ssid, g.apMac, g.staMac, g.micOrPmkid));
        ok("反证：重编码后的 salt 与原始字节不等",
                !java.util.Arrays.equals(g.ssidBytes,
                        g.ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String[] gerr = {null};
        AuditEngine.Result gr = runPmkid(g, dict("wrong", "qwerty12", "zzz"), gerr);
        ok("没有报错", gerr[0] == null);
        ok("现在必须命中（修前是假未命中）", gr != null && gr.hit);
        eq("命中口令", gr == null ? null : gr.hitPassword, "qwerty12");
        eq("位次", gr == null ? -1L : gr.hitIndex, 2L);
        // 同一条材料的 EAPOL 分支（kv=2）也要能命中
        Hc22000.Entry g2 = Hc22000.parseLine(GBK_L2);
        ok("EAPOL 也能解析", g2 != null);
        String[] gerr2 = {null};
        AuditEngine.Result gr2 = runEapol(g2, dict("qwerty12"), gerr2);
        ok("EAPOL 分支命中", gerr2[0] == null && gr2 != null && gr2.hit);

        System.out.println("== 3. Key Version 1（HMAC-MD5）：修前必然假未命中 ==");
        Hc22000.Entry k1 = Hc22000.parseLine(KV1_L1);
        eq("Key Version 读出来是 1", Hc22000.keyVersionOf(
                Hc22000.parseLine(KV1_L2).eapol), 1);
        eq("诊断=可执行", MaterialDoctor.examine(k1).status(),
                MaterialDoctor.Status.RUNNABLE);
        String[] k1err = {null};
        AuditEngine.Result k1r = runPmkid(k1, dict("qwerty12"), k1err);
        ok("PMKID 命中", k1err[0] == null && k1r != null && k1r.hit);
        Hc22000.Entry k1e = Hc22000.parseLine(KV1_L2);
        String[] k1eerr = {null};
        AuditEngine.Result k1er = runEapol(k1e, dict("qwerty12"), k1eerr);
        ok("EAPOL（HMAC-MD5）命中 —— 修前这一条永远是未命中",
                k1eerr[0] == null && k1er != null && k1er.hit);
        AuditEngine.Result k1wrong = runEapol(k1e, dict("qwerty13", "qwerty1"),
                new String[1]);
        ok("HMAC-MD5 下错误的候选不会命中（不是「什么都命中」）",
                k1wrong != null && !k1wrong.hit && !k1wrong.cancelled);
        eq("跑满条数", k1wrong == null ? -1L : k1wrong.tested, 2L);

        System.out.println("== 4. Key Version 3（AES-CMAC）：必须拦下，不能报未命中 ==");
        Hc22000.Entry k3 = Hc22000.parseLine(KV3_L2);
        ok("能解析", k3 != null);
        eq("诊断=不支持（不是可执行）", MaterialDoctor.examine(k3).status(),
                MaterialDoctor.Status.UNSUPPORTED);
        ok("原因写明 AES-CMAC 不支持",
                MaterialDoctor.examine(k3).whyNot().contains("AES-CMAC"));
        String[] k3err = {null};
        AuditEngine.Result k3r = runEapol(k3, dict("qwerty12"), k3err);
        ok("引擎拒绝执行（报错而不是返回未命中）", k3err[0] != null);
        ok("没有产生任何「未命中」结论", k3r == null);
        ok("引擎的拒绝理由也提到不支持", k3err[0] != null && k3err[0].contains("不支持"));

        System.out.println("== 5. 结果编解码：字段以反斜杠结尾不能丢结果 ==");
        OfflineVerdict bak = new OfflineVerdict(OfflineVerdict.State.HIT, "cap\\", "EAPOL",
                "ssid\\", "AP\\", "dict\\", 10, 1, 5, 1L, "pw\\", "note\\",
                "file\\", "FP\\", "/p\\", "FID\\");
        OfflineVerdict back = OfflineVerdict.decode(bak.encode());
        ok("以反斜杠结尾的 13 个字段全部能往返（修前整个结果会消失）",
                back.hasResult());
        eq("状态保住", back.state, OfflineVerdict.State.HIT);
        eq("口令保住（修前这条明文会被丢掉）", back.hitPassword, "pw\\");
        eq("SSID 保住", back.ssid, "ssid\\");
        eq("路径保住", back.dictPath, "/p\\");
        eq("note 保住", back.note, "note\\");
        // 穷举：单独一个反斜杠 / 含 \| 组合 / 反斜杠夹在分隔符旁
        String[] tricky = {"\\", "a\\", "\\a", "\\|", "|\\", "a\\|b", "\\\\", "x\\\\y"};
        int badTrip = 0;
        for (String t : tricky) {
            OfflineVerdict v = new OfflineVerdict(OfflineVerdict.State.MISS, t, "PMKID",
                    t, t, t, 3, 3, 1, 1L, "", t, t, t, t, t);
            OfflineVerdict b = OfflineVerdict.decode(v.encode());
            if (!b.hasResult() || !t.equals(b.fileName) || !t.equals(b.note)
                    || !t.equals(b.dictPath)) {
                badTrip++;
                info("往返失败: " + t + " -> hasResult=" + b.hasResult()
                        + " fileName=" + b.fileName);
            }
        }
        eq("8 种刁钻字段全部往返一致", badTrip, 0);

        System.out.println("== 6. 派生字典 -> 离线执行：顺序 / 内容 / 位次逐项对齐 ==");
        // 材料用**独立向量**（第 2 节那条 GBK 材料，口令 qwerty12），
        // 这样"算法正确"由独立实现保证；本节只证明**派生字典到引擎的索引完整性**。
        String realPw = "qwerty12";
        Hc22000.Entry mat = Hc22000.parseLine(GBK_L1);
        ok("材料解析成功", mat != null);
        ok("材料诊断=可执行", MaterialDoctor.examine(mat).executable);

        // 真实的派生结果（确定性：同样的目标+预算+线索必定同一条列表）
        NetRecord target = new NetRecord("WPAForge-AUDIT-7788", "", -127, 2437,
                NetRecord.Security.UNKNOWN, false, "");
        SeedModel seeds = new SeedModel();
        seeds.useSsid = true;
        TargetDictBuilder.Result dr = TargetDictBuilder.build(target, 120, null, seeds, null);
        ok("派生出了候选（>10 条）", dr.candidates.size() > 10);
        // 派生列表 + 在**已知位置**插入真口令（模拟"派生字典里正好有正确口令"）
        int k = 6;
        List<Candidate> derived = new ArrayList<Candidate>(dr.candidates);
        derived.add(k, new Candidate(realPw, Candidate.Origin.USER_SEED));
        info("派生 " + dr.candidates.size() + " 条；把真口令插到第 " + (k + 1)
                + " 条（下标 " + k + "）");

        AuditWorkflow wf = new AuditWorkflow();
        wf.onMaterialOk("derived-audit.hc22000", 0, "", 1, 1, 0);
        wf.selectEntry(0, true, "");
        ok("把这本派生字典设为当前字典",
                wf.applyDerived("定向派生 · WPAForge-AUDIT-7788",
                        DictSnapshot.SRC_PROFILE, "", derived));
        AuditWorkflow.Frozen fz = wf.beginRun();
        ok("冻结成功", fz != null);
        eq("冻结条数 = 派生条数", fz == null ? -1 : fz.candidateCount(), derived.size());
        eq("冻结内容指纹 = 派生列表指纹", fz.snap.contentSha256,
                DictSnapshot.sha256OfCandidates(derived));
        // 逐项对齐：内容与顺序都必须一致（任何过滤/重排都会在这里暴露）
        int mismatch = 0;
        for (int i = 0; i < derived.size(); i++) {
            if (!derived.get(i).password.equals(fz.dict.get(i).password)) mismatch++;
        }
        eq("冻结列表与派生列表逐项相同（顺序 + 内容）", mismatch, 0);

        List<Long> progress = new ArrayList<Long>();
        final AuditEngine.Result[] out = {null};
        final String[] err = {null};
        fz.run(new AuditEngine(), mat, new AuditEngine.Progress() {
            @Override public void onProgress(long tested, long total, String found) {
                progress.add(Long.valueOf(tested));
            }
            @Override public void onDone(AuditEngine.Result r) { out[0] = r; }
            @Override public void onError(String m) { err[0] = m; }
        });
        ok("没有报错", err[0] == null);
        ok("命中（派生的候选真的进了验证算法）", out[0] != null && out[0].hit);
        eq("命中位次 == 该候选在派生列表里的下标+1（无错位、无跳号）",
                out[0] == null ? -1L : out[0].hitIndex, (long) (k + 1));
        eq("命中口令 == 列表里那一条", out[0] == null ? null : out[0].hitPassword, realPw);
        ok("进度严格递增（引擎没有跳号遍历）", isStrictlyIncreasing(progress));
        info("进度回调序列 = " + progress);

        // 换掉界面上的字典，冻结包不受影响（离线链只认冻结副本）
        List<Candidate> other = dict("a", "b", "c");
        wf.setDictionary("另一本", DictSnapshot.SRC_FILE, "", other);
        ok("界面换字典后冻结包内容不变", fz.snap.matches(derived));
        ok("冻结包不是新字典", !fz.snap.matches(other));

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static boolean isStrictlyIncreasing(List<Long> xs) {
        for (int i = 1; i < xs.size(); i++) {
            if (xs.get(i).longValue() <= xs.get(i - 1).longValue()) return false;
        }
        return true;
    }

    /** 与 Python 参考实现同构的 PMKID 生成（用于第 6 节的"造材料"步骤）。 */
    static final class IndependentVector {
        static String pmkidLine(String ssid, String password, String apHex, String staHex) {
            byte[] ap = WpaCore.hexToBytes(apHex);
            byte[] sta = WpaCore.hexToBytes(staHex);
            byte[] ssidBytes = ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] pmk = WpaCore.pbkdf2HmacSha1(password.getBytes(), ssidBytes, 4096, 32);
            byte[] data = new byte[8 + 6 + 6];
            System.arraycopy(new byte[]{0x50, 0x4D, 0x4B, 0x20, 0x4E, 0x61, 0x6D, 0x65},
                    0, data, 0, 8);
            System.arraycopy(ap, 0, data, 8, 6);
            System.arraycopy(sta, 0, data, 14, 6);
            byte[] mac = WpaCore.hmacSha1(pmk, data);
            byte[] id = new byte[16];
            System.arraycopy(mac, 0, id, 0, 16);
            return "WPA*01*" + hex(id) + "*" + apHex.toLowerCase() + "*"
                    + staHex.toLowerCase() + "*" + hex(ssidBytes) + "***";
        }
    }
}
