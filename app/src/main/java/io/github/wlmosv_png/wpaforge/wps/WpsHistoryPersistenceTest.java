package io.github.wlmosv_png.wpaforge.wps;

/**
 * 台账持久化：新任务不覆盖旧任务、重启仍在、坏行跳过、命中不被后续 upsert 抹掉。
 *
 * <h3>要挡住的事故</h3>
 * ① 「一条任务一行，写完覆盖整份」的常见写法会让第二个目标把第一个目标挤掉
 * —— 用户一周后回来，历史只剩最后一次跑的那条。
 * ② 命中 PIN 是用户的**收获**，任务后续还被更新很多次（进度、时间戳），
 * 任何一次「更新状态」把它清掉，用户就永久失去了那个 PIN。
 * ③ 一条坏行（断电写半截）让整份历史读不出来。坏行必须只损失它自己。
 */
public final class WpsHistoryPersistenceTest {

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

    /** 整个测试共用一个临时目录，跑完一起删掉。 */
    private static java.io.File dir;

    private static java.io.File tmpFile(String name) {
        if (dir == null) {
            dir = new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"),
                    "wpaforge-wps-test-" + System.nanoTime());
            dir.mkdirs();
        }
        return new java.io.File(dir, name);
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

    private static void appendText(java.io.File f, String extra) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write(extra.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
            // 测试内失败会在后续断言里暴露
        }
    }

    /** 整体覆写（模拟「主文件被写坏」）。 */
    private static void writeText(java.io.File f, String text) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
            // 测试内失败会在后续断言里暴露
        }
    }

    public static void main(String[] a) {
        System.out.println("=== WPS 台账持久化 ===");

        java.io.File f = tmpFile("wps-ledger.txt");
        java.io.File bak = new java.io.File(f.getPath() + ".bak");
        info("    文件：" + f.getPath());
        long now = 1700000000000L;

        info("① taskId 稳定可测");
        String id1 = WpsLedger.newTaskId("a4:2b:b0:11:22:33", now);
        String id1b = WpsLedger.newTaskId("a4:2b:b0:11:22:33", now);
        String id1c = WpsLedger.newTaskId("a4:2b:b0:11:22:33", now + 1);
        String id2 = WpsLedger.newTaskId("b2:08:aa:bb:cc:dd", now + 1000);
        info("    taskId=" + id1);
        ok("同样输入 → 同样 id", id1.equals(id1b), id1 + " / " + id1b);
        ok("时间不同 → id 不同", !id1.equals(id1c), id1 + " / " + id1c);
        ok("taskId 形态 wps-<后 4 位>-<时间>", ("wps-2233-" + now).equals(id1), id1);
        ok("不同 BSSID → 不同 id", !id1.equals(id2), id2);

        WpsLedgerStore store = new WpsLedgerStore(f);
        WpsLedger.Task t1 = WpsLedger.start(id1, "a4:2b:b0:11:22:33", "AP-ONE", 3, now);
        WpsLedger.Task t2 = WpsLedger.start(id2, "b2:08:aa:bb:cc:dd", "AP-TWO", 2, now + 1000);

        info("② 新任务不得覆盖旧任务");
        ok("首写成功", store.upsert(t1), "");
        ok("二写成功", store.upsert(t2), "");
        java.util.List<WpsLedger.Task> all = store.load();
        ok("两条历史都在", all.size() == 2, "" + all.size());
        ok("t1 在", WpsLedger.find(all, id1) != null, "");
        ok("t2 在", WpsLedger.find(all, id2) != null, "");
        ok("t1 的 SSID 未被覆盖", "AP-ONE".equals(WpsLedger.find(all, id1).ssid),
                WpsLedger.find(all, id1).ssid);
        ok("t2 的 SSID 正确", "AP-TWO".equals(WpsLedger.find(all, id2).ssid),
                WpsLedger.find(all, id2).ssid);
        ok("t1 的策略档位保留", WpsLedger.find(all, id1).mode == 3, "");
        ok("t2 的策略档位保留", WpsLedger.find(all, id2).mode == 2, "");
        ok("默认状态 RUNNING", WpsLedger.ST_RUNNING.equals(WpsLedger.find(all, id1).status),
                WpsLedger.find(all, id1).status);
        ok("t1 起始时间保留", WpsLedger.find(all, id1).startedAtMs == now, "");
        ok("备份文件已生成（.bak）", bak.isFile(), "");

        info("③ 模拟重启：重新 new store，历史一条不少");
        WpsLedgerStore store2 = new WpsLedgerStore(f);
        java.util.List<WpsLedger.Task> all2 = store2.load();
        ok("重启后仍是两条", all2.size() == 2, "" + all2.size());
        ok("重启后 taskId 一致", WpsLedger.find(all2, id1) != null
                && WpsLedger.find(all2, id2) != null, "");
        ok("重启后没有坏行", store2.lastLoadSkipped() == 0, "" + store2.lastLoadSkipped());
        ok("重启后 note 为空（正常读取）", store2.lastLoadNote().isEmpty(), store2.lastLoadNote());

        info("④ 覆盖升级：load 不改写文件");
        String before = readText(f);
        java.util.List<WpsLedger.Task> all3 = new WpsLedgerStore(f).load();
        ok("load 不改写文件", before.equals(readText(f)), "");
        ok("覆盖升级后历史仍在", all3.size() == 2, "" + all3.size());

        info("⑤ 主文件丢失 → 用备份");
        java.io.File f2 = tmpFile("ledger2.txt");
        java.io.File bak2 = new java.io.File(f2.getPath() + ".bak");
        WpsLedgerStore s2a = new WpsLedgerStore(f2);
        ok("先写一条", s2a.upsert(t1), "");
        ok("再写一条（生成备份）", s2a.upsert(t2), "");
        ok("备份存在", bak2.isFile(), "");
        ok("删掉主文件", f2.delete() || !f2.exists(), "");
        WpsLedgerStore s2b = new WpsLedgerStore(f2);
        java.util.List<WpsLedger.Task> rec = s2b.load();
        info("    恢复出 " + rec.size() + " 条，note：" + s2b.lastLoadNote());
        ok("主文件丢失 → 用备份读出内容", !rec.isEmpty(), "" + rec.size());
        ok("note 说明用了备份", s2b.lastLoadNote().contains("备份"), s2b.lastLoadNote());

        info("⑥ 坏行跳过并计数");
        appendText(f, "GARBAGE-NOT-A-TASK\nWPS_TASK v=abc broken=1\n\n");
        WpsLedgerStore store3 = new WpsLedgerStore(f);
        java.util.List<WpsLedger.Task> all4 = store3.load();
        ok("好行保留（两条）", all4.size() == 2, "" + all4.size());
        ok("lastLoadSkipped = 2", store3.lastLoadSkipped() == 2, "" + store3.lastLoadSkipped());
        info("    note：" + store3.lastLoadNote());
        ok("note 写明跳过了几行", store3.lastLoadNote().contains("2"), store3.lastLoadNote());
        ok("坏行不影响已解析数据", WpsLedger.find(all4, id1) != null, "");

        info("⑥b 主文件一条都解析不出来 → 用备份并说明");
        java.io.File f3 = tmpFile("ledger3.txt");
        java.io.File bak3 = new java.io.File(f3.getPath() + ".bak");
        WpsLedgerStore s3a = new WpsLedgerStore(f3);
        ok("先写一条", s3a.upsert(t1), "");
        ok("再写一条（生成备份）", s3a.upsert(t2), "");
        ok("备份存在", bak3.isFile(), "");
        writeText(f3, "ALL-JUNK-1\nALL-JUNK-2\n");
        WpsLedgerStore s3b = new WpsLedgerStore(f3);
        java.util.List<WpsLedger.Task> recMain = s3b.load();
        info("    恢复出 " + recMain.size() + " 条，note：" + s3b.lastLoadNote());
        ok("主文件全坏 → 仍读到历史（来自备份）", !recMain.isEmpty(), "" + recMain.size());
        ok("主文件全坏 → note 说明用了备份",
                s3b.lastLoadNote().contains("备份"), s3b.lastLoadNote());
        ok("备份里的 taskId 正确", WpsLedger.find(recMain, id1) != null, "");

        info("⑦ 命中任务经多次 upsert 后 hitPin 仍在");
        WpsLedger.Task hit = WpsLedger.find(store.load(), id1);
        ok("取到 t1", hit != null, "");
        hit.hitPin = "12345670";
        hit.hitVerdict = WpsHitJudge.C_CONFIRMED;
        hit.evidence = "verdict=HIT_CONFIRMED connSsid=AP-ONE ip=192.168.1.5 online=1";
        hit.hitOnline = true;
        hit.connVerify = "已连上目标（依据: wifiinfo）";
        hit.status = WpsLedger.ST_DONE;
        hit.endReason = "命中";
        hit.doneCount = 137;
        hit.endedAtMs = now + 60000;
        hit.updatedAtMs = now + 60000;
        ok("命中记录写盘", store.upsert(hit), "");

        for (int i = 0; i < 3; i++) {
            WpsLedger.Task cur = WpsLedger.find(store.load(), id1);
            cur.updatedAtMs += 1000;
            cur.doneCount += 1;
            cur.lastPin = "9999999" + i;
            cur.lastResult = "NO_RESPONSE";
            ok("第 " + (i + 1) + " 次后续更新写盘", store.upsert(cur), "");
        }
        java.util.List<WpsLedger.Task> finalAll = store.load();
        WpsLedger.Task back = WpsLedger.find(finalAll, id1);
        ok("upsert 不产生重复记录", finalAll.size() == 2, "" + finalAll.size());
        ok("hitPin 仍在", back != null && back.hasHit() && "12345670".equals(back.hitPin),
                back == null ? "null" : back.hitPin);
        ok("命中判定仍在", back != null && WpsHitJudge.C_CONFIRMED.equals(back.hitVerdict),
                back == null ? "null" : back.hitVerdict);
        ok("证据明细仍在且带空格也完整", back != null
                && back.evidence.contains("HIT_CONFIRMED") && back.evidence.contains("AP-ONE"),
                back == null ? "null" : back.evidence);
        ok("联网事实仍在", back != null && back.hitOnline, "");
        ok("连接验证仍在", back != null && back.connVerify.contains("已连上"), "");
        ok("进度被更新（doneCount=140）", back != null && back.doneCount == 140,
                back == null ? "null" : ("" + back.doneCount));
        ok("hits() 能取到这条", WpsLedger.hits(finalAll).size() == 1,
                "" + WpsLedger.hits(finalAll).size());
        WpsLedger.Task lh = WpsLedger.latestHit(finalAll);
        ok("latestHit 是它", lh != null && "12345670".equals(lh.hitPin),
                lh == null ? "null" : lh.hitPin);
        String sum = WpsLedger.summary(finalAll, true);
        info("    概览：" + sum);
        ok("概览含任务数与命中数", sum.contains("2") && sum.contains("1"), sum);

        info("⑧ Task 单行编解码（含空格/中文/特殊字符）");
        WpsLedger.Task edge = WpsLedger.start("wps-edge-1", "b2:08:aa:bb:cc:dd", "我家 的 路由器 = 5G", 3, now);
        edge.pauseReason = "用户暂停：换路由器";
        edge.lastResult = "SUCCESS_UNVERIFIED";
        WpsLedger.Task rt = WpsLedger.Task.decode(edge.encode());
        ok("往返成功", rt != null, "");
        ok("SSID 带空格/等号往返", rt != null && "我家 的 路由器 = 5G".equals(rt.ssid),
                rt == null ? "null" : rt.ssid);
        ok("中文原因往返", rt != null && "用户暂停：换路由器".equals(rt.pauseReason),
                rt == null ? "null" : rt.pauseReason);
        ok("单行不含换行", edge.encode().indexOf('\n') < 0, "");
        ok("缺字段行（只有 v）仍可解析", WpsLedger.Task.decode("WPS_TASK v=1") != null, "");
        ok("非台账行 → null", WpsLedger.Task.decode("GARBAGE") == null, "");
        ok("空行 → null", WpsLedger.Task.decode("   ") == null, "");
        ok("null → null（不抛）", WpsLedger.Task.decode(null) == null, "");

        info("⑨ file=null 安全降级");
        WpsLedgerStore nil = new WpsLedgerStore((java.io.File) null);
        ok("load 返回空表", nil.load().isEmpty(), "");
        ok("upsert 返回 false", !nil.upsert(t1), "");
        ok("deleteTask 返回 false", !nil.deleteTask(id1), "");

        // 清理
        deleteQuietly(f);
        deleteQuietly(bak);
        deleteQuietly(f2);
        deleteQuietly(bak2);
        deleteQuietly(f3);
        deleteQuietly(bak3);
        deleteQuietly(new java.io.File(f.getPath() + ".tmp"));
        deleteQuietly(f.getParentFile());

        System.out.println();
        System.out.println("=== WpsHistoryPersistenceTest: " + pass + " 通过, " + fail + " 失败 ===");
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
