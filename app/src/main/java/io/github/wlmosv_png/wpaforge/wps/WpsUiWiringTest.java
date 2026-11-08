package io.github.wlmosv_png.wpaforge.wps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * 第二阶段 UI 接线守卫：**WPS 页必须真的用上新逻辑**。
 *
 * <h3>为什么需要它</h3>
 * 底层逻辑已经由 WpsHitAttributionTest / WpsCursorAccountingTest /
 * WpsCheckpoint*Test 测到位，但那些测试管不到 {@code MainActivity}（13000 行，
 * 依赖 Android API，离线跑不起来）。历史上这块出过不止一次
 * 「逻辑修好了、页面没接线」和「接线回退到旧写法」的事故。
 *
 * <p>所以这里照 {@code P3ResumeWiringTest} 的做法，**直接扫源码文本**：
 * 老 bug 的形状出现即判红，新逻辑的接线缺失也判红。
 */
public final class WpsUiWiringTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static String read(String pkgRelative) throws IOException {
        return new String(Files.readAllBytes(
                Paths.get(TestPaths.javaSrcRoot() + pkgRelative)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WPS 页接线守卫（第二阶段）===");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");

        // ---------------------------------------------------------- 1 游标记账
        info("① 游标不得再按请求数推进");
        ok("老的 halfCursor += pins.size() 已消失",
                !ma.contains("halfCursor += pins.size()"), "仍然存在按请求数推进的写法");
        ok("完整枚举也不再按 chunk.size() 推进",
                !ma.contains("wpsEnumCursor += chunk.size()"), "仍存在");
        ok("拆半主循环用 WpsBatchPlan.account", ma.contains("WpsBatchPlan.account(pins, rs)"),
                "未接线");
        // 2026-11-08：完整枚举已从生产移除（10^7 不可行 + 内存风险）
        ok("完整枚举入口已移除",
                !ma.contains("wpsMode == 4") && !ma.contains("enumerateRange(wpsEnumCursor"),
                "完整枚举入口仍存在");
        ok("runWpsBatch 返回实际处理结果",
                ma.contains("private java.util.List<io.github.wlmosv_png.wpaforge.wps."
                        + "WpsController.Result> runWpsBatch("), "签名未改");

        // ---------------------------------------------------------- 2 阶段守卫
        info("② 阶段 2 不接受空前缀");
        ok("阶段 2 入口有前缀长度守卫",
                ma.contains("wpsSession.halfPrefix.length() != 4"), "缺守卫");

        // ---------------------------------------------------------- 3 断点存储
        info("③ 断点走 schema 化存储（commit + 备份 + 目标校验）");
        ok("用 WpsCheckpointStore", ma.contains("wpsCpStore()"), "未接线");
        ok("写入用 commit()（apply 拿不到结果）", ma.contains(".commit();"), "仍在用 apply()");
        ok("不再用老 5 个 key 直接读写",
                !ma.contains("putInt(\"wpsHalfPhase\"") && !ma.contains("remove(\"wpsHalfBssid\")"),
                "仍有旧写法");
        ok("loadHalfState 返回可读原因",
                ma.contains("private String loadHalfState()"), "签名未改");
        ok("不能续跑时明确告知原因",
                ma.contains("不能续跑"), "缺提示");
        ok("断点写入失败会提示", ma.contains("断点写入失败"), "缺提示");

        // ---------------------------------------------------------- 4 恢复按钮
        info("④ 「继续」只在真能续跑时出现");
        ok("按钮判据用 wpsResumeReady", ma.contains("boolean canResume = wpsResumeReady();"),
                "未接线");
        ok("wpsResumeReady 用同一套 loadFor 校验",
                ma.contains("loadFor(wpsPicked.bssid, wpsTargetSsidForMatch(), wpsMode)"),
                "校验口径不一致");
        ok("老 hasWpsCheckpoint 已移除", !ma.contains("hasWpsCheckpoint()"), "仍有残留");
        ok("存在断点状态行 wpsResumeLine", ma.contains("wpsResumeLine"), "无");

        // ---------------------------------------------------------- 5 重置安全
        info("⑤ 重置：二次确认 + 只清断点 + 不删历史");
        ok("重置按钮走确认流程", ma.contains("setOnClickListener(v -> confirmResetWps())"),
                "未接线");
        ok("确认对话框存在", ma.contains("重置 WPS 进度？"), "无");
        ok("只清断点", ma.contains("wpsCpStore().clear()"), "无");
        ok("不删台账（doResetWps 里没有 deleteTask）",
                !ma.contains("deleteTask"), "出现了删除历史调用");
        ok("老 resetHalfState（会清空整个 prefs）已删除",
                !ma.contains("resetHalfState"), "仍存在（有清空用户数据风险）");
        ok("确认框写明「不清历史」", ma.contains("不会清除：历史任务与命中记录"), "缺说明");

        // ---------------------------------------------------------- 6 台账
        info("⑥ 台账：开任务 / 同步 / 收尾都接线");
        ok("开任务", ma.contains("wpsTaskStart(wpsPicked.bssid"), "未接线");
        ok("同步进度与最近一条", ma.contains("wpsTaskSync(") , "未接线");
        ok("收尾写状态与原因", ma.contains("wpsTaskFinish("), "未接线");
        ok("命中时写证据到台账", ma.contains("WpsEngine.evidenceSummary(wpsSession)"), "未接线");
        ok("终止原因区分暂停/异常/命中",
                ma.contains("WpsLedger.ST_PAUSED") && ma.contains("WpsLedger.ST_FAILED"),
                "未区分");
        ok("历史入口", ma.contains("showWpsHistoryDialog"), "无");
        ok("证据入口", ma.contains("showWpsEvidenceDialog"), "无");

        // ---------------------------------------------------------- 7 显示口径
        info("⑦ 显示与执行口径一致");
        ok("阶段分母来自 phaseTotal",
                ma.contains("WpsCandidates.phaseTotal(s.halfPhase)"), "未接线");
        ok("不再写死 1000/10000 三目",
                !ma.contains("s.halfPhase == 2 ? 1000 : 10000"), "仍有写死分母");
        ok("显示最近 PIN 与结果", ma.contains("s.lastPin"), "无");
        ok("能力声明与命中分开表述", ma.contains("AP 能力声明"), "无");
        ok("疑似/他网计数可见",
                ma.contains("s.suspectCount") && ma.contains("s.notTargetCount"), "无");
        ok("命中时显示证据摘要", ma.contains("evidenceSummary(s)"), "无");

        // ---------------------------------------------------------- 8 证据链接线
        info("⑧ 命中证据链接线");
        ok("批量调用带目标 SSID",
                ma.contains("this, wpsPicked.bssid, wpsTargetSsidForMatch(), pins,"), "未接线");
        ok("命中日志走证据文案", ma.contains("已命中（协议确认 + 目标归属成立）"), "无");
        ok("疑似命中在日志里说明不判命中",
                ma.contains("疑似命中（证据不足，已记录、不判命中）"), "无");

        System.out.println();
        System.out.println("=== WpsUiWiringTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
