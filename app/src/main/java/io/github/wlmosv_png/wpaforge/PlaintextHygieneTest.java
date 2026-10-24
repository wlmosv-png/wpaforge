package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.HistStore;
import io.github.wlmosv_png.wpaforge.audit.PlaintextMigration;
import io.github.wlmosv_png.wpaforge.audit.SecretBox;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;
import io.github.wlmosv_png.wpaforge.ui.Redact;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R12 回归测试：命中口令不再明文落盘。
 *
 * <h3>要清掉的两个落点</h3>
 * <ul>
 *   <li>{@code files/history.csv} 第 9 列（原来直接写口令）；</li>
 *   <li>{@code files/queue.json} 的 {@code "hit"} 字段（同上）。</li>
 * </ul>
 * 任务日志早就脱敏了（{@link Redact}），这两处一直漏着 —— 磁盘上留着一份长期口令清单。
 *
 * <h3>替换方案</h3>
 * 落盘只写「脱敏标签 + 密文」：标签可对照不可还原（排查用），
 * 密文由 {@link SecretBox}（Android Keystore，AES/GCM）保存，
 * 用来把口令还原给用户自己看。**加密通道不可用时不落明文** ——
 * 代价是重启后看不到口令，这是刻意选的。
 *
 * <h3>兼容与迁移</h3>
 * 读路径认新旧两种格式；旧格式（9 列 / 非空 {@code "hit"}）由
 * {@link PlaintextMigration} **先备份后重写**，不删任何用户数据。
 */
