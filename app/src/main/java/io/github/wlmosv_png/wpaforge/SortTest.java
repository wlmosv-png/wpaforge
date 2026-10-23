package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictGenerator;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能排序自检。验证"目标线索会被排到前面"，
 * 以及排序不增删任何候选。
 */
public class SortTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) {
        System.out.println("== 目标线索优先 ==");
        NetRecord target = new NetRecord("B209", "AA:BB:CC:11:22:02", -45, 2432,
                NetRecord.Security.WPA2, false, "[WPA2-PSK]");

        List<Candidate> dict = new ArrayList<>();
        dict.add(new Candidate("aardvark", Candidate.Origin.USER_DICT));
        dict.add(new Candidate("zzzzzzzzzz", Candidate.Origin.USER_DICT));
        dict.add(new Candidate("B209123", Candidate.Origin.USER_DICT));
        dict.add(new Candidate("randompass", Candidate.Origin.USER_DICT));
        dict.add(new Candidate("112202", Candidate.Origin.USER_DICT));
        dict.add(new Candidate("12345678", Candidate.Origin.USER_DICT));

        List<Candidate> sorted = SmartSorter.prioritize(target, dict, 0);
        ok("条数不变", sorted.size() == dict.size(),
                dict.size() + " -> " + sorted.size());

        System.out.println("     排序结果:");
        for (int i = 0; i < sorted.size(); i++) {
            System.out.println("       #" + (i + 1) + " " + sorted.get(i).password);
        }

        String top3 = sorted.get(0).password + "," + sorted.get(1).password
                + "," + sorted.get(2).password;
        ok("含 SSID 的口令进前 3", top3.contains("B209123"), top3);
        ok("含 MAC 尾号的口令进前 4",
                top3.contains("112202") || sorted.get(3).password.equals("112202"),
                top3 + "," + sorted.get(3).password);
        ok("高频弱口令 12345678 进前 4",
                top3.contains("12345678") || sorted.get(3).password.equals("12345678"),
                top3 + "," + sorted.get(3).password);
        ok("无关口令排在最后",
                sorted.get(sorted.size() - 1).password.equals("aardvark")
                        || sorted.get(sorted.size() - 1).password.equals("zzzzzzzzzz")
                        || sorted.get(sorted.size() - 1).password.equals("randompass"),
                sorted.get(sorted.size() - 1).password);

        System.out.println("== limit 生效 ==");
        List<Candidate> top3only = SmartSorter.prioritize(target, dict, 3);
        ok("取前 3 条", top3only.size() == 3, "" + top3only.size());
        ok("与全排前 3 一致",
                top3only.get(0).password.equals(sorted.get(0).password)
                        && top3only.get(2).password.equals(sorted.get(2).password), "");

        System.out.println("== 无目标时也稳定 ==");
        List<Candidate> noTarget = SmartSorter.prioritize(null, dict, 0);
        ok("无目标不崩", noTarget.size() == dict.size(), "" + noTarget.size());
        ok("无目标时高频弱口令仍靠前",
                noTarget.get(0).password.equals("12345678"),
                noTarget.get(0).password);

        System.out.println("== 空字典 ==");
        ok("空输入返回空", SmartSorter.prioritize(target, new ArrayList<>(), 0).isEmpty(), "");
        ok("null 输入返回空", SmartSorter.prioritize(target, null, 0).isEmpty(), "");

        System.out.println("== 大字典性能 ==");
        DictGenerator g = new DictGenerator(100000).classic().numericPatterns()
                .phoneSegments().wordVariants().wordDigits();
        List<Candidate> big = g.build();
        long t0 = System.currentTimeMillis();
        List<Candidate> sortedBig = SmartSorter.prioritize(target, big, 0);
        long dt = System.currentTimeMillis() - t0;
        System.out.println("     " + big.size() + " 条排序耗时 " + dt + "ms");
        ok("排序耗时 < 2000ms", dt < 2000, dt + "ms");
        ok("大字典条数不变", sortedBig.size() == big.size(),
                big.size() + " -> " + sortedBig.size());

        System.out.println("== 描述文案 ==");
        String desc = SmartSorter.describe(target);
        ok("描述含 SSID", desc.contains("B209"), desc);
        System.out.println("     " + desc);

        System.out.println();
        System.out.println("=== 排序测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
