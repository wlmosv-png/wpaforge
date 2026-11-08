package io.github.wlmosv_png.wpaforge.wps;

/**
 * 断点续跑：编码往返、归属/策略拒绝、阶段 2 缺前缀、跨阶段保留阶段 1 断点。
 *
 * <h3>要挡住的事故</h3>
 * 断点最危险的失败模式不是「读不出来」（那最多从头跑），而是**读出来了但不是这个目标的**：
 * 用户在 A 路由器上跑到 4000 条，回家在 B 路由器上点继续，程序把 4000 的游标当成 B 的进度
 * —— 白跑 4000 条，而且用户以为程序记得住。所以归属判据（BSSID + 策略，
 * SSID 只在两侧都知道时才比）与「不匹配必须给人话原因」是本测试的重点。
 *
 * <p>全部断言调用生产类，不复制任何生产逻辑。
 */
public final class WpsCheckpointResumeTest {

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

    private static void info(String s) {
        System.out.println("  ·    " + s);
    }

    /** 内存 Blob：与 SharedPreferences 语义一致，走同一段生产逻辑。 */
    private static final class MemBlob implements WpsCheckpointStore.Blob {
        final java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();

        public String read(String key) {
            return map.get(key);
        }

        public boolean write(String key, String value) {
            map.put(key, value);
            return true;
        }

        public boolean remove(String key) {
            return map.remove(key) != null;
        }
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 断点续跑 ===");

        info("① encode / decode 往返（SSID 带空格）");
        WpsCheckpoint c = new WpsCheckpoint();
        c.taskId = "wps-2233-1699999999999";
        c.bssid = "a4:2b:b0:11:22:33";
        c.ssid = "My Home AP";
        c.mode = 3;
        c.phase = 2;
        c.cursor = 42;
        c.cursor1 = 9876;
        c.prefix = "1234";
        c.updatedAtMs = 1699999999999L;

        String txt = c.encode();
        info("    首行：" + txt.split("\n")[0]);
        ok("首行是版本行", txt.startsWith("WPS_CP v=1"), txt.split("\n")[0]);
        ok("编码后不含原始空格（值已编码）", !txt.contains("My Home AP"), "");
        ok("单行文本不含换行以外的结构破坏", txt.endsWith("\n"), "");

        WpsCheckpoint.Parse p = WpsCheckpoint.decode(txt);
        ok("decode 成功", p.ok(), p.error);
        ok("成功时 error 为空", p.error.isEmpty(), p.error);
        ok("成功时 note 为空", p.note.isEmpty(), p.note);
        ok("fromLegacy=false", !p.fromLegacy, "");
        WpsCheckpoint d = p.cp;
        ok("taskId 往返", "wps-2233-1699999999999".equals(d.taskId), d.taskId);
        ok("bssid 往返", "a4:2b:b0:11:22:33".equals(d.bssid), d.bssid);
        ok("带空格 SSID 往返", "My Home AP".equals(d.ssid), "[" + d.ssid + "]");
        ok("mode 往返", d.mode == 3, "" + d.mode);
        ok("phase 往返", d.phase == 2, "" + d.phase);
        ok("cursor 往返", d.cursor == 42, "" + d.cursor);
        ok("cursor1 往返", d.cursor1 == 9876, "" + d.cursor1);
        ok("prefix 往返", "1234".equals(d.prefix), d.prefix);
        ok("updatedAtMs 往返", d.updatedAtMs == 1699999999999L, "" + d.updatedAtMs);
        ok("schema 往返", d.schema == WpsCheckpoint.SCHEMA, "" + d.schema);

        info("② 值里的 = 与 + 不得破坏结构");
        WpsCheckpoint c2 = new WpsCheckpoint();
        c2.bssid = "b2:08:aa:bb:cc:dd";
        c2.ssid = "a=b+c 中文 网络";
        c2.phase = 2;
        c2.prefix = "0000";
        c2.cursor = 3;
        WpsCheckpoint.Parse p2 = WpsCheckpoint.decode(c2.encode());
        ok("含 = + 空格 中文的 SSID 往返", p2.ok() && "a=b+c 中文 网络".equals(p2.cp.ssid),
                p2.ok() ? "[" + p2.cp.ssid + "]" : p2.error);
        ok("prefix=0000 往返", p2.ok() && "0000".equals(p2.cp.prefix), "");

        info("③ 空字符串字段往返");
        WpsCheckpoint e = new WpsCheckpoint();
        e.bssid = "b2:08:aa:bb:cc:dd";
        e.ssid = "";
        e.prefix = "";
        e.phase = 1;
        e.cursor = 0;
        e.cursor1 = 0;
        WpsCheckpoint.Parse pe = WpsCheckpoint.decode(e.encode());
        ok("空字段往返 → ok", pe.ok(), pe.error);
        ok("空 SSID 仍是空串", pe.ok() && "".equals(pe.cp.ssid), "[" + (pe.ok() ? pe.cp.ssid : "?") + "]");
        ok("空 prefix 仍是空串", pe.ok() && "".equals(pe.cp.prefix), "");
        ok("空 SSID 不报错", pe.ok() && pe.error.isEmpty(), pe.error);