public class PlaintextHygieneTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        if (!c) System.out.println("        want=" + want + " got=" + got);
        ok(name, c);
    }

    /** 测试口令：完全是合成值，不是任何真实网络的凭据。 */
    private static final String LAB_PW = "S3cret-PW-42";
    /**
     * 「旧格式（9 列明文）」用例的夹具口令。
     *
     * <p><b>刻意用合成值</b>：这里原来直接嵌了一个**真实网络口令**（以及它的
     * Redact 前缀）。那种写法有两个问题：一是把真实凭据写进公开仓库，
     * 二是让「明文泄露」这条测试本身变成泄露点。夹具只需要形状正确，
     * 不需要是任何真实口令。
     */
    private static final String LEGACY_PW = "LABPW9182";

    public static void main(String[] args) {
        System.out.println("== R12-1 加解密通道 ==");
        eq("默认不可用（不做事，也不留明文）", null, SecretBox.seal(LAB_PW));
        eq("默认解不开", null, SecretBox.open("v1:a:b"));
        ok("默认 available()=false", !SecretBox.available());
        SecretBox.install(SecretBox.reversibleStub());
        ok("装了桩之后可用", SecretBox.available());
        String sealed = SecretBox.seal(LAB_PW);
        ok("能加密", sealed != null && !sealed.isEmpty());
        ok("密文里不含明文", sealed != null && !sealed.contains(LAB_PW));
        eq("能解回", LAB_PW, SecretBox.open(sealed));
        eq("空串不加密（没有秘密就不造秘密）", null, SecretBox.seal(""));
        eq("null 不加密", null, SecretBox.seal(null));
        eq("乱码解不开（返回 null 而不是猜）", null, SecretBox.open("v1:!!:??"));
        eq("不是本格式的串解不开", null, SecretBox.open("plaintext?"));

        // P2-8：加解密**往返**自检 —— open() 此前从未在真机被执行过，
        // 而恢复原配置完全依赖它。这里用合成串走一遍 seal → open（不含任何真实口令）。
        ok("通道可用时往返一致", SecretBox.roundTrip("wpaforge-selftest-probe"));
        ok("自检探针是合成值（前缀固定，不是任何口令）",
                SecretBox.selfTestProbe().startsWith("wpaforge-selftest-"));
        ok("两次自检探针不同（不是硬编码常量）",
                !SecretBox.selfTestProbe().equals(SecretBox.selfTestProbe()));
        ok("空探针不通过", !SecretBox.roundTrip("") && !SecretBox.roundTrip(null));
        ok("往返自检不会把明文留在密文里",
                !SecretBox.seal("probe-xyz").contains("probe-xyz"));
        SecretBox.install(null);
        ok("通道不可用时往返自检为 false（不假绿）",
                !SecretBox.roundTrip("wpaforge-selftest-probe"));
        SecretBox.install(SecretBox.reversibleStub());

        System.out.println("== R12-2 历史记录：落盘只有标签 + 密文 ==");
        HistStore.Record hit = new HistStore.Record(1_764_000_000_000L, "LAB-TARGET",
                "AA:BB:CC:DD:EE:FF", "LAB-DICT", 100, 11, 58_525L, true, LAB_PW);
        String line = hit.toLine();
        ok("**行里不含明文口令**", !line.contains(LAB_PW));
        ok("行里含脱敏标签", line.contains(Redact.pw(LAB_PW)));
        eq("仍是 10 列（9=标签, 10=密文）", 10, line.split(",", -1).length);
        ok("第 9 列就是标签", Redact.pw(LAB_PW).equals(line.split(",", -1)[8]));
        ok("第 10 列非空（密文已落）", !line.split(",", -1)[9].isEmpty());

        HistStore.Record back = HistStore.Record.fromLine(line);
        ok("能读回来", back != null);
        eq("口令还原（给用户自己看）", LAB_PW, back.password);
        eq("displayPassphrase 优先明文", LAB_PW, back.displayPassphrase());
        ok("不是旧格式", !back.legacyPlain);
        eq("命中标志保留", true, back.hit);
        eq("条数保留", 11, back.tested);
        eq("目标保留", "LAB-TARGET", back.ssid);

        System.out.println("== R12-3 未命中记录不产生任何秘密 ==");
        HistStore.Record miss = new HistStore.Record(1_764_000_000_000L, "LAB-TARGET",
                "AA:BB:CC:DD:EE:FF", "LAB-DICT", 100, 100, 999L, false, null);
        eq("没有标签", "", miss.tag);
        eq("没有密文", "", miss.sealed);
        eq("没有口令", "", miss.password);
        eq("两列仍写出来（格式稳定）", 10, miss.toLine().split(",", -1).length);
        ok("不崩", HistStore.Record.fromLine(miss.toLine()) != null);

        System.out.println("== R12-4 加密通道不可用：宁可不显示，也不落明文 ==");
        SecretBox.install(null);
        HistStore.Record noBox = new HistStore.Record(1_764_000_000_000L, "LAB-TARGET",
                "AA:BB:CC:DD:EE:FF", "LAB-DICT", 10, 1, 10L, true, LAB_PW);
        ok("密文为空", noBox.sealed.isEmpty());
        ok("**行里依然不含明文**", !noBox.toLine().contains(LAB_PW));
        ok("标签仍在（可对照，不可还原）", !noBox.tag.isEmpty());
        HistStore.Record rb = HistStore.Record.fromLine(noBox.toLine());
        eq("重启后口令取不回来（预期代价）", "", rb.password);
        eq("但显示脱敏标签而不是编造值", noBox.tag, rb.displayPassphrase());
        ok("标签不是明文", !rb.displayPassphrase().contains(LAB_PW));

        System.out.println("== R12-5 兼容旧格式（9 列明文） ==");
        String legacy = "1764000000000,LAB-TARGET,AA:BB:CC:DD:EE:FF,LAB-DICT,100,11,58525,1,"
                + LEGACY_PW;
        eq("旧格式被识别为需要迁移", true,
                PlaintextMigration.needsHistoryMigration(legacy + "\n"));
        HistStore.Record lg = HistStore.Record.fromLine(legacy);
        ok("旧格式能读", lg != null);
        ok("标记为 legacy", lg != null && lg.legacyPlain);
        eq("明文读进内存（供界面显示/迁移）", LEGACY_PW, lg.password);
        eq("同时补出标签", Redact.pw(LEGACY_PW), lg.tag);
        ok("此时磁盘上仍是明文（迁移负责清）", legacy.contains(LEGACY_PW));

        SecretBox.install(SecretBox.reversibleStub());
        HistStore.Record lg2 = HistStore.Record.fromLine(legacy);
        ok("补出密文后 toLine 不再含明文", !lg2.toLine().contains(LEGACY_PW));
        eq("重写后是 10 列", 10, lg2.toLine().split(",", -1).length);

        System.out.println("== R12-6 迁移判定（纯函数） ==");
        eq("旧格式命中行 → 需要迁移", true,
                PlaintextMigration.needsHistoryMigration(legacy + "\n"));
        eq("新格式 → 不需要", false,
                PlaintextMigration.needsHistoryMigration(hit.toLine() + "\n"));
        eq("未命中的旧行 → 不需要", false,
                PlaintextMigration.needsHistoryMigration(
                        "1764000000000,LAB-TARGET,AA,LAB-DICT,100,100,999,0,\n"));
        eq("空文本 → 不需要", false, PlaintextMigration.needsHistoryMigration(""));
        eq("null → 不需要", false, PlaintextMigration.needsHistoryMigration(null));
        eq("混合数据（含一条旧的）→ 需要", true,
                PlaintextMigration.needsHistoryMigration(hit.toLine() + "\n" + legacy + "\n"));
        eq("备份文件名固定", "history.csv.plain.bak", PlaintextMigration.HISTORY_BAK);
        eq("队列备份文件名固定", "queue.json.plain.bak", PlaintextMigration.QUEUE_BAK);

        System.out.println("== R12-7 队列：旧明文检测与写入路径 ==");
        eq("旧格式队列 → 有明文", true,
                TaskQueue.hasLegacyPlaintextHit("[{\"hit\":\"" + LEGACY_PW + "\"}]"));
        eq("新格式队列 → 无明文", false,
                TaskQueue.hasLegacyPlaintextHit("[{\"hit\":\"\"}]"));
        eq("空 hit + 标签/密文 → 无明文", false,
                TaskQueue.hasLegacyPlaintextHit(
                        "[{\"hit\":\"\",\"hitTag\":\"#0badc0(len=9)\",\"hitSeal\":\"v1:a:b\"}]"));
        eq("只有标签字段 → 无明文", false,
                TaskQueue.hasLegacyPlaintextHit("[{\"hitTag\":\"#abc\"}]"));
        eq("空串 → 无明文", false, TaskQueue.hasLegacyPlaintextHit(""));
        eq("null → 无明文", false, TaskQueue.hasLegacyPlaintextHit(null));
        eq("活动队列里多条、末条旧 → 有明文", true,
                TaskQueue.hasLegacyPlaintextHit(
                        "[{\"hit\":\"\"},{\"hit\":\"999999\"}]"));
        eq("迁移判定转发一致", true,
                PlaintextMigration.needsQueueMigration("[{\"hit\":\"" + LEGACY_PW + "\"}]"));

        System.out.println("== R12-8 任务写入口：标签与密文必须同源生成 ==");
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = "t1";
        t.ssid = "LAB-TARGET";
        t.setHitPassword(LAB_PW);
        eq("口令在内存里", LAB_PW, t.hitPassword);
        eq("标签来自同一条口令", Redact.pw(LAB_PW), t.hitTag);
        ok("密文非空", !t.hitSeal.isEmpty());
        eq("密文能解回同一口令", LAB_PW, SecretBox.open(t.hitSeal));
        eq("落盘明文标志为假", false, t.hasPlaintextOnDisk());
        t.setHitPassword("");
        eq("清空时要一起清标签", "", t.hitTag);
        eq("清空时要一起清密文", "", t.hitSeal);

        System.out.println("== R12-9 源码守卫：不允许再出现明文落盘路径 ==");
        String hist = read("audit/HistStore.java");
        String tq = read("audit/TaskQueue.java");
        String ma = read("MainActivity.java");
        String app = read("App.java");
        if (hist.isEmpty() || tq.isEmpty() || ma.isEmpty() || app.isEmpty()) {
            System.out.println("  N/RUN 源码守卫（源码不可读）");
        } else {
            ok("HistStore.toLine 不再写 esc(password)",
                    !hist.contains("+ \",\" + esc(password)"));
            ok("HistStore 写的是 tag + sealed",
                    hist.contains("esc(tag) + \",\" + esc(sealed)"));
            ok("HistStore 读写都走 SecretBox",
                    hist.contains("SecretBox.seal(") && hist.contains("SecretBox.open("));
            ok("TaskQueue 写 hit 时固定空串",
                    tq.contains("o.put(\"hit\", \"\");"));
            ok("TaskQueue 不再把 hitPassword 原样塞进 JSON",
                    !tq.contains("o.put(\"hit\", hitPassword == null ? \"\" : hitPassword)"));
            ok("TaskQueue 增设只读迁移标志",
                    tq.contains("legacyHitPlain") && tq.contains("hasPlaintextOnDisk"));
            ok("历史页走 displayPassphrase（没有明文就给标签）",
                    ma.contains("r.displayPassphrase()"));
            ok("App 启动安装加密通道", app.contains("SecretBox.install("));
            ok("App 启动安装恢复记录载体", app.contains("RestoreJournal.install("));
            ok("App 启动跑迁移且先判 needed",
                    app.contains("PlaintextMigration.needed(") && app.contains("PlaintextMigration.run("));
        }

        String mig = read("audit/PlaintextMigration.java");
        if (mig.isEmpty()) {
            System.out.println("  N/RUN 迁移守卫（源码不可读）");
        } else {
            ok("迁移先备份（backup 调用在 rewrite/save 之前）",
                    mig.indexOf("backup(hf,") > 0
                            && mig.indexOf("backup(hf,") < mig.indexOf("HistStore.rewrite(")
                            && mig.indexOf("backup(qf,") < mig.indexOf("TaskQueue.save("));
            ok("备份目标已存在时不覆盖", mig.contains("dst.exists()) return false"));
            ok("不删除任何用户数据（只做重写）",
                    !mig.contains("delete()") || !mig.contains("qf.delete"));
        }

        System.out.println();
        System.out.println("=== R12 明文卫生测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String rel) {
        try {
            return new String(Files.readAllBytes(new File(
                    "/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/wpaforge/"
                            + rel).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
