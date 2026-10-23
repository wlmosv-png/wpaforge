package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.ui.CandidateTicker;
import io.github.wlmosv_png.wpaforge.ui.CrackHud;
import io.github.wlmosv_png.wpaforge.ui.LogFilter;

import java.util.List;

/**
 * 破解页改版（方案 A · 驾驶舱）的纯逻辑测试。
 *
 * <p>这一页的改动集中在「信息层级」：把同一件事说三遍的进度收成一条、
 * 把 ETA 提成主视觉、把正在试的候选做成等宽大字、把日志按重要性分级。
 * 数字怎么算、窗口怎么滚、哪一行算关键 —— 全在这里钉住，
 * 界面那边只剩「把字符串放上去」。
 */
public class CrackHudTest {

    private static int pass = 0, fail = 0;
    private static String section = "";

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL [" + section + "] " + name); }
    }

    private static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) System.out.println("       期望 " + want + "，实际 " + got);
        check(name, ok);
    }

    public static void main(String[] args) {
        // ══════════ CandidateTicker ══════════
        section = "候选窗口";
        System.out.println("== 候选滚动窗口 ==");
        CandidateTicker t = new CandidateTicker(3);
        eq("空窗口 current 为 null", null, t.current());
        check("空窗口 previous 为空", t.previous(2).isEmpty());

        t.push("12345678");
        eq("第 1 条就是 current", "12345678", t.current());
        check("只有一条时没有 previous", t.previous(2).isEmpty());

        t.push("  ");           // 空白忽略
        t.push(null);           // null 忽略
        eq("空串/null 不占格子", "12345678", t.current());

        t.push("password");
        t.push("88888888");
        eq("满窗口后 current 是最新一条", "88888888", t.current());
        List<String> prev = t.previous(2);
        eq("previous 有 2 条", 2, prev.size());
        eq("previous[0] 是上一条", "password", prev.get(0));
        eq("previous[1] 再上一条", "12345678", prev.get(1));

        t.push("99999999");     // 绕回，最老的被挤掉
        eq("绕回后 current", "99999999", t.current());
        eq("绕回后 previous[1]", "password", t.previous(2).get(1));
        eq("窗口只保留 3 条", 3, t.count());
        check("previous 请求超过容量不越界", t.previous(99).size() == 2);

        t.push("  aaaa1111  ");  // 前后空白会 trim
        eq("push 会 trim", "aaaa1111", t.current());

        t.clear();
        eq("clear 后 current 为 null", null, t.current());
        eq("clear 后 count 归零", 0, t.count());

        CandidateTicker tiny = new CandidateTicker(1);
        tiny.push("x");
        tiny.push("y");
        eq("size=1 时只留最新", "y", tiny.current());
        eq("size=1 时 previous 恒空", 0, tiny.previous(1).size());

        // ══════════ CrackHud ══════════
        section = "HUD 数字";
        System.out.println("== HUD 数字 ==");
        eq("千位分隔 13255", "13,255", CrackHud.thousands(13255));
        eq("千位分隔 999", "999", CrackHud.thousands(999));
        eq("千位分隔 1000", "1,000", CrackHud.thousands(1000));
        eq("千位分隔 1234567", "1,234,567", CrackHud.thousands(1234567));
        eq("负数按 0", "0", CrackHud.thousands(-5));
        eq("0", "0", CrackHud.thousands(0));

        eq("百分比 2/13255", "0.0%", CrackHud.percentLabel(2, 13255));
        eq("百分比 662/13255", "5.0%", CrackHud.percentLabel(662, 13255));
        eq("百分比 total=0 不除零", "0.0%", CrackHud.percentLabel(5, 0));
        eq("百分比封顶 100", "100.0%", CrackHud.percentLabel(999, 100));
        eq("百分比负数夹到 0", "0.0%", CrackHud.percentLabel(-3, 100));

        eq("计数行", "1,204 / 13,255", CrackHud.countsLabel(1204, 13255));

        eq("速率 0.09 条/秒 → 条/分", "5.4 条/分", CrackHud.ratePerMin(0.09));
        eq("速率 0.4 条/秒 → 24 条/分", "24 条/分", CrackHud.ratePerMin(0.4));
        eq("速率 0 → 空串（不编 0 出来，也不打占位符）", "", CrackHud.ratePerMin(0));
        eq("速率负数 → 空串", "", CrackHud.ratePerMin(-1));

        eq("脚注", "1,204 / 13,255 · 5.4 条/分", CrackHud.footnote(1204, 13255, 0.09));
        eq("速率未知时脚注只有数量（不打 — 占位）", "15 / 13,255", CrackHud.footnote(15, 13255, 0));

        eq("ETA 45 秒", "剩 45 秒", CrackHud.etaLabel(45_000));
        eq("ETA 9 分", "剩 9 分", CrackHud.etaLabel(9 * 60_000L));
        eq("ETA 2 小时 09 分", "剩 2 小时 9 分", CrackHud.etaLabel(2 * 3600_000L + 9 * 60_000L));
        eq("ETA 未知给空串", "", CrackHud.etaLabel(-1));

        // ══════════ LogFilter ══════════
        section = "日志分级";
        System.out.println("== 日志分级 ==");
        Object[][] cases = {
            // 真实日志原文 → 期望分级
            {"===== 开始任务: 2.4G-3 =====", LogFilter.Kind.MILESTONE},
            {"⚡ 密钥命中：12345678（证据: wpalog）", LogFilter.Kind.MILESTONE},
            {"已暂停，进度 173/3336", LogFilter.Kind.MILESTONE},
            {"已停止，进度 5/2147", LogFilter.Kind.MILESTONE},
            {"任务完成，未命中（试了 3336 条）", LogFilter.Kind.MILESTONE},
            {"从第 158 条继续", LogFilter.Kind.MILESTONE},
            {"队列已结束", LogFilter.Kind.MILESTONE},
            {"任务「2.4G-3」处于暂停，跳过", LogFilter.Kind.MILESTONE},
            {"✓ 成功  12345678  (712ms 握手完成且已获取 IP（确认连上）)", LogFilter.Kind.MILESTONE},

            {"早期失败(第二级): M1 后 600ms 无握手进展", LogFilter.Kind.REASON},
            {"快速失败(回连): 系统已连上 B212", LogFilter.Kind.REASON},
            {"连续多次命令超时 —— 通道已被系统限制，任务已暂停。", LogFilter.Kind.REASON},
            {"旧配置未清除（netId=613），本条结果不可信", LogFilter.Kind.REASON},
            {"常驻 shell 会话不可用，已退回逐条 su（会慢一些）", LogFilter.Kind.REASON},

            {"✗  88888888  (5123ms 系统已回连到其它网络 B212 —— 目标这次没连上)", LogFilter.Kind.REASON},
            {"✗  password  (10000ms 超时)", LogFilter.Kind.REASON},
            {"已启用静音音频保活（防止后台被系统冻结）", LogFilter.Kind.DETAIL},
            {"已显示保活悬浮窗（1×1 透明，防止后台被系统冻结）", LogFilter.Kind.DETAIL},
            {"加载字典 07 通用口令榜 TOP 3336 -> 3336 条", LogFilter.Kind.DETAIL},
            {"目标派生: 已关闭", LogFilter.Kind.DETAIL},
            {"引擎模式: Root", LogFilter.Kind.DETAIL},
            {"  近 20 条 · 均 2342ms（前置 443 · netId 222 · 轮询 375 · 读日志 623）轮 10 均 34ms", LogFilter.Kind.DETAIL},
            {"  监听器诊断: fired 2x, SSID unavailable every time", LogFilter.Kind.DETAIL},
            {"  所有密码都会被判为「无法确认」，请不要误以为密码不对。", LogFilter.Kind.DETAIL},
            {"", LogFilter.Kind.DETAIL},
        };
        for (Object[] c : cases) {
            LogFilter.Kind got = LogFilter.classify((String) c[0]);
            boolean ok = got == c[1];
            if (!ok) System.out.println("       行: " + c[0] + " → " + got + "（期望 " + c[1] + "）");
            check("分级 " + ((String) c[0]).substring(0, Math.min(18, ((String) c[0]).length())), ok);
        }

        check("关键视图含里程碑", LogFilter.visible(LogFilter.Kind.MILESTONE, true));
        check("关键视图含失败原因", LogFilter.visible(LogFilter.Kind.REASON, true));
        check("关键视图不含明细", !LogFilter.visible(LogFilter.Kind.DETAIL, true));
        check("全部视图含明细", LogFilter.visible(LogFilter.Kind.DETAIL, false));
        check("null 不炸", LogFilter.classify(null) == LogFilter.Kind.DETAIL);

        System.out.println();
        System.out.println("破解页 HUD: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
