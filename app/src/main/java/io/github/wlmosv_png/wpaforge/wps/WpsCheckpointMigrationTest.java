package io.github.wlmosv_png.wpaforge.wps;

/**
 * 断点格式演进：旧版 5 键迁移、版本拒绝、缺字段容错。
 *
 * <h3>要挡住的事故</h3>
 * ① 旧版断点直接丢弃 → 用户升级 App 后进度归零（明明数据还在 SharedPreferences 里）。
 * ② 旧版迁移时**不看 BSSID** 就认领 → 这段进度可能属于上一个目标。
 * ③ 新版本写的断点被旧版本当垃圾读 → 必须明确拒绝（说「更新的版本写入的」），
 * 不能猜、也不能当成空断点从头跑。
 * ④ 缺字段整条报废 → 向后兼容的字段增删会让老断点直接失效。
 */
public final class WpsCheckpointMigrationTest {

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

    private static MemBlob legacy(String bssid, String phase, String cursor, String cursor1,
                                 String prefix) {
        MemBlob m = new MemBlob();
        if (bssid != null) m.map.put(WpsCheckpointStore.KEY_LEGACY_BSSID, bssid);
        if (phase != null) m.map.put(WpsCheckpointStore.KEY_LEGACY_PHASE, phase);
        if (cursor != null) m.map.put(WpsCheckpointStore.KEY_LEGACY_CURSOR, cursor);
        if (cursor1 != null) m.map.put(WpsCheckpointStore.KEY_LEGACY_CURSOR1, cursor1);
        if (prefix != null) m.map.put(WpsCheckpointStore.KEY_LEGACY_PREFIX, prefix);
        return m;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 断点迁移与版本演进 ===");

        info("① 只有旧版 5 键 → 迁移成功，且写成新格式落回主副本");
        MemBlob mem = legacy("a4:2b:b0:11:22:33", "1", "1234", "0", "");
        WpsCheckpointStore store = new WpsCheckpointStore(mem);
        WpsCheckpoint.Parse p = store.load();
        ok("旧版可迁移 → ok", p.ok(), p.error);
        ok("fromLegacy=true", p.fromLegacy, "");
        info("    note：" + p.note);
        ok("note 说明来路（含「旧版」）", p.note.contains("旧版"), p.note);
        ok("error 为空", p.error.isEmpty(), p.error);
        ok("bssid 迁移", p.ok() && "a4:2b:b0:11:22:33".equals(p.cp.bssid),
                p.ok() ? p.cp.bssid : p.error);
        ok("cursor 迁移（实际完成数）", p.ok() && p.cp.cursor == 1234,
                p.ok() ? ("" + p.cp.cursor) : p.error);
        ok("phase 迁移", p.ok() && p.cp.phase == 1, "");
        ok("旧版默认拆半档位 M3", p.ok() && p.cp.mode == 3, "");
        ok("旧版 SSID 未知 → 空串", p.ok() && "".equals(p.cp.ssid), "");
        ok("迁移结果可续跑", p.ok() && p.cp.invalidReason(true).isEmpty(), "");

        String written = mem.read(WpsCheckpointStore.KEY_PRIMARY);
        ok("新格式已写回主副本", written != null && !written.isEmpty(), String.valueOf(written));
        ok("写回的是新格式（含版本头）",
                written != null && written.startsWith("WPS_CP v=1"), String.valueOf(written));
        WpsCheckpoint.Parse rew = WpsCheckpoint.decode(written);
        ok("写回内容与迁移结果一致",
                rew.ok() && rew.cp.cursor == 1234 && "a4:2b:b0:11:22:33".equals(rew.cp.bssid), "");

        WpsCheckpoint.Parse p2 = store.load();
        ok("二次 load 不再走旧版", p2.ok() && !p2.fromLegacy, "");
        ok("二次 load note 为空", p2.note.isEmpty(), p2.note);
        ok("二次 load 进度一致", p2.ok() && p2.cp.cursor == 1234, "");

        info("② 旧版缺 BSSID → 拒绝并说明原因（不得静默认领）");
        MemBlob mem2 = legacy(null, "2", "55", "0", "1234");
        WpsCheckpoint.Parse p3 = new WpsCheckpointStore(mem2).load();
        ok("旧版缺 BSSID → 不 ok", !p3.ok(), "");
        info("    原因：" + p3.error);
        ok("原因说明缺 BSSID", p3.error.contains("BSSID"), p3.error);
        ok("不是含糊的「没有断点」", !p3.error.contains("没有找到断点"), p3.error);
        ok("没有写脏主副本", mem2.read(WpsCheckpointStore.KEY_PRIMARY) == null, "");

        MemBlob memBlank = legacy("   ", "1", "10", "0", "");
        WpsCheckpoint.Parse p4 = new WpsCheckpointStore(memBlank).load();
        ok("旧版 BSSID 只有空白 → 同样拒绝", !p4.ok(), "");
        ok("空白 BSSID 原因可读", p4.error.contains("BSSID"), p4.error);

        info("③ 缺失 BSSID 键但其它旧键存在 → 也算「旧版存在但缺目标」");
        MemBlob memNoKey = legacy(null, "1", "20", null, null);
        WpsCheckpoint.Parse p5 = new WpsCheckpointStore(memNoKey).load();
        ok("只有游标键 → 拒绝且说明缺 BSSID",
                !p5.ok() && p5.error.contains("BSSID"), p5.error);

        info("④ 旧版阶段值非法 → 夹到阶段 1 并在 note 说明");
        MemBlob memBadPhase = legacy("b2:08:aa:bb:cc:dd", "7", "30", "0", "");
        WpsCheckpoint.Parse p6 = new WpsCheckpointStore(memBadPhase).load();
        ok("非法阶段 → 仍 ok", p6.ok(), p6.error);
        ok("非法阶段 → 当阶段 1", p6.ok() && p6.cp.phase == 1, "");
        ok("非法阶段 → note 说明", p6.note.contains("阶段"), p6.note);

        info("⑤ 旧版游标为负 → 夹到 0 并说明");
        MemBlob memNeg = legacy("b2:08:aa:bb:cc:dd", "1", "-9", "-1", "");
        WpsCheckpoint.Parse p7 = new WpsCheckpointStore(memNeg).load();
        ok("负游标 → 仍 ok", p7.ok(), p7.error);
        ok("负游标 → 夹到 0", p7.ok() && p7.cp.cursor == 0 && p7.cp.cursor1 == 0, "");
        ok("负游标 → note 说明", p7.note.contains("游标"), p7.note);

        info("⑥ v=99（更新的版本写入）→ 明确拒绝并说明");
        WpsCheckpoint.Parse hi = WpsCheckpoint.decode("WPS_CP v=99\nbssid=a4%3A2b%3Ab0%3A11%3A22%3A33\ncursor=5\n");
        ok("v=99 → 不 ok", !hi.ok(), "");
        info("    原因：" + hi.error);
        ok("原因含版本号 99", hi.error.contains("99"), hi.error);
        ok("原因说明是更新的版本", hi.error.contains("更新"), hi.error);
        ok("不返回断点对象（不猜结构）", hi.cp == null, "");

        MemBlob memHi = new MemBlob();
        memHi.map.put(WpsCheckpointStore.KEY_PRIMARY, "WPS_CP v=99\ncursor=5\n");
        WpsCheckpoint.Parse p8 = new WpsCheckpointStore(memHi).load();
        ok("经 store：v=99 → 不 ok", !p8.ok(), "");
        ok("经 store：原因保留版本信息",
                p8.error.contains("99") && p8.error.contains("更新"), p8.error);

        info("⑦ 无版本行 / 版本号非法 → 一律「断点格式无法识别」");
        ok("没有版本行", WpsCheckpoint.E_FORMAT.equals(
                WpsCheckpoint.decode("bssid=a4:2b:b0:11:22:33\ncursor=1\n").error), "");
        ok("版本号不是数字", WpsCheckpoint.E_FORMAT.equals(
                WpsCheckpoint.decode("WPS_CP v=abc\ncursor=1\n").error), "");
        ok("版本号缺失", WpsCheckpoint.E_FORMAT.equals(
                WpsCheckpoint.decode("WPS_CP\ncursor=1\n").error), "");
        ok("空串", WpsCheckpoint.E_FORMAT.equals(WpsCheckpoint.decode("").error), "");
        ok("null（不抛异常）", WpsCheckpoint.E_FORMAT.equals(WpsCheckpoint.decode(null).error), "");
        ok("纯垃圾文本", WpsCheckpoint.E_FORMAT.equals(WpsCheckpoint.decode("hello world").error), "");

        info("⑧ v=1 但字段缺失 → 用默认值，不报错");
        WpsCheckpoint.Parse empty = WpsCheckpoint.decode("WPS_CP v=1\n");
        ok("缺字段 → ok", empty.ok(), empty.error);
        ok("缺字段 → error 为空", empty.error.isEmpty(), empty.error);
        ok("缺字段 → bssid 空串", empty.ok() && "".equals(empty.cp.bssid), "");
        ok("缺字段 → phase 默认 1", empty.ok() && empty.cp.phase == 1, "");
        ok("缺字段 → 两个游标默认 0", empty.ok() && empty.cp.cursor == 0 && empty.cp.cursor1 == 0, "");
        ok("缺字段 → mode 默认 3（拆半）", empty.ok() && empty.cp.mode == 3, "");
        ok("缺字段 → prefix 空串", empty.ok() && "".equals(empty.cp.prefix), "");
        ok("缺字段 → schema 取版本行", empty.ok() && empty.cp.schema == 1, "");

        info("⑨ 未知字段忽略、非关键字段坏值不致命");
        WpsCheckpoint.Parse unk = WpsCheckpoint.decode(
                "WPS_CP v=1\nbssid=b2%3A08%3Aaa%3Abb%3Acc%3Add\nfutureKey=whatever\nmode=abc\ncursor=12\n");
        ok("未知键忽略 → ok", unk.ok(), unk.error);
        ok("未知键不影响已知字段", unk.ok() && unk.cp.cursor == 12, "");
        ok("坏 mode 值 → 用默认 3", unk.ok() && unk.cp.mode == 3, "");

        info("⑩ 版本行不在首行也能认（容错）");
        WpsCheckpoint.Parse later = WpsCheckpoint.decode(
                "\nbssid=b2%3A08%3Aaa%3Abb%3Acc%3Add\nWPS_CP v=1\ncursor=8\n");
        ok("版本行在中间 → ok", later.ok(), later.error);
        ok("版本行在中间 → 字段照读", later.ok() && later.cp.cursor == 8, "");

        System.out.println();
        System.out.println("=== WpsCheckpointMigrationTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
