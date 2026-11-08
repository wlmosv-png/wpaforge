package io.github.wlmosv_png.wpaforge.wps;

/**
 * WpsStats 的单测：分类映射逐条断言 + 分位数用真实样本 + 落盘往返 + 台账对账 + 文案口径。
 *
 * <p>本类**不碰** Android API（纯逻辑回归），可在门禁里直接跑。
 *
 * <h3>它守住的几条底线</h3>
 * <ol>
 *   <li>UNKNOWN / TIMEOUT **不得**被并进成功或失败（各自独占一行）。</li>
 *   <li>不认识的串 → INSUFFICIENT，绝不猜；SKIPPED_INVALID 只进 skipped。</li>
 *   <li>样本不足 5 个时 P50/P90 = -1（不许用一个样本编出 P90）；无数据时 ratio = -1。</li>
 *   <li>坏输入 decode 返回空统计、绝不抛；且空统计必须能让 UI 说「暂无数据」。</li>
 * </ol>
 */
public final class WpsStatsTest {

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

    private static void eq(String name, Object got, Object want) {
        boolean c = got == null ? want == null : got.equals(want);
        ok(name + "（实得 " + got + "，期望 " + want + "）", c, "");
    }

    private static void eql(String name, long got, long want) {
        ok(name + "（实得 " + got + "，期望 " + want + "）", got == want, "");
    }

    /** 布尔断言（别用 eq —— 它的实参是 Object，Boolean 与字符串永远不等）。 */
    private static void bt(String name, boolean cond) {
        ok(name, cond, "");
    }

    private static int lines(String s) {
        if (s == null || s.isEmpty()) return 0;
        return s.split("\n", -1).length;
    }