        info("④ copy 独立（改副本不影响原件）");
        WpsCheckpoint cpCopy = c.copy();
        cpCopy.cursor = 999;
        cpCopy.ssid = "改过了";
        ok("copy 后改副本不动原件", c.cursor == 42 && "My Home AP".equals(c.ssid),
                c.cursor + "/" + c.ssid);
        ok("copy 字段一致", cpCopy.taskId.equals(c.taskId) && cpCopy.mode == c.mode, "");

        info("⑤ 归属与策略不匹配 → 必须拒绝，并给人话原因");
        WpsCheckpoint t = new WpsCheckpoint();
        t.bssid = "a4:2b:b0:11:22:33";
        t.ssid = "TARGET-AP";
        t.mode = 3;
        ok("同目标同策略 → 匹配", t.matches("a4:2b:b0:11:22:33", "TARGET-AP", 3), "");
        ok("BSSID 大小写不影响", t.matches("A4:2B:B0:11:22:33", "TARGET-AP", 3), "");
        ok("匹配时 mismatchReason 为空串",
                t.mismatchReason("a4:2b:b0:11:22:33", "TARGET-AP", 3, true).isEmpty(), "");
        ok("另一个 BSSID → 不匹配",
                !t.matches("b2:08:aa:bb:cc:dd", "TARGET-AP", 3), "");
        String rOther = t.mismatchReason("b2:08:aa:bb:cc:dd", "TARGET-AP", 3, true);
        info("    原因：" + rOther);
        ok("原因说「另一个目标」", rOther.contains("另一个目标"), rOther);
        ok("原因带断点目标标签",
                rOther.contains(WpsCheckpoint.tagOf("a4:2b:b0:11:22:33")), rOther);
        ok("原因带当前目标标签",
                rOther.contains(WpsCheckpoint.tagOf("b2:08:aa:bb:cc:dd")), rOther);
        ok("英文原因非空", !t.mismatchReason("b2:08:aa:bb:cc:dd", "TARGET-AP", 3, false).isEmpty(), "");
        ok("策略档位不同 → 不匹配", !t.matches("a4:2b:b0:11:22:33", "TARGET-AP", 1), "");
        ok("策略原因提到档位",
                t.mismatchReason("a4:2b:b0:11:22:33", "TARGET-AP", 1, true).contains("M1"),
                t.mismatchReason("a4:2b:b0:11:22:33", "TARGET-AP", 1, true));
        ok("SSID 不同 → 不匹配", !t.matches("a4:2b:b0:11:22:33", "OTHER-AP", 3), "");
        ok("SSID 不同的原因提到 SSID",
                t.mismatchReason("a4:2b:b0:11:22:33", "OTHER-AP", 3, true).contains("SSID"),
                t.mismatchReason("a4:2b:b0:11:22:33", "OTHER-AP", 3, true));
        ok("目标 SSID 未知（空）→ 只按 bssid+mode 判", t.matches("a4:2b:b0:11:22:33", "", 3), "");
        ok("目标 SSID 为 null → 只按 bssid+mode 判", t.matches("a4:2b:b0:11:22:33", null, 3), "");
        WpsCheckpoint noBssid = new WpsCheckpoint();
        noBssid.ssid = "TARGET-AP";
        ok("断点没记 BSSID → 不匹配", !noBssid.matches("a4:2b:b0:11:22:33", "TARGET-AP", 3), "");
        ok("断点没记 BSSID → 原因可读",
                noBssid.mismatchReason("a4:2b:b0:11:22:33", "TARGET-AP", 3, true).contains("BSSID"),
                noBssid.mismatchReason("a4:2b:b0:11:22:33", "TARGET-AP", 3, true));
        ok("当前目标没 BSSID → 不匹配", !t.matches("", "TARGET-AP", 3), "");

        info("⑥ 阶段 2 缺前 4 位 → 不可续跑");
        WpsCheckpoint bad = new WpsCheckpoint();
        bad.bssid = "a4:2b:b0:11:22:33";
        bad.mode = 3;
        bad.phase = 2;
        bad.prefix = "";
        String ir = bad.invalidReason(true);
        ok("阶段 2 空前缀 → 不可续跑", !ir.isEmpty() && ir.contains("前 4 位"), ir);
        ok("英文原因非空", !bad.invalidReason(false).isEmpty(), "");
        bad.prefix = "123";
        ok("阶段 2 前缀只有 3 位 → 不可续跑", !bad.invalidReason(true).isEmpty(), "");
        bad.prefix = "12345";
        ok("阶段 2 前缀 5 位 → 不可续跑", !bad.invalidReason(true).isEmpty(), "");
        bad.prefix = "1234";
        ok("阶段 2 前缀 4 位 → 可续跑", bad.invalidReason(true).isEmpty(), "");
        WpsCheckpoint p1 = new WpsCheckpoint();
        p1.bssid = "a4:2b:b0:11:22:33";
        p1.phase = 1;
        p1.prefix = "1234";
        ok("阶段 1 带 prefix → 不算错（忽略）", p1.invalidReason(true).isEmpty(),
                p1.invalidReason(true));

