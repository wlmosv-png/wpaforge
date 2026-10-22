package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.AuditEngine;
import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.WpaCore;

import java.util.ArrayList;
import java.util.List;

/**
 * PMKID 校验测试。
 *
 * 三组期望值由 Python 的 hashlib / hmac **独立实现**算出，不是拿本项目代码反推 ——
 * 与 SelfTest 用 RFC 官方向量是同一个思路：只有独立来源才算真验证。
 */
public class PmkidTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static byte[] hex(String s) {
        return WpaCore.hexToBytes(s);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("== 与独立实现（Python hashlib）对齐 ==");
        ok("向量 1：12345678 / TestSSID",
                "8547b061fd9759b2779ea0bdb7bda7f9".equals(toHex(
                        WpaCore.computePmkid("12345678", "TestSSID",
                                hex("001122334455"), hex("66778899aabb")))));
        ok("向量 2：password / FAST_E972",
                "1c696a385f76965d39a3e9f2d2aab26a".equals(toHex(
                        WpaCore.computePmkid("password", "FAST_E972",
                                hex("AABBCCE97211"), hex("001122334455")))));
        ok("向量 3：中文 SSID",
                "a42e5d4afb23b3908bc672b980ad6f87".equals(toHex(
                        WpaCore.computePmkid("MyPass2026!", "小卖部WiFi5",
                                hex("28937D45DCED"), hex("2C48D7415E2D")))));

        System.out.println("== checkPmkid ==");
        byte[] exp = hex("8547b061fd9759b2779ea0bdb7bda7f9");
        ok("正确密码 -> true",
                WpaCore.checkPmkid("12345678", "TestSSID", hex("001122334455"), hex("66778899aabb"), exp));
        ok("错误密码 -> false",
                !WpaCore.checkPmkid("12345679", "TestSSID", hex("001122334455"), hex("66778899aabb"), exp));
        ok("SSID 不同 -> false",
                !WpaCore.checkPmkid("12345678", "OtherSSID", hex("001122334455"), hex("66778899aabb"), exp));
        ok("AP MAC 不同 -> false",
                !WpaCore.checkPmkid("12345678", "TestSSID", hex("001122334456"), hex("66778899aabb"), exp));
        ok("期望值长度不对 -> false",
                !WpaCore.checkPmkid("12345678", "TestSSID", hex("001122334455"), hex("66778899aabb"), hex("0011")));
        ok("期望值 null -> false",
                !WpaCore.checkPmkid("12345678", "TestSSID", hex("001122334455"), hex("66778899aabb"), null));
        ok("AP MAC 长度不对 -> false（且不崩）",
                !WpaCore.checkPmkid("12345678", "TestSSID", hex("0011"), hex("66778899aabb"), exp));

        System.out.println("== 引擎跑 PMKID 字典 ==");
        List<Candidate> dict = new ArrayList<>();
        dict.add(new Candidate("00000000", Candidate.Origin.DIGITS));
        dict.add(new Candidate("password", Candidate.Origin.LETTERS));
        dict.add(new Candidate("12345678", Candidate.Origin.DIGITS));   // 真口令在第 3 位
        dict.add(new Candidate("88888888", Candidate.Origin.DIGITS));
        final boolean[] hit = {false};
        final long[] idx = {-1};
        new AuditEngine().runPmkid(
                new AuditEngine.Pmkid("TestSSID", hex("001122334455"), hex("66778899aabb"), exp),
                dict, new AuditEngine.Progress() {
                    @Override public void onProgress(long tested, long total, String found) {}
                    @Override public void onDone(AuditEngine.Result r) { hit[0] = r.hit; idx[0] = r.hitIndex; }
                    @Override public void onError(String message) {}
                });
        ok("命中", hit[0]);
        ok("报出字典位次 = 3", idx[0] == 3);

        final boolean[] miss = {true};
        final long[] tested = {0};
        List<Candidate> dict2 = new ArrayList<>();
        dict2.add(new Candidate("aaaaaaaa", Candidate.Origin.LETTERS));
        dict2.add(new Candidate("bbbbbbbb", Candidate.Origin.LETTERS));
        new AuditEngine().runPmkid(
                new AuditEngine.Pmkid("TestSSID", hex("001122334455"), hex("66778899aabb"), exp),
                dict2, new AuditEngine.Progress() {
                    @Override public void onProgress(long t, long total, String found) {}
                    @Override public void onDone(AuditEngine.Result r) { miss[0] = r.hit; tested[0] = r.tested; }
                    @Override public void onError(String message) {}
                });
        ok("不含真口令 -> 不命中", !miss[0]);
        ok("跑满整个字典（2 条）", tested[0] == 2);

        System.out.println("== 材料校验 ==");
        ok("缺 SSID 被拦下", !new AuditEngine.Pmkid("", hex("001122334455"), hex("66778899aabb"), exp)
                .validate().isEmpty());
        ok("PMKID 长度不对被拦下", !new AuditEngine.Pmkid("S", hex("001122334455"), hex("66778899aabb"), hex("0011"))
                .validate().isEmpty());
        ok("材料齐全通过", new AuditEngine.Pmkid("S", hex("001122334455"), hex("66778899aabb"), exp)
                .validate().isEmpty());

        System.out.println();
        System.out.println("=== PMKID 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
