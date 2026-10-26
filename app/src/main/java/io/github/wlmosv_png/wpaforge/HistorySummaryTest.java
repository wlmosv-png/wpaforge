package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.HistStore;

import java.util.HashMap;
import java.util.Map;

/**
 * 历史统计的「不解密」契约。
 *
 * <p>为什么单独测：说明弹窗曾经卡一下 —— 原因是统计走了
 * {@code HistStore.load()}，而它对每条命中记录都会做一次
 * Android Keystore 解密（用户 107 条命中 = 200+ 次 Keystore 操作）。
 * 现在统计走 {@link HistStore#foldLine}，**只读前 8 列**。
 *
 * <p>这类"性能假设"最容易在后续改动里被悄悄破坏（有人顺手读第 10 列拿明文），
 * 所以把它钉成测试：行里给的密文是**无法解密的垃圾**，
 * 只要统计结果正确，就证明它根本没去解密。
 */
public class HistorySummaryTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static HistStore.Summary run(String[] lines) {
        int[] tally = new int[2];
        Map<String, Integer> per = new HashMap<>();
        for (String l : lines) HistStore.foldLine(l, tally, per);
        return HistStore.build(tally, per);
    }

    public static void main(String[] a) {
        System.out.println("== 统计只读前 8 列（不解密）==");
        // 新格式 10 列，第 10 列塞一段**假密文**：真去解密只会拿到 null / 抛错，
        // 但统计结果不受影响 —— 这就证明统计路径根本没碰它。
        String hit1 = "1791690201830,B214,28:93:7D:44:95:A9,01 内置弱口令常用,13255,102,137907,1,"
                + "#ef797c(len=8),NOT-A-REAL-SEALED-VALUE-@@@";
        String miss = "1791690201846,B217,28:93:7D:45:DA:31,01 内置弱口令常用,13255,102,137907,0,,"
                + "ANOTHER-BOGUS-SEAL";
        HistStore.Summary s = run(new String[]{hit1, miss});
        ok("runs 计数", s.runs == 2, "" + s.runs);
        ok("hits 计数", s.hits == 1, "" + s.hits);
        ok("同一本字典命中 1 次（样本不足 → topDict 为空）", s.topDict.isEmpty(),
                s.topDict + " / " + s.topDictHits);

        System.out.println("== topDict 至少 3 次才给（避免样本误导）==");
        HistStore.Summary s3 = run(new String[]{hit1, hit1, hit1});
        ok("命中 3 次 → 给出 topDict", "01 内置弱口令常用".equals(s3.topDict), s3.topDict);
        ok("topDictHits 正确", s3.topDictHits == 3, "" + s3.topDictHits);

        System.out.println("== 最短列数 ==");
        ok("只有 7 列 → 忽略（不到 hit 标志）", run(new String[]{
                "1791690201830,B214,28:93:7D:44:95:A9,dict,1,2,3"}).runs == 0, "");
        ok("恰好 8 列 → 计入（旧格式最少就是 8 列）", run(new String[]{
                "1,B,AA:BB:CC:DD:EE:FF,dict,1,2,3,0"}).runs == 1, "");
        ok("空行/null 不崩", run(new String[]{"", null}).runs == 0, "");

        System.out.println("== 字典名里的逗号按转义处理 ==");
        // 写盘时逗号被转义成 \c，所以按逗号切列是安全的
        HistStore.Summary sc = run(new String[]{
                "1,B,AA:BB:CC:DD:EE:FF,wo\\cde\\cmingzi,1,2,3,1,x,y",
                "2,B,AA:BB:CC:DD:EE:FF,wo\\cde\\cmingzi,1,2,3,1,x,y",
                "3,B,AA:BB:CC:DD:EE:FF,wo\\cde\\cmingzi,1,2,3,1,x,y"});
        ok("含逗号的字典名被正确还原", "wo,de,mingzi".equals(sc.topDict), sc.topDict);
        ok("列数没错位（runs=3）", sc.runs == 3, "" + sc.runs);

        System.out.println();
        System.out.println("=== 历史统计契约测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