    public static void main(String[] args) {
        System.out.println("== WpsStats 专项统计 ==");

        // ---------------- 1. classify 每条映射 ----------------
        info("1. classify：硬规则逐条（不得把 TIMEOUT/UNKNOWN 并进成功或失败）");
        eq("SUCCESS → CONFIRMED", WpsStats.classify("SUCCESS"), WpsStats.C_CONFIRMED);
        eq("SUCCESS_UNVERIFIED → INSUFFICIENT（疑似不算成功）",
                WpsStats.classify("SUCCESS_UNVERIFIED"), WpsStats.C_INSUFFICIENT);
        eq("CMD_FAIL → CMD_REFUSED", WpsStats.classify("CMD_FAIL"), WpsStats.C_CMD_REFUSED);
        eq("FAIL → CMD_REFUSED", WpsStats.classify("FAIL"), WpsStats.C_CMD_REFUSED);
        eq("FAIL_whatever（FAIL*）→ CMD_REFUSED",
                WpsStats.classify("FAIL_whatever"), WpsStats.C_CMD_REFUSED);
        eq("NO_RESPONSE → NO_RESPONSE", WpsStats.classify("NO_RESPONSE"), WpsStats.C_NO_RESPONSE);
        eq("REJECTED → REJECTED", WpsStats.classify("REJECTED"), WpsStats.C_REJECTED);
        eq("PIN_REJECTED → REJECTED", WpsStats.classify("PIN_REJECTED"), WpsStats.C_REJECTED);
        eq("NO_ASSOC → REJECTED", WpsStats.classify("NO_ASSOC"), WpsStats.C_REJECTED);
        eq("HALF_OK → HALF_EVIDENCE", WpsStats.classify("HALF_OK"), WpsStats.C_HALF_EVIDENCE);
        eq("TIMEOUT → TIMEOUT（独立分类）", WpsStats.classify("TIMEOUT"), WpsStats.C_TIMEOUT);
        eq("LINK_LOST → CHANNEL_ERROR",
                WpsStats.classify("LINK_LOST"), WpsStats.C_CHANNEL_ERROR);
        eq("ERROR → CHANNEL_ERROR", WpsStats.classify("ERROR"), WpsStats.C_CHANNEL_ERROR);
        eq("BUDGET → INSUFFICIENT", WpsStats.classify("BUDGET"), WpsStats.C_INSUFFICIENT);
        eq("null → INSUFFICIENT", WpsStats.classify(null), WpsStats.C_INSUFFICIENT);
        eq("空串 → INSUFFICIENT", WpsStats.classify(""), WpsStats.C_INSUFFICIENT);
        eq("全空白 → INSUFFICIENT", WpsStats.classify("   "), WpsStats.C_INSUFFICIENT);
        eq("未知串 → INSUFFICIENT", WpsStats.classify("WHATEVER_123"), WpsStats.C_INSUFFICIENT);
        eq("' TIMEOUT '（带空白）仍识别 → TIMEOUT",
                WpsStats.classify(" TIMEOUT "), WpsStats.C_TIMEOUT);
        // 锁定风险**不是** helper 的 status：classify 永不返回 C_LOCK_RISK，
        // 它只能走 noteLockRisk()/addClass()。
        eq("LOCK_RISK 不是 status → INSUFFICIENT",
                WpsStats.classify(WpsStats.C_LOCK_RISK), WpsStats.C_INSUFFICIENT);
        // 小写不是 helper 的拼法：宁可少记一次成功，也不猜。
        eq("小写 success → INSUFFICIENT（不猜）",
                WpsStats.classify("success"), WpsStats.C_INSUFFICIENT);
        eq("SKIPPED_INVALID → INSUFFICIENT（它不是分类）",
                WpsStats.classify("SKIPPED_INVALID"), WpsStats.C_INSUFFICIENT);
        // 协议证据（logKind/会话标记）→ PROTOCOL：这是为了让 C_PROTOCOL 也能由 status 触达。
        eq("M2D_THEN_MORE → PROTOCOL",
                WpsStats.classify("M2D_THEN_MORE"), WpsStats.C_PROTOCOL);
        eq("WPS_SESSION → PROTOCOL",
                WpsStats.classify("WPS_SESSION"), WpsStats.C_PROTOCOL);
        eq("EAP_ONLY 不算协议交互 → INSUFFICIENT",
                WpsStats.classify("EAP_ONLY"), WpsStats.C_INSUFFICIENT);

        {   // classify 永不返回 null，且返回值必是 10 个分类之一
            String[] inputs = {"SUCCESS", "TIMEOUT", null, "", "x", "FAIL_1", "NO_ASSOC",
                    "LINK_LOST", "HALF_OK", "BUDGET", "M2D_ONLY", "EAP_STALL"};
            boolean okAll = true;
            String badOne = "";
            for (String in : inputs) {
                String c = WpsStats.classify(in);
                if (c == null || !WpsStats.classes().contains(c)) {
                    okAll = false;
                    badOne = String.valueOf(in) + "→" + c;
                    break;
                }
            }
            ok("classify 只返回已声明分类（非 null，12 种输入）", okAll, badOne);
            eq("classes() 共 10 个分类", WpsStats.classes().size(), 10);
        }

        // ---------------- 2. SKIPPED_INVALID 只进 skipped ----------------
        info("2. SKIPPED_INVALID：不进任何分类，只累加 skipped");
        {
            WpsStats s = new WpsStats();
            s.add("SKIPPED_INVALID", 10);
            s.add("SKIPPED_INVALID", 20);
            s.add("SKIPPED_INVALID", 30);
            eql("total 仍为 0", s.total(), 0);
            eql("skippedCount = 3", s.skippedCount(), 3);
            eql("不产生耗时样本", s.sampleCount(), 0);
            eql("avgMs = -1（暂无数据）", s.avgMs(), -1);
            eql("countOf(SKIPPED_INVALID) = 0（不是分类）", s.countOf("SKIPPED_INVALID"), 0);
            eql("countOf(TIMEOUT) = 0", s.countOf(WpsStats.C_TIMEOUT), 0);
            bt("ratioOf(C_TIMEOUT) = -1", s.ratioOf(WpsStats.C_TIMEOUT) == -1);
            ok("非空（有本地跳过记录）", !s.empty(), "skipped>0 也算已记录事件");
            // 只跳过 ≠ 有真实结果：分类占比必须还是「暂无数据」
            ok("summary 无分类占比数据 → 含「暂无数据」",
                    s.summary(true).contains("暂无数据"), s.summary(true));
        }

        // ---------------- 3. total / avg ----------------
        info("3. total / avgMs：100,200,300 → avg=200");
        {
            WpsStats s = new WpsStats();
            s.add("TIMEOUT", 100);
            s.add("TIMEOUT", 200);
            s.add("TIMEOUT", 300);
            eql("total = 3", s.total(), 3);
            eql("avgMs = 200", s.avgMs(), 200);
            eql("countOf(TIMEOUT) = 3", s.countOf(WpsStats.C_TIMEOUT), 3);
            bt("elapsedMs<=0 不算样本（仍进分类）", zeroSample());
        }

        // ---------------- 4. p50 / p90 ----------------
        info("4. p50/p90：真实样本（最近秩法），不足 5 个返回 -1");
        {
            WpsStats s = new WpsStats();
            for (long v : new long[]{10, 20, 30, 40}) s.add("TIMEOUT", v);
            eql("4 个样本 p50 = -1（不足 5 个）", s.p50(), -1);
            eql("4 个样本 p90 = -1（不足 5 个）", s.p90(), -1);
            eql("4 个样本 avg 仍算", s.avgMs(), 25);
        }
        {
            WpsStats s = new WpsStats();
            for (long v = 1; v <= 5; v++) s.add("TIMEOUT", v);
            eql("1..5 p50 = 3", s.p50(), 3);
            eql("1..5 p90 = 5", s.p90(), 5);
        }
        {
            WpsStats s = new WpsStats();
            for (long v = 1; v <= 100; v++) s.add("NO_RESPONSE", v);
            eql("1..100 avg = 50", s.avgMs(), 50);
            eql("1..100 p50 = 50", s.p50(), 50);
            eql("1..100 p90 = 90", s.p90(), 90);
        }

        // ---------------- 5. 样本封顶（滑窗） ----------------
        info("5. 样本上限 5000：超出丢最旧，并标注 truncatedSamples");
        {
            WpsStats s = new WpsStats();
            for (long v = 1; v <= 5100; v++) s.addClass(WpsStats.C_TIMEOUT, v);
            eql("total = 5100（分类计数不受样本窗影响）", s.total(), 5100);
            eql("窗口内样本 = 5000", s.sampleCount(), 5000);
            eql("丢弃数 = 100", s.droppedSamples(), 100);
            ok("samplesTruncated = true", s.samplesTruncated(), "");
            // 保留的是 101..5100
            eql("窗口平均 = 2600", s.avgMs(), 2600);
            eql("窗口 p50 = 2600", s.p50(), 2600);
            eql("窗口 p90 = 4600", s.p90(), 4600);
            ok("summary 含 truncatedSamples 提示",
                    s.summary(true).contains("truncatedSamples=true"), s.summary(true));
            // 封顶后落盘/续写：ring_next 必须往返，否则续写会挤错最旧样本（假数据来源）
            WpsStats d = WpsStats.decode(s.encode());
            eql("封顶后往返 avg 不变", d.avgMs(), 2600);
            eql("封顶后往返 p50 不变", d.p50(), 2600);
            eql("封顶后往返 p90 不变", d.p90(), 4600);
            eql("封顶后往返 dropped 不变", d.droppedSamples(), 100);
            bt("封顶后往返 truncated 仍为 true", d.samplesTruncated());
            d.addClass(WpsStats.C_TIMEOUT, 99999);
            eql("续写后样本仍封顶 5000", d.sampleCount(), 5000);
            eql("续写后 p50 = 2601（101 被挤出）", d.p50(), 2601);
            eql("续写后 p90 = 4601（101 被挤出）", d.p90(), 4601);
        }

        // ---------------- 6. ratioOf ----------------
        info("6. ratioOf：total==0 → -1（不是 0）");
        {
            WpsStats e = new WpsStats();
            boolean all = true;
            for (String c : WpsStats.classes()) if (e.ratioOf(c) != -1) all = false;
            ok("空统计：10 个分类 ratio 全为 -1", all, "");
            bt("空统计 ratioOf(null) = -1", e.ratioOf(null) == -1);
        }
        {
            WpsStats s = new WpsStats();
            s.add("SUCCESS", 100);
            s.add("TIMEOUT", 200);
            s.add("NO_RESPONSE", 300);
            s.add("REJECTED", 400);
            bt("4 条里 1 条已确认 → 0.25", s.ratioOf(WpsStats.C_CONFIRMED) == 0.25);
            bt("4 条里 1 条超时 → 0.25", s.ratioOf(WpsStats.C_TIMEOUT) == 0.25);
            bt("未出现的分类 → 0.0（total>0）", s.ratioOf(WpsStats.C_PROTOCOL) == 0.0);
        }

        // ---------------- 7. 落盘往返 ----------------
        info("7. encode/decode 往返（0 样本 / 含 skipped / 含 lock）");
        {
            WpsStats s = new WpsStats();
            String raw = s.encode();
            ok("首行是 WPS_STATS v=1", raw.startsWith("WPS_STATS v=1\n"), raw);
            WpsStats d = WpsStats.decode(raw);
            ok("0 样本往返：empty() = true", d.empty(), "");
            eq("0 样本往返：total = 0", d.total(), 0);
            eql("0 样本往返：skipped = 0", d.skippedCount(), 0);
            eq("0 样本往返：encode 完全一致", d.encode(), raw);
            ok("成功 decode 后 lastDecodeError 为空", WpsStats.lastDecodeError().isEmpty(),
                    WpsStats.lastDecodeError());
        }
        {
            WpsStats s = new WpsStats();
            s.add("SUCCESS", 1000);
            s.add("TIMEOUT", 2000);
            s.addClass(WpsStats.C_PROTOCOL, 300);
            s.add("SKIPPED_INVALID", 1);
            s.add("SKIPPED_INVALID", 1);
            s.noteLockRisk("AP 连续拒绝（疑似锁定）");
            String raw = s.encode();
            WpsStats d = WpsStats.decode(raw);
            eq("往返：total = 3", d.total(), 3);
            eql("往返：skipped = 2", d.skippedCount(), 2);
            eql("往返：lock = 1", d.lockCount(), 1);
            eq("往返：lockWhy 保留", d.lockWhy(), "AP 连续拒绝（疑似锁定）");
            eql("往返：CONFIRMED = 1", d.countOf(WpsStats.C_CONFIRMED), 1);
            eql("往返：PROTOCOL = 1", d.countOf(WpsStats.C_PROTOCOL), 1);
            eql("往返：TIMEOUT = 1", d.countOf(WpsStats.C_TIMEOUT), 1);
            eql("往返：sampleCount = 3", d.sampleCount(), 3);
            eql("往返：avgMs = 1100", d.avgMs(), 1100);
            eql("往返：样本 3 个 → p50 = -1", d.p50(), -1);
            eql("往返：样本 3 个 → p90 = -1", d.p90(), -1);
            eq("往返：encode 完全一致", d.encode(), raw);
            ok("往返后仍非 empty", !d.empty(), "");
            // 单值编码（值走 WpsHelper.enc）：说明行里不应出现裸换行
            // 期望行数 = 表头 1 + 字段 8 + 分类 10 + 样本 3
            eq("lock_why 不会把文件撑成多行（已编码）", lines(raw.trim()), 1 + 8 + 10 + 3);
        }
        {
            // 0 耗时的 0 样本场景 + 封顶后的 0 样本场景都不能丢字段
            WpsStats s = new WpsStats();
            s.add("CMD_FAIL", 0);
            s.add("ERROR", 0);
            eql("elapsed=0 不计样本", s.sampleCount(), 0);
            eql("elapsed=0 仍计入分类", s.total(), 2);
            WpsStats d = WpsStats.decode(s.encode());
            eq("0 样本（但有分类数据）往返一致", d.encode(), s.encode());
            eql("往返 total = 2", d.total(), 2);
            eql("往返 avgMs = -1", d.avgMs(), -1);
        }

        info("7b. 坏输入：返回空统计 + 非空 lastDecodeError，绝不抛");
        {
            String[] bad = {
                    null,
                    "",
                    "hello world",
                    "WPS_STATS v=1\ntotal=abc",
                    "WPS_STATS v=99\n",
                    "WPS_STATS v=1\ntotal=2\nc_TIMEOUT=1",                       // 分类合计 ≠ total
                    "WPS_STATS v=1\ntotal=1\nc_TIMEOUT=1\nsamples=2\nsample=1",   // 样本数不符
                    "WPS_STATS v=1\ntotal=1\nc_TIMEOUT=1\nsamples=1\nring_next=1\nsample=1\nweird=2",
                    "WPS_STATS v=1\ntotal=1\nc_NOPE=1\nsamples=0\nring_next=0\ntruncated=0\ndropped=0",
                    "WPS_STATS v=1\nnot_a_kv_line",
                    "WPS_STATS v=1\ntotal=0\nsamples=0",                             // 缺 schema
                    "WPS_STATS v=1\nschema=1\ntotal=0\ntruncated=1\ndropped=0\nsamples=0\nring_next=0",
                    "WPS_STATS v=1\nschema=1\ntotal=1\nc_TIMEOUT=1\nsamples=1\nring_next=2\nsample=5",
            };
            for (String in : bad) {
                String shown = in == null ? "null" : (in.length() > 32 ? in.substring(0, 32) + "…" : in);
                WpsStats d;
                boolean threw = false;
                try {
                    d = WpsStats.decode(in);
                } catch (Throwable t) {
                    threw = true;
                    d = null;
                }
                ok("坏输入不抛异常：" + shown, !threw, "decode 抛了异常");
                ok("坏输入 → empty()：" + shown, d != null && d.empty(), "");
                ok("坏输入 → lastDecodeError 非空：" + shown,
                        !WpsStats.lastDecodeError().isEmpty(), WpsStats.lastDecodeError());
                ok("坏输入 → total=0：" + shown, d != null && d.total() == 0, "");
            }
            info("   最后一个原因示例：" + WpsStats.lastDecodeError());
        }

        // ---------------- 8. reconcile 对账 ----------------
        info("8. reconcile：一致返回空串，不一致给出中文说明");
        {
            WpsStats s = new WpsStats();
            for (int i = 0; i < 120; i++) s.add("TIMEOUT", 0);
            String msg = WpsStats.reconcile(s, 118, 0);
            ok("差 2：说明非空", !msg.isEmpty(), msg);
            ok("差 2：含统计数 120", msg.contains("120"), msg);
            ok("差 2：含台账数 118", msg.contains("118"), msg);
            ok("差 2：含差值 2", msg.contains("差 2"), msg);
            info("   示例：" + msg);
            ok("完全一致 → 空串", WpsStats.reconcile(s, 120, 0).isEmpty(),
                    WpsStats.reconcile(s, 120, 0));
            String hitMsg = WpsStats.reconcile(s, 120, 3);
            ok("命中数对不上 → 说明含「命中不一致」", hitMsg.contains("命中不一致"), hitMsg);
            ok("null 统计 → 明确说明不可对账",
                    !WpsStats.reconcile(null, 1, 1).isEmpty(), "");
        }
        {
            WpsStats s = new WpsStats();
            s.add("SUCCESS", 100);
            s.add("TIMEOUT", 200);
            ok("命中一致 → 空串", WpsStats.reconcile(s, 2, 1).isEmpty(),
                    WpsStats.reconcile(s, 2, 1));
            // 半证据 + 锁定不会污染成功口径：台账记 2 条命中而统计只有 1 条已确认 → 必须报出来
            String m = WpsStats.reconcile(s, 2, 2);
            ok("台账命中比统计已确认多 → 报不一致并附半证据/锁定条数",
                    m.contains("命中不一致") && m.contains("半证据"), m);
        }
        {
            WpsStats s = new WpsStats();
            s.add("TIMEOUT", 100);
            s.noteLockRisk("beacon 里出现 LOCKED");
            ok("锁定计数对得上 → 空串", WpsStats.reconcile(s, 1, 0, 1).isEmpty(),
                    WpsStats.reconcile(s, 1, 0, 1));
            String m = WpsStats.reconcile(s, 1, 0, 3);
            ok("锁定计数对不上 → 说明含「锁定风险不一致」",
                    m.contains("锁定风险不一致"), m);
            ok("不传锁定列（-1）时不校验锁定", WpsStats.reconcile(s, 1, 0).isEmpty(),
                    WpsStats.reconcile(s, 1, 0));
        }

        // ---------------- 9. summary / detail 文案 ----------------
        info("9. summary/detail：没数据必须说「暂无数据」");
        {
            WpsStats e = new WpsStats();
            String zh = e.summary(true);
            String en = e.summary(false);
            ok("空统计 summary(zh) 含「暂无数据」", zh.contains("暂无数据"), zh);
            ok("空统计 summary(en) 含 no data", en.contains("no data"), en);
            ok("空统计 summary 标注 truncatedSamples",
                    zh.contains("truncatedSamples=false"), zh);
            String dz = e.detail(true);
            eq("detail 恰好 10 行", lines(dz), 10);
            ok("detail(zh) 含「暂无数据」", dz.contains("暂无数据"), dz);
            eq("detail(en) 恰好 10 行", lines(e.detail(false)), 10);
            info("   空统计 detail 首行：" + dz.split("\n")[0]);
        }
        {
            WpsStats s = new WpsStats();
            s.add("SUCCESS", 100);
            s.add("HALF_OK", 200);
            s.add("REJECTED", 300);
            s.add("TIMEOUT", 400);
            s.add("NO_RESPONSE", 500);
            s.add("LINK_LOST", 600);
            s.noteLockRisk("两次 M6 后 AP 不再应答");
            String zh = s.summary(true);
            ok("有数据 summary 含总计 6", zh.contains("总计 6"), zh);
            ok("有数据 summary 含百分比", zh.contains("16.7%"), zh);
            ok("有数据 summary 含平均耗时 350 ms", zh.contains("平均耗时 350 ms"), zh);
            ok("有数据 summary 含 P50 300", zh.contains("P50 300 ms"), zh);
            ok("有数据 summary 含 P90 600", zh.contains("P90 600 ms"), zh);
            ok("summary 单列锁定风险 1 次", zh.contains("锁定风险 1 次"), zh);
            ok("summary 带上最近锁定原因", zh.contains("两次 M6 后 AP 不再应答"), zh);
            ok("有数据时不再说「暂无数据」", !zh.contains("暂无数据"), zh);
            String dz = s.detail(true);
            eq("有数据 detail 恰好 10 行", lines(dz), 10);
            ok("detail 含已确认行", dz.contains("已确认（CONFIRMED）：1 条 · 16.7%"), dz);
            ok("detail 含锁定风险行", dz.contains("锁定风险（LOCK_RISK）：1 次"), dz);
            info("   summary 第 1 行：" + zh.split("\n")[0]);
            info("   detail 第 1 行：" + dz.split("\n")[0]);
        }

        // ---------------- 10. 线程安全冒烟（synchronized） ----------------
        info("10. 并发累加不丢计数（同一把锁）");
        {
            final WpsStats s = new WpsStats();
            Thread[] ts = new Thread[4];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(new Runnable() {
                    public void run() {
                        for (int k = 0; k < 500; k++) s.add("TIMEOUT", 10);
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    // 忽略中断：主线程继续核对计数
                }
            }
            eql("4×500 并发 → total = 2000", s.total(), 2000);
            eql("4×500 并发 → 样本 = 2000", s.sampleCount(), 2000);
            eql("4×500 并发 → p50 = 10", s.p50(), 10);
        }

        System.out.println();
        System.out.println("=== WpsStatsTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** elapsedMs<=0 视为「没有测量」：仍进分类，但不进耗时样本。 */
    private static boolean zeroSample() {
        WpsStats s = new WpsStats();
        s.add("CMD_FAIL", 0);
        s.add("ERROR", -5);
        return s.total() == 2 && s.sampleCount() == 0 && s.avgMs() == -1 && s.p50() == -1;
    }
}
