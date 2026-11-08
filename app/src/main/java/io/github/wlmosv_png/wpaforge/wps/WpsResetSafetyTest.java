package io.github.wlmosv_png.wpaforge.wps;

/**
 * 「重置进度不删历史」——本组测试的核心断言。
 *
 * <h3>要挡住的事故</h3>
 * 重置进度是一个**清理动作**，而清理最容易误伤：命中 PIN 是用户唯一的收获，
 * 断点只是运行状态。如果两者存在同一份存储里、共用同一个 clear，
 * 用户点一次「重置进度」，昨天那个好不容易跑出来的 PIN 就没了 ——
 * 而且没有任何提示。所以：
 * <ul>
 *   <li>{@link WpsCheckpointStore#clear()} 只能碰断点键；<\/li>
 *   <li>台账里带命中 PIN 的任务，重置前后**一条都不能少**；<\/li>
 *   <li>{@link WpsLedger#latestHit} 是跨任务查询，重置不应影响它；<\/li>
 *   <li>删历史只有 {@link WpsLedgerStore#deleteTask} 一个入口（用户主动删）。<\/li>
 * <\/ul>
 */
public final class WpsResetSafetyTest {

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

    private static String readText(java.io.File f) {
        try {
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
            br.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 重置进度不删历史 ===");

        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"),
                "wpaforge-wps-reset-" + System.nanoTime());
        dir.mkdirs();
        java.io.File ledgerFile = new java.io.File(dir, "wps-ledger.txt");
        long now = 1700000000000L;

        String hitId = WpsLedger.newTaskId("a4:2b:b0:11:22:33", now);
        String otherId = WpsLedger.newTaskId("b2:08:aa:bb:cc:dd", now + 1000);

        WpsLedgerStore ledger = new WpsLedgerStore(ledgerFile);
        WpsLedger.Task hitTask = WpsLedger.start(hitId, "a4:2b:b0:11:22:33", "AP-ONE", 3, now);
        hitTask.hitPin = "12345670";
        hitTask.hitVerdict = WpsHitJudge.C_CONFIRMED;
        hitTask.evidence = "verdict=HIT_CONFIRMED connSsid=AP-ONE ip=192.168.1.5 online=1";
        hitTask.hitOnline = true;
        hitTask.status = WpsLedger.ST_DONE;
        hitTask.endReason = "命中";
        hitTask.doneCount = 501;
        hitTask.endedAtMs = now + 60000;
        hitTask.updatedAtMs = now + 60000;

        WpsLedger.Task otherTask = WpsLedger.start(otherId, "b2:08:aa:bb:cc:dd", "AP-TWO", 3, now + 1000);
        otherTask.status = WpsLedger.ST_PAUSED;
        otherTask.pauseReason = "用户暂停";
        otherTask.doneCount = 22;

        ok("写入命中任务", ledger.upsert(hitTask), "");
        ok("写入未命中任务", ledger.upsert(otherTask), "");

        // 断点存储：主副本 + 备份 + 旧版键都摆上，确认 clear 清得干净
        MemBlob cpBlob = new MemBlob();
        WpsCheckpointStore cpStore = new WpsCheckpointStore(cpBlob);
        WpsCheckpoint cp = new WpsCheckpoint();
        cp.taskId = hitId;
        cp.bssid = "a4:2b:b0:11:22:33";
        cp.ssid = "AP-ONE";
        cp.mode = 3;
        cp.phase = 1;
        cp.cursor = 501;
        ok("断点已保存", cpStore.save(cp), "");
        ok("再存一次（产生备份）", cpStore.save(cp), "");
        cpBlob.map.put(WpsCheckpointStore.KEY_LEGACY_BSSID, "a4:2b:b0:11:22:33");
        cpBlob.map.put(WpsCheckpointStore.KEY_LEGACY_CURSOR, "500");

        java.util.List<WpsLedger.Task> pre = ledger.load();
        String preText = readText(ledgerFile);
        ok("重置前：两条任务", pre.size() == 2, "" + pre.size());
        ok("重置前：一条命中", WpsLedger.hits(pre).size() == 1, "" + WpsLedger.hits(pre).size());
        ok("重置前：latestHit 命中那条",
                WpsLedger.latestHit(pre) != null && hitId.equals(WpsLedger.latestHit(pre).taskId), "");
        ok("重置前：断点可读", cpStore.loadFor("a4:2b:b0:11:22:33", "AP-ONE", 3).ok(), "");

        info("① 重置：清断点");
        boolean cleared = cpStore.clear();
        ok("clear 返回 true", cleared, "");
        ok("断点主副本已清", cpBlob.read(WpsCheckpointStore.KEY_PRIMARY) == null, "");
        ok("断点备份已清", cpBlob.read(WpsCheckpointStore.KEY_BACKUP) == null, "");
        ok("旧版断点键已清", cpBlob.read(WpsCheckpointStore.KEY_LEGACY_BSSID) == null
                && cpBlob.read(WpsCheckpointStore.KEY_LEGACY_CURSOR) == null, "");
        WpsCheckpoint.Parse after = cpStore.loadFor("a4:2b:b0:11:22:33", "AP-ONE", 3);
        ok("重置后 loadFor 不 ok", !after.ok(), "");
        ok("重置后原因说明「没有断点」（不是静默从头）",
                after.error.contains("没有找到断点"), after.error);

        info("② 核心断言：重置后台账命中任务一条都没少");
        java.util.List<WpsLedger.Task> post = ledger.load();
        ok("任务数不变（2）", post.size() == 2, "" + post.size());
        ok("命中任务仍在", WpsLedger.find(post, hitId) != null, "");
        ok("未命中任务仍在", WpsLedger.find(post, otherId) != null, "");
        ok("命中条数不变（1）", WpsLedger.hits(post).size() == 1, "" + WpsLedger.hits(post).size());
        WpsLedger.Task kept = WpsLedger.find(post, hitId);
        ok("hitPin 未被重置清掉", kept != null && "12345670".equals(kept.hitPin),
                kept == null ? "null" : kept.hitPin);
        ok("命中判定保留", kept != null && WpsHitJudge.C_CONFIRMED.equals(kept.hitVerdict), "");
        ok("命中证据保留", kept != null && kept.evidence.contains("AP-ONE"), "");
        ok("完成计数保留", kept != null && kept.doneCount == 501, "");
        ok("暂停原因保留", WpsLedger.find(post, otherId) != null
                && "用户暂停".equals(WpsLedger.find(post, otherId).pauseReason), "");

        WpsLedger.Task lh = WpsLedger.latestHit(post);
        ok("latestHit 重置后仍命中那条", lh != null && hitId.equals(lh.taskId),
                lh == null ? "null" : lh.taskId);
        ok("latestHit 的 PIN 正确", lh != null && "12345670".equals(lh.hitPin), "");

        info("③ 重置没有碰台账文件本身");
        ok("台账文件字节未变", preText.equals(readText(ledgerFile)), "");
        ok("重新 new store 仍读到全部历史", new WpsLedgerStore(ledgerFile).load().size() == 2, "");

        info("④ 只有 deleteTask 能删历史");
        ok("deleteTask 不存在的 id → false", !ledger.deleteTask("wps-nope-1"), "");
        ok("删除后文件仍是两条", ledger.load().size() == 2, "");
        ok("deleteTask(命中那条) → true", ledger.deleteTask(hitId), "");
        java.util.List<WpsLedger.Task> oneLeft = ledger.load();
        ok("删除后只剩一条", oneLeft.size() == 1, "" + oneLeft.size());
        ok("删除的是指定的那条", WpsLedger.find(oneLeft, hitId) == null, "");
        ok("另一条仍在", WpsLedger.find(oneLeft, otherId) != null, "");
        ok("删除后 hits 为空", WpsLedger.hits(oneLeft).isEmpty(),
                "" + WpsLedger.hits(oneLeft).size());
        ok("删除后 latestHit 为 null", WpsLedger.latestHit(oneLeft) == null, "");
        ok("删除动作落盘（重新 new store 仍是一条）",
                new WpsLedgerStore(ledgerFile).load().size() == 1, "");

        info("⑤ 重复删除同一条 → false（幂等，不误删别的）");
        ok("再删同一条 → false", !ledger.deleteTask(hitId), "");
        ok("未命中那条未受影响", ledger.load().size() == 1
                && WpsLedger.find(ledger.load(), otherId) != null, "");

        // 清理
        deleteQuietly(ledgerFile);
        deleteQuietly(new java.io.File(ledgerFile.getPath() + ".bak"));
        deleteQuietly(new java.io.File(ledgerFile.getPath() + ".tmp"));
        deleteQuietly(dir);

        System.out.println();
        System.out.println("=== WpsResetSafetyTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static void deleteQuietly(java.io.File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
            // 清理失败不影响结论
        }
    }
}
