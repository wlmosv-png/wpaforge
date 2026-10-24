package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RestoreJournal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * R11 回归测试：恢复记录的持久化与「进程被杀也能补做」这条链条。
 *
 * <p>为什么单独测它：{@code finally} 在 SIGKILL / 系统强停 / 掉电时**不一定执行**，
 * 所以恢复能力实际上取决于“盘上那份记录还在不在、能不能读回来”。
 * 序列化一旦出问题（转义、截断、字段缺失），恢复会静默失效 ——
 * 而静默失效正是这个项目最不能接受的一类问题。
 */
public class RestoreJournalTest {

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

    private static RestoreJournal.Entry sample() {
        RestoreJournal.Entry e = new RestoreJournal.Entry();
        e.stage = RestoreJournal.STAGE_ARMED;
        e.ssid = "LAB-TARGET";
        e.bssid = "AA:BB:CC:DD:EE:FF";
        e.security = "wpa2";
        e.originalNetId = 2747L;
        e.hadPsk = true;
        e.sealedPsk = "v1:AAAAAAAAAAAAAAAA:BBBB";
        e.snapshotOk = true;
        e.runId = "tndtjv-4b45cf4e-0";
        e.armedAt = 1_764_000_000_000L;
        e.finishedAt = 1_764_000_060_000L;
        e.failStep = "";
        e.failDetail = "";
        return e;
    }

