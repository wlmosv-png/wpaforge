package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.RunLog;

import java.util.List;

/**
 * 运行记录的**四频道 + 结构化记录**。
 *
 * <h3>要钉死的事情</h3>
 * <ul>
 *   <li>离线校验 / 口令强度 / 字典操作 / 其他，四个频道互不干扰；</li>
 *   <li>每条记录都带时间、级别（= 状态）、操作对象、简短结果、详细日志；</li>
 *   <li>清空**只清指定频道**，而且不碰结果卡（结果卡在 OfflineRunStore，本类只证明
 *       RunLog 自己不负责结果卡）；</li>
 *   <li>环形上限生效，不会无限增长。</li>
 * </ul>
 */
public class RunChannelTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    public static void main(String[] args) {
        System.out.println("== 四个频道互不干扰 ==");
        RunLog log = new RunLog();
        log.offline.info(1000, "离线 A");
        log.strength.info(1000, "强度 B");
        log.dict.info(1000, "字典 C");
        log.other.info(1000, "其他 D");
        log.offline.fail(2000, "离线 E");
        eq("离线 2 条", log.offline.size(), 2);
        eq("强度 1 条", log.strength.size(), 1);
        eq("字典 1 条", log.dict.size(), 1);
        eq("其他 1 条", log.other.size(), 1);
        eq("离线第一条文本", log.offline.snapshot().get(0).text, "离线 A");
        eq("字典第一条文本", log.dict.snapshot().get(0).text, "字典 C");
        ok("强度没被离线写入污染", log.strength.snapshot().get(0).text.equals("强度 B"));

        System.out.println("== channel(Op) 映射 ==");
        eq("OFFLINE", log.channel(RunLog.Op.OFFLINE), log.offline);
        eq("STRENGTH", log.channel(RunLog.Op.STRENGTH), log.strength);
        eq("DICT", log.channel(RunLog.Op.DICT), log.dict);
        eq("OTHER", log.channel(RunLog.Op.OTHER), log.other);
        eq("null -> other", log.channel(null), log.other);
        eq("频道自报类型 OFFLINE", log.offline.op(), RunLog.Op.OFFLINE);
        eq("频道自报类型 DICT", log.dict.op(), RunLog.Op.DICT);

        System.out.println("== 记录自带操作类型（不是靠界面记）==");
        eq("offline 里的记录 op", log.offline.snapshot().get(0).op, RunLog.Op.OFFLINE);
        eq("strength 里的记录 op", log.strength.snapshot().get(0).op, RunLog.Op.STRENGTH);

        System.out.println("== 结构化记录：时间 / 状态 / 对象 / 简短结果 / 详细日志 ==");
        RunLog l2 = new RunLog();
        l2.dict.add(3600_000L, RunLog.Kind.OK, "07 通用口令榜",
                "3336 条候选 · 内容指纹 AB12CD",
                "名称: 07 通用口令榜\n候选数: 3336\n内容指纹: AB12CD");
        RunLog.Line ln = l2.dict.snapshot().get(0);
        eq("时间 = 01:00:00", ln.clock(), "01:00:00");
        eq("状态 OK", ln.status(), "OK");
        eq("操作对象", ln.subject, "07 通用口令榜");
        eq("简短结果", ln.text, "3336 条候选 · 内容指纹 AB12CD");
        ok("有详细日志", ln.hasDetail());
        ok("详细日志比简短结果长", ln.detail.length() > ln.text.length());

        System.out.println("== 状态文案覆盖全部级别 ==");
        RunLog l3 = new RunLog();
        l3.offline.add(0, RunLog.Kind.INFO, "", "i", null);
        l3.offline.add(0, RunLog.Kind.PROGRESS, "", "p", null);
        l3.offline.add(0, RunLog.Kind.OK, "", "o", null);
        l3.offline.add(0, RunLog.Kind.WARN, "", "w", null);
        l3.offline.add(0, RunLog.Kind.FAIL, "", "f", null);
        l3.offline.add(0, RunLog.Kind.CANCEL, "", "c", null);
        List<RunLog.Line> ls = l3.offline.snapshot();
        eq("INFO 状态", ls.get(0).status(), "INFO");
        eq("PROGRESS 状态", ls.get(1).status(), "RUN");
        eq("OK 状态", ls.get(2).status(), "OK");
        eq("WARN 状态", ls.get(3).status(), "WARN");
        eq("FAIL 状态", ls.get(4).status(), "FAIL");
        eq("CANCEL 状态", ls.get(5).status(), "CANCEL");
        ok("没有详细日志时 hasDetail=false", !ls.get(0).hasDetail());
        eq("没有详细日志时 detail 退化成 text", ls.get(0).detail, "i");
        eq("状态文案是纯 ASCII（不随语言变）", RunLog.statusText(null), "INFO");

        System.out.println("== 清空只清指定频道 ==");
        RunLog l4 = new RunLog();
        l4.offline.info(0, "o");
        l4.strength.info(0, "s");
        l4.dict.info(0, "d");
        l4.other.info(0, "x");
        l4.clearChannel(RunLog.Op.DICT);
        eq("字典被清空", l4.dict.size(), 0);
        eq("离线还在", l4.offline.size(), 1);
        eq("强度还在", l4.strength.size(), 1);
        eq("其他还在", l4.other.size(), 1);

        RunLog l5 = new RunLog();
        l5.offline.info(0, "o");
        l5.strength.info(0, "s");
        l5.dict.info(0, "d");
        l5.other.info(0, "x");
        l5.clearAll();
        ok("clearAll 清四个频道",
                l5.offline.size() + l5.strength.size() + l5.dict.size() + l5.other.size() == 0);

        System.out.println("== 环形上限 ==");
        RunLog l6 = new RunLog();
        for (int i = 0; i < 500; i++) l6.offline.info(i, "line" + i);
        eq("不超过上限", l6.offline.size(), RunLog.Channel.DEFAULT_CAP);
        eq("最早被丢掉的是靠前的", l6.offline.snapshot().get(0).text,
                "line" + (500 - RunLog.Channel.DEFAULT_CAP));

        System.out.println("== 负时间戳不炸 ==");
        ok("负时间戳 clock 安全", new RunLog.Line(-5, RunLog.Kind.INFO, "x").clock()
                .equals("00:00:00"));
        ok("旧构造器仍可用（只有 at/kind/text）",
                new RunLog.Line(0, RunLog.Kind.INFO, "y").op == RunLog.Op.OTHER);
        ok("null kind 退化为 INFO", new RunLog.Line(0, null, "z").kind == RunLog.Kind.INFO);

        System.out.println("== 快照是拷贝（界面遍历时不受并发影响）==");
        RunLog l7 = new RunLog();
        l7.offline.info(0, "a");
        List<RunLog.Line> snap = l7.offline.snapshot();
        l7.offline.info(0, "b");
        eq("快照长度不变", snap.size(), 1);
        eq("新记录进了频道", l7.offline.size(), 2);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
