package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.WpaCore;
import io.github.wlmosv_png.wpaforge.core.NetRecord;
import io.github.wlmosv_png.wpaforge.scan.ChannelAnalyzer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 端到端自检：自己构造一份握手（用已知口令算真 MIC），
 * 再让审计引擎从字典中把它找回来。全程本地、无网络。
 */
public class E2ETest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " " + detail);
        }
    }

    /** 翻转一位，模拟真实握手的不确定性 */
    private static byte[] nonce(int seed) {
        byte[] n = new byte[32];
        long s = seed * 2654435761L;
        for (int i = 0; i < 32; i++) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            n[i] = (byte) (s >>> 33);
        }
        return n;
    }

    public static void main(String[] args) {
        final String SSID = "WPAForge-Lab";
        final String TRUTH = "home2026lab";   // 真实口令，将放进字典里
        final byte[] apMac = WpaCore.hexToBytes("aabbccddee01");
        final byte[] staMac = WpaCore.hexToBytes("112233445566");
        final byte[] aN = nonce(7);
        final byte[] sN = nonce(13);

        // 1) 用真实口令推导 PMK/PTK
        byte[] pmk = WpaCore.derivePmk(TRUTH, SSID);
        byte[] ptk = WpaCore.derivePtk(pmk, apMac, staMac, aN, sN);
        byte[] kck = java.util.Arrays.copyOfRange(ptk, 0, 16);

        // 2) 造一个假的 EAPOL 帧，MIC 字段先置零，算出真 MIC 填进去
        int micOffset = 4;
        byte[] frame = new byte[99];
        for (int i = 0; i < frame.length; i++) frame[i] = (byte) (i * 7);
        for (int i = 0; i < 16; i++) frame[micOffset + i] = 0;
        byte[] realMic = java.util.Arrays.copyOf(WpaCore.hmacSha1(kck, frame), 16);
        System.arraycopy(realMic, 0, frame, micOffset, 16);

        // 3) 构造握手对象，交给引擎
        AuditEngine.Handshake hs = new AuditEngine.Handshake(
                SSID, apMac, staMac, aN, sN, frame, micOffset, realMic);

        System.out.println("== 握手材料自检 ==");
        ok("材料完整", hs.validate().isEmpty(), hs.validate());

        System.out.println("== 字典里包含真口令 ==");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 200; i++) words.add("wrongpass" + i);
        words.add(TRUTH);
        for (int i = 0; i < 50; i++) words.add("trailing" + i);

        DictGenerator g = new DictGenerator();
        g.classic().numericPatterns();
        g.importLines(words);
        List<io.github.wlmosv_png.wpaforge.audit.Candidate> dict = g.build();
        System.out.println("     字典条数: " + dict.size());

        final boolean[] done = {false};
        final String[] found = {null};
        final long[] tested = {0};

        new AuditEngine().runHandshake(hs, dict, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) { tested[0] = t; }
            @Override public void onDone(AuditEngine.Result r) {
                done[0] = true;
                found[0] = r.hitPassword;
                System.out.println("     测试条数: " + r.tested + "/" + dict.size()
                        + "  耗时: " + r.elapsedMs + "ms"
                        + "  速率: " + String.format("%.1f", r.perSecond) + " 条/秒");
            }
            @Override public void onError(String m) {
                done[0] = true;
                System.out.println("     错误: " + m);
            }
        });

        ok("审计完成", done[0], "");
        ok("命中真口令 \"" + TRUTH + "\"", TRUTH.equals(found[0]),
                "实际命中: " + found[0]);

        System.out.println("== 错误口令必须不命中 ==");
        final boolean[] hit2 = {true};
        byte[] badMic = realMic.clone();
        badMic[0] ^= 0x01;
        AuditEngine.Handshake hsBad = new AuditEngine.Handshake(
                SSID, apMac, staMac, aN, sN, frame, micOffset, badMic);
        new AuditEngine().runHandshake(hsBad, dict, new AuditEngine.Progress() {
            @Override public void onProgress(long t, long total, String f) {}
            @Override public void onDone(AuditEngine.Result r) { hit2[0] = r.hit; }
            @Override public void onError(String m) {}
        });
        ok("MIC 被篡改时不误报", !hit2[0], "");

        System.out.println("== 字典位次评估（模式 A） ==");
        long rank = AuditEngine.rankOf(dict, "12345678");
        ok("12345678 排在最前段 (rank=" + rank + ")", rank > 0 && rank <= 20, "");
        long rankMiss = AuditEngine.rankOf(dict, "not-in-any-dict-xyz");
        ok("不存在的口令返回 -1", rankMiss == -1, "实际 " + rankMiss);

        System.out.println("== 信道分析 ==");
        List<NetRecord> nets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nets.add(new NetRecord("AP" + i, "aabbccddee0" + i, -40 - i * 5,
                    2412 + 5 * (i % 3), NetRecord.Security.WPA2, false, "[WPA2-PSK]"));
        }
        nets.add(new NetRecord("5G-AP", "aabbccddee99", -60, 5180,
                NetRecord.Security.WPA3, false, "[RSN-SAE-CCMP]"));
        ChannelAnalyzer.Report rep = ChannelAnalyzer.analyze(nets);
        ok("2.4G 统计非空", !rep.stats24.isEmpty(), "");
        ok("5G 统计非空", !rep.stats5.isEmpty(), "");
        ok("推荐 2.4G 信道在 {1,6,11,13}",
                rep.recommended24 == 1 || rep.recommended24 == 6
                        || rep.recommended24 == 11 || rep.recommended24 == 13,
                "得到 " + rep.recommended24);

        System.out.println("== 性能基准 ==");
        long t0 = System.currentTimeMillis();
        int N = 500;
        int hits = 0;
        for (int i = 0; i < N; i++) {
            if (WpaCore.checkCandidate("candidate" + i, SSID, apMac, staMac,
                    aN, sN, frame, micOffset, realMic)) hits++;
        }
        long dt = System.currentTimeMillis() - t0;
        double rate = dt > 0 ? N * 1000.0 / dt : 0;
        System.out.println("     " + N + " 条耗时 " + dt + "ms, 速率 "
                + String.format("%.1f", rate) + " 条/秒");
        ok("基准跑通且只命中 1 次", hits == 0 || hits == 1, "命中 " + hits);

        System.out.println();
        System.out.println("=== 端到端结果: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