    public static void main(String[] args) {
        System.out.println("== R11J-1 往返序列化 ==");
        RestoreJournal.Entry a = sample();
        RestoreJournal.Entry b = RestoreJournal.Entry.fromText(a.toText());
        ok("能读回来", b != null);
        eq("stage", a.stage, b.stage);
        eq("ssid", a.ssid, b.ssid);
        eq("bssid", a.bssid, b.bssid);
        eq("security", a.security, b.security);
        eq("originalNetId", a.originalNetId, b.originalNetId);
        eq("hadPsk", a.hadPsk, b.hadPsk);
        eq("sealedPsk", a.sealedPsk, b.sealedPsk);
        eq("snapshotOk", a.snapshotOk, b.snapshotOk);
        eq("runId", a.runId, b.runId);
        eq("armedAt", a.armedAt, b.armedAt);
        eq("finishedAt", a.finishedAt, b.finishedAt);

        System.out.println("== R11J-2 转义：分隔符与换行不许把格式撑破 ==");
        RestoreJournal.Entry w = sample();
        w.ssid = "My Net = 5G";
        w.failDetail = "第一行\n第二行";
        w.runId = "a=b\\c";
        RestoreJournal.Entry w2 = RestoreJournal.Entry.fromText(w.toText());
        ok("含 = 的 SSID 往返正确", w2 != null && "My Net = 5G".equals(w2.ssid));
        ok("含换行的说明往返正确", w2 != null && "第一行\n第二行".equals(w2.failDetail));
        ok("含反斜杠的字段往返正确", w2 != null && "a=b\\c".equals(w2.runId));
        ok("序列化后仍是逐行 key=value（解析器不靠猜）",
                w.toText().split("\n").length >= 13);

        System.out.println("== R11J-3 坏输入不猜、不伪造 ==");
        eq("空文本 → null", null, RestoreJournal.Entry.fromText(""));
        eq("空白 → null", null, RestoreJournal.Entry.fromText("   \n  "));
        eq("null → null", null, RestoreJournal.Entry.fromText(null));
        eq("缺 ssid → null", null, RestoreJournal.Entry.fromText("stage=ARMED\n"));
        eq("非 key=value 行 → null", null,
                RestoreJournal.Entry.fromText("stage=ARMED\nssid=LAB\n这行没有等号\n"));
        ok("未知键被忽略（向前兼容）",
                RestoreJournal.Entry.fromText("ssid=LAB\nfuture=1\n") != null);

        System.out.println("== R11J-4 默认值：缺失字段不许变成“看似有效” ==");
        RestoreJournal.Entry d = RestoreJournal.Entry.fromText("ssid=LAB\n");
        ok("能解析", d != null);
        eq("originalNetId 默认 -1（不是 0）", -1L, d.originalNetId);
        eq("hadPsk 默认 false", false, d.hadPsk);
        eq("sealedPsk 默认空（拿不到就不装作能还原）", "", d.sealedPsk);
        ok("canReadd 默认 false", !d.canReadd());
        eq("stage 默认 ARMED（未完成 → 下次启动会补做）",
                RestoreJournal.STAGE_ARMED, d.stage);
        ok("默认就是“需要处理”", d.armed());

        System.out.println("== R11J-5 载体与补做判据 ==");
        RestoreJournal.Store mem = RestoreJournal.memory();
        RestoreJournal.install(mem);
        ok("装好载体", RestoreJournal.installed());

        ok("空载体 → 没有待办", !RestoreJournal.hasPending());
        eq("空载体 → 读不到记录", null, RestoreJournal.load());

        RestoreJournal.Entry e = sample();
        ok("写入成功", RestoreJournal.save(e));
        ok("读回一致", RestoreJournal.load() != null
                && "LAB-TARGET".equals(RestoreJournal.load().ssid));
        ok("ARMED 视为待办（进程被杀后要补做）", RestoreJournal.hasPending());

        RestoreJournal.Entry f = RestoreJournal.load();
        f.stage = RestoreJournal.STAGE_FAILED;
        RestoreJournal.save(f);
        ok("FAILED 也视为待办（失败必须留下可追踪状态）", RestoreJournal.hasPending());

        f.stage = RestoreJournal.STAGE_RESTORED;
        RestoreJournal.save(f);
        ok("RESTORED 不再是待办", !RestoreJournal.hasPending());

        ok("清除成功", RestoreJournal.clear());
        eq("清除后读不到", null, RestoreJournal.load());

        System.out.println("== R11J-6 载体缺失时不许谎报成功 ==");
        RestoreJournal.install(null);
        ok("install(null) 后视为未安装", !RestoreJournal.installed());
        ok("保存失败要返回 false（调用方必须知道恢复能力未知）",
                !RestoreJournal.save(sample()));
        eq("读不到", null, RestoreJournal.load());
        ok("没有待办（而不是崩）", !RestoreJournal.hasPending());

        System.out.println("== R11J-6b 有内容但读不出来 ≠ 没有待办（F7）==");
        RestoreJournal.Store broken = RestoreJournal.memory();
        broken.write("stage=ARMED\nssid=OTHER-TARGET\n\u8fd9\u884c\u6ca1\u6709\u7b49\u53f7\n");
        RestoreJournal.install(broken);
        eq("损坏记录 → load() 拿不到（既有行为）", null, RestoreJournal.load());
        ok("但 pendingUnknown() 必须为真（不许当成“没有记录”）",
                RestoreJournal.pendingUnknown());
        ok("闸门据此拦住新任务",
                io.github.wlmosv_png.wpaforge.audit.RestoreGuard.decide(
                        new io.github.wlmosv_png.wpaforge.audit.RestoreGuard.Facts(
                                true, true, true, true, true, true, true, true)).proceed == false);
        broken.write("");
        ok("空内容 → 不算未知（真的没有待办）", !RestoreJournal.pendingUnknown());

        final boolean[] throwOnRead = {false};
        RestoreJournal.install(new RestoreJournal.Store() {
            @Override public String read() {
                if (throwOnRead[0]) throw new IllegalStateException("io");
                return null;
            }
            @Override public boolean write(String text) { return true; }
            @Override public boolean clear() { return true; }
        });
        ok("读返回 null（真的没有记录）→ 不算未知", !RestoreJournal.pendingUnknown());
        throwOnRead[0] = true;
        ok("读抛异常 → 未知（不许当成没有）", RestoreJournal.pendingUnknown());

        System.out.println("== R11J-6c 写成功必须回读一致 ==");
        RestoreJournal.Store okStore = RestoreJournal.memory();
        okStore.write("x");
        ok("内容一致 → 真", RestoreJournal.verifyWritten(okStore, "x"));
        RestoreJournal.Store liar = new RestoreJournal.Store() {
            @Override public String read() { return "STALE-RECORD\n"; }
            @Override public boolean write(String text) { return true; }
            @Override public boolean clear() { return true; }
        };
        ok("载体谎报成功（盘上仍是旧记录）→ 假",
                !RestoreJournal.verifyWritten(liar, "NEW-RECORD\n"));
        ok("载体抛异常 → 假",
                !RestoreJournal.verifyWritten(new RestoreJournal.Store() {
                    @Override public String read() { throw new IllegalStateException("io"); }
                    @Override public boolean write(String text) { return true; }
                    @Override public boolean clear() { return true; }
                }, "x"));
        ok("null 入参 → 假",
                !RestoreJournal.verifyWritten(null, "x")
                        && !RestoreJournal.verifyWritten(RestoreJournal.memory(), null));

        System.out.println("== R11J-7 记录里不出现明文口令字段 ==");
        String src = read("/workspace/promo-wpaforge/app/src/main/java/io/github/wlmosv_png/"
                + "wpaforge/audit/RestoreJournal.java");
        if (src == null) {
            System.out.println("  N/RUN 结构守卫（源码不可读）");
        } else {
            ok("只有 sealedPsk，没有 plainPsk/password 字段",
                    src.contains("sealedPsk")
                            && !src.contains("public String psk;")
                            && !src.contains("public String plainPsk")
                            && !src.contains("public String password"));
            ok("注释里写清“绝不能明文落盘”", src.contains("绝不能明文落盘")
                    || src.contains("口令怎么存"));
        }

        System.out.println();
        System.out.println("=== R11 恢复记录测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
