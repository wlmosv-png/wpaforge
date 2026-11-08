package io.github.wlmosv_png.wpaforge.wps;

/**
 * 断点落盘的原子性与自愈。
 *
 * <h3>要挡住的事故</h3>
 * ① 主副本写坏（写一半被杀、被别的版本写进半截）→ 用户几小时进度归零。
 * 必须有备份，且恢复后要**在 note 里说清楚**（用户有权知道自己的断点换了来源）。
 * ② 写失败被当成写成功 → 界面显示「已保存」，实际什么都没存。
 * ③ 清断点顺手把别的数据一起清掉 —— 这是「重置进度」最不能犯的错。
 */
public final class WpsCheckpointAtomicityTest {

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

    /** 内存 Blob，可注入写失败。 */
    private static final class MemBlob implements WpsCheckpointStore.Blob {
        final java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        boolean failWrites = false;

        public String read(String key) {
            return map.get(key);
        }

        public boolean write(String key, String value) {
            if (failWrites) return false;
            map.put(key, value);
            return true;
        }

        public boolean remove(String key) {
            return map.remove(key) != null;
        }
    }

    private static WpsCheckpoint cp(String bssid, int cursor, String taskId) {
        WpsCheckpoint c = new WpsCheckpoint();
        c.taskId = taskId;
        c.bssid = bssid;
        c.ssid = "TARGET-AP";
        c.mode = 3;
        c.phase = 1;
        c.cursor = cursor;
        c.updatedAtMs = 1700000000000L + cursor;
        return c;
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 断点落盘原子性 ===");

        MemBlob mem = new MemBlob();
        WpsCheckpointStore store = new WpsCheckpointStore(mem);

        info("① 首存：只有主副本，没有备份");
        ok("首存成功", store.save(cp("a4:2b:b0:11:22:33", 10, "t1")), "");
        ok("主副本有值", mem.read(WpsCheckpointStore.KEY_PRIMARY) != null, "");
        ok("首存时不产生空备份", mem.read(WpsCheckpointStore.KEY_BACKUP) == null, "");
        WpsCheckpoint.Parse first = WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_PRIMARY));
        ok("主副本可解析", first.ok() && first.cp.cursor == 10,
                first.ok() ? ("" + first.cp.cursor) : first.error);

        info("② 再存：旧主副本挪到备份");
        ok("再存成功", store.save(cp("a4:2b:b0:11:22:33", 20, "t2")), "");
        WpsCheckpoint.Parse prim = WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_PRIMARY));
        WpsCheckpoint.Parse bak = WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_BACKUP));
        ok("主副本是新断点（cursor=20）", prim.ok() && prim.cp.cursor == 20, "" + prim.cp.cursor);
        ok("备份有值（非空）", mem.read(WpsCheckpointStore.KEY_BACKUP) != null, "");
        ok("备份是上一份（cursor=10）", bak.ok() && bak.cp.cursor == 10, "" + bak.cp.cursor);
        ok("备份里的 taskId 是上一份", bak.ok() && "t1".equals(bak.cp.taskId),
                bak.ok() ? bak.cp.taskId : bak.error);

        info("③ 主副本损坏 → 从备份恢复，并说明来源");
        mem.map.put(WpsCheckpointStore.KEY_PRIMARY, "GARBAGE-NOT-A-CHECKPOINT");
        WpsCheckpoint.Parse rec = store.load();
        ok("损坏后仍能拿到断点", rec.ok(), rec.error);
        ok("error 为空（恢复成功不算失败）", rec.error.isEmpty(), rec.error);
        info("    note：" + rec.note);
        ok("note 说明主副本损坏", rec.note.contains("主副本损坏"), rec.note);
        ok("note 说明用了备份", rec.note.contains("备份"), rec.note);
        ok("恢复出来的是备份那份（cursor=10）", rec.ok() && rec.cp.cursor == 10,
                rec.ok() ? ("" + rec.cp.cursor) : rec.error);
        ok("自愈：主副本被写回可用内容",
                WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_PRIMARY)).ok(),
                mem.read(WpsCheckpointStore.KEY_PRIMARY));

        info("④ 主备都坏 → 报明确原因，且不静默从头");
        MemBlob memBoth = new MemBlob();
        memBoth.map.put(WpsCheckpointStore.KEY_PRIMARY, "JUNK-1");
        memBoth.map.put(WpsCheckpointStore.KEY_BACKUP, "JUNK-2");
        WpsCheckpoint.Parse both = new WpsCheckpointStore(memBoth).load();
        ok("主备都坏 → 不 ok", !both.ok(), "");
        ok("原因含「断点不可用」", both.error.startsWith("断点不可用"), both.error);
        ok("原因说明主备都坏（不是「没有断点」）",
                both.error.contains("主副本") && !both.error.contains("没有找到断点"), both.error);
        ok("不返回断点对象", both.cp == null, "");

        info("⑤ 只有垃圾备份、没有主副本");
        MemBlob memBak = new MemBlob();
        memBak.map.put(WpsCheckpointStore.KEY_BACKUP, "JUNK");
        WpsCheckpoint.Parse onlyBak = new WpsCheckpointStore(memBak).load();
        ok("没有主副本且备份坏 → 不 ok 且原因可读",
                !onlyBak.ok() && onlyBak.error.contains("备份"), onlyBak.error);

        info("⑥ 写失败 → save 返回 false，且不破坏已有主副本");
        String before = mem.read(WpsCheckpointStore.KEY_PRIMARY);
        WpsCheckpoint.Parse beforeParsed = WpsCheckpoint.decode(before);
        mem.failWrites = true;
        boolean wrote = store.save(cp("a4:2b:b0:11:22:33", 999, "t3"));
        mem.failWrites = false;
        ok("写失败 → save 返回 false", !wrote, "");
        ok("写失败 → 主副本原样未动", before.equals(mem.read(WpsCheckpointStore.KEY_PRIMARY)),
                mem.read(WpsCheckpointStore.KEY_PRIMARY));
        WpsCheckpoint.Parse afterParsed = WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_PRIMARY));
        ok("写失败 → 主副本仍是旧断点（cursor=10）",
                afterParsed.ok() && beforeParsed.ok() && afterParsed.cp.cursor == 10,
                afterParsed.ok() ? ("" + afterParsed.cp.cursor) : afterParsed.error);
        ok("写失败 → 后续 load 仍可用", store.load().ok(), store.load().error);

        info("⑦ 写失败之后再正常写 → 能恢复保存能力");
        ok("恢复后 save 成功", store.save(cp("a4:2b:b0:11:22:33", 30, "t4")), "");
        ok("主副本是新断点", WpsCheckpoint.decode(mem.read(WpsCheckpointStore.KEY_PRIMARY))
                .cp.cursor == 30, "");

        info("⑧ clear 只清断点键，不碰别的数据");
        mem.map.put("hist_store_pw_list", "KEEP-ME");
        mem.map.put(WpsCheckpointStore.KEY_LEGACY_BSSID, "a4:2b:b0:11:22:33");
        mem.map.put(WpsCheckpointStore.KEY_LEGACY_CURSOR, "777");
        boolean cleared = store.clear();
        ok("clear 返回 true（至少清掉一份）", cleared, "");
        ok("clear 清掉主副本", mem.read(WpsCheckpointStore.KEY_PRIMARY) == null, "");
        ok("clear 清掉备份", mem.read(WpsCheckpointStore.KEY_BACKUP) == null, "");
        ok("clear 清掉旧版键", mem.read(WpsCheckpointStore.KEY_LEGACY_BSSID) == null
                && mem.read(WpsCheckpointStore.KEY_LEGACY_CURSOR) == null, "");
        ok("clear 不碰别的键", "KEEP-ME".equals(mem.read("hist_store_pw_list")),
                mem.read("hist_store_pw_list"));
        WpsCheckpoint.Parse gone = store.load();
        ok("清完 load → 不 ok", !gone.ok(), "");
        ok("清完 load → 原因是「没有断点」", gone.error.contains("没有找到断点"), gone.error);

        ok("没有任何断点时 clear 返回 false", !new WpsCheckpointStore(new MemBlob()).clear(), "");

        info("⑨ blob 缺失时全部方法安全降级");
        WpsCheckpointStore nil = new WpsCheckpointStore(null);
        ok("blob=null → save 返回 false", !nil.save(cp("a4:2b:b0:11:22:33", 1, "x")), "");
        WpsCheckpoint.Parse nilLoad = nil.load();
        ok("blob=null → load 不 ok", !nilLoad.ok(), "");
        ok("blob=null → load 有原因", !nilLoad.error.isEmpty(), nilLoad.error);
        ok("blob=null → clear 返回 false", !nil.clear(), "");
        ok("blob=null → loadFor 不 ok", !nil.loadFor("a4:2b:b0:11:22:33", "TARGET-AP", 3).ok(), "");
        ok("save(null) 返回 false", !store.save(null), "");

        System.out.println();
        System.out.println("=== WpsCheckpointAtomicityTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