        info("⑦ 阶段切换：cursor1 必须保留阶段 1 的断点");
        WpsCheckpoint s1 = new WpsCheckpoint();
        s1.bssid = "a4:2b:b0:11:22:33";
        s1.phase = 1;
        s1.cursor = 4321;
        s1.cursor1 = 0;
        WpsCheckpoint s2 = s1.copy();
        s2.cursor1 = s1.cursor;
        s2.phase = 2;
        s2.prefix = "7788";
        s2.cursor = 0;
        WpsCheckpoint.Parse ps = WpsCheckpoint.decode(s2.encode());
        ok("阶段 2 保留阶段 1 断点", ps.ok() && ps.cp.cursor1 == 4321,
                ps.ok() ? ("" + ps.cp.cursor1) : ps.error);
        ok("阶段 2 自己的游标从 0 开始", ps.ok() && ps.cp.cursor == 0, "");
        ok("阶段 2 前缀保留", ps.ok() && "7788".equals(ps.cp.prefix), "");
        ok("阶段 1 参数改回后仍可续跑", ps.ok() && ps.cp.invalidReason(true).isEmpty(), "");

        info("⑧ 经 store（内存 Blob）走完整 loadFor");
        MemBlob mem = new MemBlob();
        WpsCheckpointStore store = new WpsCheckpointStore(mem);
        WpsCheckpoint cp = new WpsCheckpoint();
        cp.taskId = "wps-2233-1";
        cp.bssid = "a4:2b:b0:11:22:33";
        cp.ssid = "TARGET-AP";
        cp.mode = 3;
        cp.phase = 1;
        cp.cursor = 100;
        ok("save 成功", store.save(cp), "");
        WpsCheckpoint.Parse lf = store.loadFor("a4:2b:b0:11:22:33", "TARGET-AP", 3);
        ok("loadFor 同目标 → ok", lf.ok(), lf.error);
        ok("loadFor 续上实际完成数", lf.ok() && lf.cp.cursor == 100,
                lf.ok() ? ("" + lf.cp.cursor) : lf.error);
        ok("loadFor 保留 taskId", lf.ok() && "wps-2233-1".equals(lf.cp.taskId), "");
        String desc = lf.ok() ? lf.cp.describe(true) : "";
        info("    描述：" + desc);
        ok("describe 含阶段与进度", desc.contains("阶段") && desc.contains("100"), desc);

        WpsCheckpoint.Parse lfOther = store.loadFor("b2:08:aa:bb:cc:dd", "TARGET-AP", 3);
        ok("loadFor 别的目标 → 不 ok", !lfOther.ok(), "");
        ok("loadFor 别的目标 → 有原因（不静默从头）",
                lfOther.error.contains("另一个目标"), lfOther.error);
        ok("loadFor 别的目标 → 不返回断点对象", lfOther.cp == null, "");
        WpsCheckpoint.Parse lfMode = store.loadFor("a4:2b:b0:11:22:33", "TARGET-AP", 1);
        ok("loadFor 策略不同 → 不 ok 且有原因",
                !lfMode.ok() && !lfMode.error.isEmpty(), lfMode.error);
        WpsCheckpoint.Parse lfSsid = store.loadFor("a4:2b:b0:11:22:33", "OTHER-AP", 3);
        ok("loadFor SSID 不同 → 不 ok 且有原因",
                !lfSsid.ok() && lfSsid.error.contains("SSID"), lfSsid.error);

        WpsCheckpoint bad2 = new WpsCheckpoint();
        bad2.bssid = "a4:2b:b0:11:22:33";
        bad2.ssid = "TARGET-AP";
        bad2.mode = 3;
        bad2.phase = 2;
        bad2.prefix = "";
        bad2.cursor = 5;
        ok("存下缺前缀的阶段 2 断点", store.save(bad2), "");
        WpsCheckpoint.Parse lf2 = store.loadFor("a4:2b:b0:11:22:33", "TARGET-AP", 3);
        ok("loadFor 阶段 2 缺前缀 → 不 ok", !lf2.ok(), "");
        ok("loadFor 阶段 2 原因可读", lf2.error.contains("前 4 位"), lf2.error);

        System.out.println();
        System.out.println("=== WpsCheckpointResumeTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
