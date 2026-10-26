package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.CnHot;
import io.github.wlmosv_png.wpaforge.audit.SeedDeriver;
import io.github.wlmosv_png.wpaforge.audit.SeedModel;
import io.github.wlmosv_png.wpaforge.audit.SmartSorter;
import io.github.wlmosv_png.wpaforge.audit.TargetDictBuilder;
import io.github.wlmosv_png.wpaforge.core.NetRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 画像派生自检（v2，2026-10-26 推倒重写）。
 *
 * 盯四件事：
 *  1. 画像只生"人会设的形态" —— 手机号只有 全号/后8/1+后8，不做子串手术；
 *  2. 长度闸门生效 —— 短于 8 位直接丢，**绝不补位**（v1 的 00408514 那类必须绝迹）；
 *  3. 预算封顶 —— 实测 3.6 秒/条，候选必须收在 120 以内；
 *  4. SSID 派生默认关闭。
 */
public class SeedDeriveTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    private static boolean has(List<Candidate> list, String pw) {
        for (Candidate c : list) if (c.password.equals(pw)) return true;
        return false;
    }

    private static int indexOf(List<Candidate> list, String pw) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).password.equals(pw)) return i;
        return -1;
    }

    private static boolean hasValue(SeedModel m, SeedDeriver.Tier tier, String v) {
        for (SeedDeriver.Item it : SeedDeriver.profile(m)) {
            if (it.tier == tier && it.value.equals(v)) return true;
        }
        return false;
    }

    private static NetRecord target(String ssid, String bssid) {
        return new NetRecord(ssid, bssid, -50, 2432, NetRecord.Security.WPA2, false, "");
    }

    /** 用户实测的那个号 + 那个 SSID，作为验收基线 */
    private static SeedModel phoneSeed() {
        SeedModel m = new SeedModel();
        SeedModel.addPhone(m, "19395408514");
        return m;
    }

    public static void main(String[] a) {
        System.out.println("== SeedModel 校验与序列化 ==");
        SeedModel m = new SeedModel();
        ok("11 位手机号可加", SeedModel.addPhone(m, "13812345678"), "");
        ok("整个号重复被忽略", SeedModel.addPhone(m, "138-1234-5678") == false, "");
        ok("非 1 开头拒绝", SeedModel.addPhone(m, "23812345678") == false, "");
        SeedModel m2 = new SeedModel();
        SeedModel.addPhone(m2, "13800000001");
        SeedModel.addPhone(m2, "13800000002");
        SeedModel.addPhone(m2, "13800000003");
        SeedModel.addPhone(m2, "13800000004");
        ok("手机号上限 4", SeedModel.addPhone(m2, "13800000005") == false, "");
        SeedModel m3 = new SeedModel();
        ok("拼音只留字母并转小写", SeedModel.addName(m3, "Zhang Wei123") && m3.names.get(0).equals("zhangwei"), m3.names.toString());
        ok("单字母拼音拒绝", SeedModel.addName(m3, "z") == false, "");
        SeedModel.addName(m3, "wangwei");
        SeedModel.addName(m3, "lisi");
        SeedModel.addName(m3, "xiaobao");
        ok("拼音上限 4", SeedModel.addName(m3, "dabao") == false && SeedModel.addName(m3, "extra") == false, m3.names.toString());

        SeedModel full = new SeedModel();
        SeedModel.addPhone(full, "19395408514");
        SeedModel.addName(full, "zhangwei");
        full.birthday = "19900101";
        full.houseNo = "802";
        full.landline = "051288889999";
        full.oldPassword = "oldpass2024";
        SeedModel.addCustom(full, "特§殊=值,%号");
        full.useSsid = true;
        SeedModel back = SeedModel.decode(full.encode());
        ok("往返：手机号", back.phones.equals(full.phones), back.phones.toString());
        ok("往返：拼音列表", back.names.equals(full.names), back.names.toString());
        ok("往返：生日/门牌/宽带", back.birthday.equals("19900101")
                && back.houseNo.equals("802") && back.landline.equals("051288889999"), "");
        ok("往返：旧口令", back.oldPassword.equals("oldpass2024"), back.oldPassword);
        ok("往返：带特殊字符的自由词", back.customs.equals(full.customs), back.customs.toString());
        ok("往返：SSID 开关", back.useSsid, "");
        ok("老格式 pinyin= 兼容读成 names",
                SeedModel.decode("pinyin=zhangwei").names.contains("zhangwei"), "");
        ok("损坏串不崩", SeedModel.decode("§§===|乱码").isEmpty(), "");
        String fp = full.fingerprint();
        full.houseNo = "803";
        ok("内容变则指纹变", !fp.equals(full.fingerprint()), "");
        full.houseNo = "802";
        ok("内容还原则指纹还原", fp.equals(full.fingerprint()), "");
        String masked = full.masked();
        ok("掩码不含完整手机号", !masked.contains("19395408514"), masked);
        ok("掩码不含旧口令原文", !masked.contains("oldpass2024"), masked);
        ok("掩码不含自由词原文", !masked.contains("号"), masked);

        System.out.println("== 画像派生：只生成人会设的形态 ==");
        SeedModel p = phoneSeed();
        List<SeedDeriver.Item> items = SeedDeriver.profile(p);
        ok("手机号全号进原值", hasValue(p, SeedDeriver.Tier.VALUE, "19395408514"), "");
        ok("手机号后 8 位进原值", hasValue(p, SeedDeriver.Tier.VALUE, "95408514"), "");
        ok("1+后 8 位（9 位）进原值", hasValue(p, SeedDeriver.Tier.VALUE, "195408514"), "");
        ok("后 6 位（408514）**不**进原值", !hasValue(p, SeedDeriver.Tier.VALUE, "408514"), "");
        ok("后 6 位也不在任何一层", !hasValue(p, SeedDeriver.Tier.COMBO, "408514")
                && !hasValue(p, SeedDeriver.Tier.MANGLE, "408514"), "");
        int valueCount = 0;
        for (SeedDeriver.Item it : items) if (it.tier == SeedDeriver.Tier.VALUE) valueCount++;
        ok("纯手机号的画像 ≤5 条", valueCount <= 5, "" + valueCount);

        SeedModel pn = new SeedModel();
        SeedModel.addName(pn, "zhangwei");
        SeedModel.addName(pn, "xiaobao");
        pn.birthday = "19900101";
        pn.houseNo = "802";
        List<SeedDeriver.Item> it2 = SeedDeriver.profile(pn);
        ok("zhangwei（8 位）可单独当原值", hasValue(pn, SeedDeriver.Tier.VALUE, "zhangwei"), "");
        ok("首字母 zw 只进互拼/追加", !hasValue(pn, SeedDeriver.Tier.VALUE, "zw"), "");
        ok("互拼：拼音+手机后四位", hasValue(pn, SeedDeriver.Tier.COMBO, "zhangwei8514")
                || hasValue(pn, SeedDeriver.Tier.COMBO, "zhangwei0101"), "");
        ok("互拼：拼音+门牌", hasValue(pn, SeedDeriver.Tier.COMBO, "zhangwei802"), "");
        ok("互拼：两名字拼接", hasValue(pn, SeedDeriver.Tier.COMBO, "zhangweixiaobao"), "");
        ok("互拼上限 24", countTier(pn, SeedDeriver.Tier.COMBO) <= SeedDeriver.MAX_COMBO,
                "" + countTier(pn, SeedDeriver.Tier.COMBO));
        ok("追加上限 20", countTier(pn, SeedDeriver.Tier.MANGLE) <= SeedDeriver.MAX_MANGLE,
                "" + countTier(pn, SeedDeriver.Tier.MANGLE));
        ok("追加含 +1 / +123 / +520", hasValue(pn, SeedDeriver.Tier.MANGLE, "zhangwei1")
                && hasValue(pn, SeedDeriver.Tier.MANGLE, "zhangwei123")
                && hasValue(pn, SeedDeriver.Tier.MANGLE, "zhangwei520"), "");

        ok("短拼音（wangwei=7）不单独当原值",
                !hasValue(newSeedName("wangwei"), SeedDeriver.Tier.VALUE, "wangwei"), "");
        ok("首字母 zhangwei→zw", "zw".equals(SeedDeriver.initials("zhangwei")), SeedDeriver.initials("zhangwei"));
        ok("首字母 wangxiaoming→wxm", "wxm".equals(SeedDeriver.initials("wangxiaoming")), "");
        ok("切不出音节就返回空", "".equals(SeedDeriver.initials("qwertyui")), "");
        ok("空画像不产生候选", SeedDeriver.profile(new SeedModel()).isEmpty(), "");
        ok("null 画像不崩", SeedDeriver.profile(null).isEmpty(), "");

        System.out.println("== 派生链：闸门与预算（验收基线）==");
        NetRecord b209 = target("B209", "AA:BB:CC:55:D3:25");
        TargetDictBuilder.Result r = TargetDictBuilder.build(b209, 120, null, p, null);
        System.out.println("     生成 " + r.candidates.size() + " 条，来源: " + r.explanation);
        ok("① 总数 ≤ 120", r.candidates.size() <= 120, "" + r.candidates.size());
        ok("② 旧版的补位垃圾一条不剩",
                !has(r.candidates, "00408514") && !has(r.candidates, "40851440")
                        && !has(r.candidates, "40851400") && !has(r.candidates, "40851488")
                        && !has(r.candidates, "40851411") && !has(r.candidates, "9540851495408514")
                        && !has(r.candidates, "408514"), "");
        ok("③ 前 10 条含画像原值（全号）",
                indexOf(r.candidates, "19395408514") >= 0 && indexOf(r.candidates, "19395408514") < 10,
                "idx=" + indexOf(r.candidates, "19395408514"));
        ok("③ 前 10 条含画像原值（后 8 位）",
                indexOf(r.candidates, "95408514") >= 0 && indexOf(r.candidates, "95408514") < 10,
                "idx=" + indexOf(r.candidates, "95408514"));
        ok("③ 前 10 条含通用弱口令头部",
                indexOf(r.candidates, "12345678") >= 0 && indexOf(r.candidates, "12345678") < 10,
                "idx=" + indexOf(r.candidates, "12345678"));
        ok("画像原值排在通用弱口令之前",
                indexOf(r.candidates, "19395408514") < indexOf(r.candidates, "12345678"), "");
        ok("SSID 派生默认不进（无 B209B209）", !has(r.candidates, "B209B209"), r.explanation);

        System.out.println("== 社会工程学审查回归（都是实测抓出来的）==");
        {
            // ① 长拼音原值曾被长度闸门误杀：laopoxingwang（13 位混排）
            SeedModel sw = new SeedModel();
            SeedModel.addCustom(sw, "laopoxingwang");
            TargetDictBuilder.Result rw = TargetDictBuilder.build(b209, 200, null, sw, null);
            ok("13 位拼音自由词保留（曾被闸门误杀）", has(rw.candidates, "laopoxingwang"), "");
            ok("拼音 + 后缀也保留", has(rw.candidates, "laopoxingwang1314"), "");

            // ② 宽带账号（12 位纯数字）曾被误杀
            SeedModel sl = new SeedModel();
            sl.landline = "051288889999";
            TargetDictBuilder.Result rl = TargetDictBuilder.build(b209, 200, null, sl, null);
            ok("12 位宽带账号保留（曾被闸门误杀）", has(rl.candidates, "051288889999"), "");

            // ③ 但"宽带账号 + 月日" = 16 位纯数字是**没人会设**的堆砌，必须挡住
            SeedModel sb = new SeedModel();
            sb.landline = "051288889999";
            sb.birthday = "19900101";
            TargetDictBuilder.Result rb = TargetDictBuilder.build(b209, 300, null, sb, null);
            ok("16 位纯数字组合被挡（无脑延伸）", !has(rb.candidates, "0512888899990101"), "");

            // ④ 旧口令：换年份/换尾数是合理微调
            SeedModel so = new SeedModel();
            so.oldPassword = "zhangwei2023";
            TargetDictBuilder.Result ro = TargetDictBuilder.build(b209, 300, null, so, null);
            ok("旧口令原值排第一", ro.candidates.get(0).password.equals("zhangwei2023"),
                    ro.candidates.get(0).password);
            ok("旧口令换年份（zhangwei2024）", has(ro.candidates, "zhangwei2024"), "");
            ok("旧口令换尾数（zhangwei520）", has(ro.candidates, "zhangwei520"), "");
            // 而"把旧口令当词根再追加"是堆砌，必须不出现
            ok("不再把旧口令当词根堆砌（无 zhangwei20231234）",
                    !has(ro.candidates, "zhangwei20231234"), "");
            ok("也不再有 zhangwei20231314", !has(ro.candidates, "zhangwei20231314"), "");
        }

        System.out.println("== 闸门：长度与形态 ==");
        // 上限经"社会工程学审查"放宽过（原 纯数字≤11 / 混排≤12 会误杀
        // 宽带账号和拼音全名），这里按**新契约**钉边界。
        SeedModel longSeed = new SeedModel();
        SeedModel.addCustom(longSeed, "123456789012");       // 12 位纯数字（宽带账号级）
        SeedModel.addCustom(longSeed, "abcdefgh12345");      // 13 位混排（拼音级）
        SeedModel.addCustom(longSeed, "abc12345");           // 8 位
        SeedModel.addCustom(longSeed, "12345678901234567");  // 17 位纯数字 → 超限
        SeedModel.addCustom(longSeed, "abcdefghijklmnopqrstuvwxy");   // 25 位混排 → 超限
        TargetDictBuilder.Result rg = TargetDictBuilder.build(b209, 300, null, longSeed, null);
        ok("12 位纯数字保留（宽带账号级）", has(rg.candidates, "123456789012"), "");
        ok("13 位混排保留（拼音级）", has(rg.candidates, "abcdefgh12345"), "");
        ok("17 位纯数字被丢（超上限）", !has(rg.candidates, "12345678901234567"), "");
        ok("25 位混排被丢（超上限）", !has(rg.candidates, "abcdefghijklmnopqrstuvwxy"), "");
        ok("8 位合法保留", has(rg.candidates, "abc12345"), "");
        SeedModel shortSeed = new SeedModel();
        SeedModel.addCustom(shortSeed, "abc123");           // 6 位
        TargetDictBuilder.Result rs = TargetDictBuilder.build(b209, 200, null, shortSeed, null);
        // 注意区分：v1 那种"补位"产物（+00 / +11 / 前缀 0）必须绝迹；
        // 而 +88 属于画像追加层的正常规则，不在禁止之列。
        ok("6 位短串本身被丢", !has(rs.candidates, "abc123"), "");
        ok("v1 的补位产物绝迹", !has(rs.candidates, "abc12300") && !has(rs.candidates, "abc12311")
                && !has(rs.candidates, "0abc123") && !has(rs.candidates, "abc123ab"), "");

        System.out.println("== SSID 派生：开关行为 ==");
        NetRecord fast = target("FAST_E972", "AA:BB:CC:E9:72:11");
        TargetDictBuilder.Result off = TargetDictBuilder.build(fast, 200, null, null, null);
        ok("关闭时不含 SSID 原文", !has(off.candidates, "FAST_E972"), off.explanation);
        ok("关闭时来源写明", off.explanation.contains("关") || off.explanation.contains("off"),
                off.explanation);
        SeedModel ssidOn = new SeedModel();
        ssidOn.useSsid = true;
        TargetDictBuilder.Result on = TargetDictBuilder.build(fast, 200, null, ssidOn, null);
        ok("打开时含 SSID 原文", has(on.candidates, "FAST_E972"), "");
        ok("SSID 候选排在通用弱口令之后",
                indexOf(on.candidates, "12345678") < indexOf(on.candidates, "FAST_E972"), "");

        System.out.println("== 中文字表 ==");
        List<String> parsed = CnHot.parse(Arrays.asList("# 注释", "", "12345678", "短", "12345678", "zhang1234"));
        ok("注释空行与重复被清理", parsed.size() == 2, parsed.toString());
        List<String> cn = Arrays.asList("5201314520", "woaini1314", "zhangwei520");
        TargetDictBuilder.Result rc = TargetDictBuilder.build(b209, 200, null, null, cn);
        ok("中文高频进入候选", has(rc.candidates, "woaini1314"), "");
        ok("来源里标出中文高频", rc.explanation.contains("中文高频"), rc.explanation);

        System.out.println("== 排序：画像优先 ==");
        SeedModel sp = new SeedModel();
        SeedModel.addPhone(sp, "13900001111");
        List<Candidate> poolDict = new ArrayList<>();
        poolDict.add(new Candidate("zzzzzzzz", Candidate.Origin.USER_DICT));
        poolDict.add(new Candidate("88888888", Candidate.Origin.DIGITS));
        poolDict.add(new Candidate("13900001111", Candidate.Origin.USER_SEED));
        List<Candidate> ranked = SmartSorter.prioritize(b209, poolDict, 0, sp, null);
        ok("画像命中排第一", "13900001111".equals(ranked.get(0).password), ranked.get(0).password);
        ok("无线索时旧签名可用", SmartSorter.prioritize(b209, poolDict, 0).size() == 3, "");

        System.out.println("== 边界 ==");
        ok("null 目标返回空", TargetDictBuilder.build(null, 100).candidates.isEmpty(), "");
        TargetDictBuilder.Result tiny = TargetDictBuilder.build(b209, 3, null, p, null);
        ok("预算 3 生效", tiny.candidates.size() <= 3, "" + tiny.candidates.size());
        boolean lenOk = true;
        for (Candidate c : r.candidates) {
            if (c.password.length() < 8 || c.password.length() > 63) lenOk = false;
        }
        ok("所有条目长度合法", lenOk, "");

        System.out.println();
        System.out.println("=== 画像派生测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static SeedModel newSeedName(String n) {
        SeedModel m = new SeedModel();
        SeedModel.addName(m, n);
        return m;
    }

    private static int countTier(SeedModel m, SeedDeriver.Tier t) {
        int n = 0;
        for (SeedDeriver.Item it : SeedDeriver.profile(m)) if (it.tier == t) n++;
        return n;
    }
}
