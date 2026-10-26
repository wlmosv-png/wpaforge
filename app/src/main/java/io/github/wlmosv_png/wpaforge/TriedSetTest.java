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

        System.out.println("== 线索档案：按目标、旧口令不继承 ==");
        SeedModel tpl = new SeedModel();
        SeedModel.addPhone(tpl, "13800000001");
        SeedModel.addName(tpl, "zhangwei");
        tpl.birthday = "19900101";
        tpl.houseNo = "802";
        tpl.landline = "051288889999";
        tpl.oldPassword = "wojiadewangluo2023";
        SeedModel.addCustom(tpl, "ceshiyixia");
        tpl.useSsid = true;

        SeedModel inher = ProfileStore.inherit(tpl);
        ok("继承：手机号", inher.phones.equals(tpl.phones), inher.phones.toString());
        ok("继承：拼音", inher.names.equals(tpl.names), inher.names.toString());
        ok("继承：生日/门牌/宽带", inher.birthday.equals("19900101")
                && inher.houseNo.equals("802") && inher.landline.equals("051288889999"), "");
        ok("继承：自由词", inher.customs.equals(tpl.customs), "");
        ok("继承：SSID 开关", inher.useSsid, "");
        ok("**旧口令不继承**", inher.oldPassword.isEmpty(), inher.oldPassword);

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
