package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ProfileStore;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.TriedSet;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 已试集合与线索档案自检。
 *
 * 盯四件事：
 *  1. 「跑过的候选不再跑第二遍」—— 加进去、查得到、存下来、读回来还查得到；
 *  2. **损坏/丢失只降级成"重复劳动"，绝不崩、绝不改变结论**；
 *  3. 目标键 BSSID 优先（SSID 会重名）；
 *  4. 线索档案按目标分开，且**旧口令永不从默认模板继承**。
 */
public class TriedSetTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "wpf-tried-test");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();

        System.out.println("== 哈希与查找 ==");
        ok("同串同哈希", TriedSet.hash("12345678") == TriedSet.hash("12345678"), "");
        ok("异串异哈希", TriedSet.hash("12345678") != TriedSet.hash("12345679"), "");
        ok("空串可哈希", TriedSet.hash("") == TriedSet.hash(""), "");

        TriedSet t = new TriedSet();
        ok("未加过 → contains=false", !t.contains("12345678"), "");
        ok("add 返回 true", t.add("12345678"), "");
        ok("加过 → contains=true", t.contains("12345678"), "");
        ok("重复 add 返回 false", !t.add("12345678"), "");
        ok("size 不重复计数", t.size() == 1, "" + t.size());

        // 乱序加很多条，验证排序后二分查找仍然对
        List<String> pws = new ArrayList<>();
        for (int i = 0; i < 500; i++) pws.add("pw-" + (i * 7919 % 500) + "-random");
        for (String p : pws) t.add(p);
        boolean allFound = true;
        for (String p : pws) if (!t.contains(p)) { allFound = false; break; }
        ok("乱序写入 500 条后全部可查", allFound, "");
        ok("未加过的查不到", !t.contains("definitely-not-added-xyz"), "");

        System.out.println("== 落盘与读回 ==");
        File f = new File(tmp, "b-aabbccddeeff.tried");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        ok("保存成功", t.save(f), "");
        ok("文件存在且非空", f.isFile() && f.length() > 0, "");
        ok("文件长度是 8 的整数倍（定长记录）", f.length() % 8 == 0, "" + f.length());
        TriedSet back = TriedSet.load(f);
        ok("读回条数一致", back.size() == t.size(), back.size() + " vs " + t.size());
        boolean backFound = true;
        for (String p : pws) if (!back.contains(p)) { backFound = false; break; }
        ok("读回后全部可查（跨字典不重复的关键）", backFound, "");
        ok("读回来的是干净状态", !back.isDirty(), "");

        System.out.println("== 损坏只降级、不崩 ==");
        File bad = new File(tmp, "bad.tried");
        try (FileOutputStream o = new FileOutputStream(bad)) {
            o.write(new byte[]{1, 2, 3});          // 长度不是 8 的倍数
        }
        ok("长度不对 → 空集合", TriedSet.load(bad).size() == 0, "");
        try (FileOutputStream o = new FileOutputStream(bad)) {
            o.write(new byte[16 * 1024]);          // 全是 0，合法长度
        }
        ok("全零文件不崩", TriedSet.load(bad) != null, "");
        ok("不存在的文件 → 空集合", TriedSet.load(new File(tmp, "nope.tried")).size() == 0, "");
        ok("目录当文件 → 空集合", TriedSet.load(tmp).size() == 0, "");
        File empty = new File(tmp, "empty.tried");
        //noinspection ResultOfMethodCallIgnored
        empty.createNewFile();
        ok("空文件 → 空集合", TriedSet.load(empty).size() == 0, "");

        System.out.println("== 目标键：BSSID 优先 ==");
        String k1 = TriedSet.targetKey("AA:BB:CC:DD:EE:FF", "某网");
        String k2 = TriedSet.targetKey("aabbccddeeff", "别的名字");
        ok("大小写与分隔符归一", k1.equals(k2), k1 + " / " + k2);
        String k3 = TriedSet.targetKey("", "同SSID的两个网");
        String k4 = TriedSet.targetKey(null, "同SSID的两个网");
        ok("BSSID 缺失时退化成 SSID", k3.equals(k4) && k3.startsWith("s"), k3);
        ok("不同 SSID 得到不同键",
                !TriedSet.targetKey("", "A").equals(TriedSet.targetKey("", "B")), "");
        ok("缺目标不崩", "unknown".equals(TriedSet.targetKey("", ""))
                || TriedSet.targetKey("", "").startsWith("u"), TriedSet.targetKey("", ""));
        ok("BSSID 优先于 SSID", k1.equals(TriedSet.targetKey("AA:BB:CC:DD:EE:FF", "任意名")), "");

        System.out.println("== 过滤计数（不重复劳动的量化）==");
        TriedSet small = new TriedSet();
        small.add("12345678");
        small.add("88888888");
        small.add("admin123");
        int skipped = TriedSet.filterCount(
                Arrays.asList("12345678", "abc12345", "88888888", "admin123", "qwertyuiop"),
                small);
        ok("5 条里认出 3 条已试过", skipped == 3, "" + skipped);
        ok("空集合 → 一条都不跳", TriedSet.filterCount(
                Arrays.asList("a1234567", "b1234567"), new TriedSet()) == 0, "");

        System.out.println("== 线索档案：只有本目标一种来源 ==");
        // 用户砍掉了"默认模板"：原话「每个 wifi 都是不一样的，你把这个设计了有啥用」。
        // 所以这里只钉三件事：没处理过=空、清空过=空且不可复活、有档案=用自己的。
        ProfileStore.Pick n1 = ProfileStore.decide(null);
        ok("从没处理过 → 空", n1.seeds.isEmpty(), n1.seeds.masked());
        ok("从没处理过 → hasOwn=false", !n1.hasOwn, "");

        // ★ 用户报的 bug：「清空之后点开又出现以前输入的线索」。
        //   现在清空写的是"空档案"，而 decide(空档案) 必须仍然是空。
        ProfileStore.Pick n2 = ProfileStore.decide(new SeedModel());
        ok("**清空过的目标 → 打开仍是空**", n2.seeds.isEmpty(), n2.seeds.masked());
        ok("清空过的目标 → hasOwn=true（持久决定）", n2.hasOwn, "");

        SeedModel mine = new SeedModel();
        SeedModel.addPhone(mine, "13900000002");
        mine.oldPassword = "only-for-this-net";
        ProfileStore.Pick n3 = ProfileStore.decide(mine);
        ok("有本目标档案 → 用本目标的",
                n3.hasOwn && n3.seeds.oldPassword.equals("only-for-this-net"), "");
        ok("本目标档案里没有别的网的东西",
                n3.seeds.phones.size() == 1 && n3.seeds.phones.get(0).equals("13900000002"), "");

        System.out.println("== 档案编解码与目标键共用 ==");
        SeedModel one = new SeedModel();
        SeedModel.addPhone(one, "13900000002");
        one.oldPassword = "only-for-this-net";
        SeedModel rt = SeedModel.decode(one.encode());
        ok("档案往返：手机号", rt.phones.equals(one.phones), "");
        ok("档案往返：旧口令", rt.oldPassword.equals("only-for-this-net"), "");
        ok("档案键与已试集合键同源（同一 BSSID 必得同一键）",
                ProfileStore.key("AA:BB:CC:11:22:33", "x")
                        .equals(TriedSet.targetKey("aa-bb-cc-11-22-33", "y")), "");

        System.out.println("== 上限提示字段 ==");
        TriedSet big = new TriedSet();
        ok("初始未溢出", !big.isOverflow(), "");
        ok("新集合不是脏的", !big.isDirty(), "");
        big.add("whatever123");
        ok("加过之后是脏的（需要落盘）", big.isDirty(), "");
        big.clear();
        ok("清空后条数为 0", big.size() == 0, "");

        System.out.println();
        System.out.println("=== 已试集合/线索档案测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
