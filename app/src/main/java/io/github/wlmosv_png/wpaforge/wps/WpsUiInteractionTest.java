package io.github.wlmosv_png.wpaforge.wps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import io.github.wlmosv_png.wpaforge.TestPaths;

/**
 * WPS 页 UI 与交互接线守卫（源码文本级）。
 *
 * <p>MainActivity 依赖 Android API，离线跑不起来；这里照 {@link WpsUiWiringTest}
 * 的做法扫源码文本，把「UI 与真实业务是否接线」的契约固定下来：
 * 停止按钮真的能停、状态标签真的刷新、弹窗真的走统一组件、帮助入口真的存在。
 */
public final class WpsUiInteractionTest {

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
        System.out.println("=== WPS 页 UI / 交互 接线守卫 ===");
        String ma = read("io/github/wlmosv_png/wpaforge/MainActivity.java");

        // ---------------------------------------------------------- 1 停止按钮
        info("① 停止按钮必须真的能停（P0）");
        ok("运行状态的唯一权威是 WpsRunState", ma.contains("wpsRun =")
                && ma.contains("new io.github.wlmosv_png.wpaforge.wps.WpsRunState()"), "未接线");
        ok("旧的裸布尔 wpsRunning 已移除", !ma.contains("wpsRunning"), "仍存在裸字段");
        ok("停止按钮在运行中可用（不被禁用）",
                ma.contains("wpsStopBtn.setEnabled(!wpsRun.isStopping())"), "运行中仍被禁用");
        ok("停止按钮显示「停止中…」", ma.contains("wpsStopBtn.setText(wpsRun.isStopping()"), "无");
        ok("stopWps 走状态机 requestStop", ma.contains("wpsRun.requestStop()"), "未接线");
        ok("停止请求写真实取消信号", ma.contains("wpsCancel = true;"), "未设置取消信号");
        ok("worker 退出复位状态机", ma.contains("wpsRun.workerExited()"), "未复位");

        // ---------------------------------------------------------- 2 扫描/能力状态同步
        info("② 扫描完成 / 选中目标后状态标签必须刷新（不再一律「待检测」）");
        ok("能力标签由真实字段映射", ma.contains("private int wpsCapStateOf("), "无");
        ok("标签区分「能力未知」与「不支持」",
                ma.contains("WPS_TAG_UNKNOWN") && ma.contains("WPS_TAG_NO_PIN")
                        && ma.contains("WPS_TAG_OK") && ma.contains("WPS_TAG_LOCKED"), "未区分");
        ok("能力未知不写成成功", ma.contains("能力未知") && !ma.contains("WPS_TAG_OK = 0"), "");
        ok("顶部目标卡 SSID/BSSID 是成员字段（可刷新）",
                ma.contains("private TextView wpsTargetSsidV;")
                        && ma.contains("private TextView wpsTargetBssidV;"), "仍是局部变量");
        ok("扫描完成回调刷新目标卡", ma.contains("refreshWpsPickedMeta();"), "未接线");

        // ---------------------------------------------------------- 3 扫描优先（不阻断）
        info("③ 扫描优先：无目标不阻断页面、不禁用扫描");
        ok("空态引导去扫描（而非「未选择目标」阻断）",
                ma.contains("尚未扫描 —— 点上方「扫描 AP」发现附近网络"), "仍是阻断式文案");
        ok("扫描按钮始终可点（无禁用语句）",
                !ma.contains("scanBtn.setEnabled(false)"), "扫描按钮被禁用");
        ok("进页自动扫描一次", ma.contains("doWpsScan();"), "未自动扫描");

        // ---------------------------------------------------------- 4 统一弹窗
        info("④ WPS 弹窗统一走 NetDialog（不再用默认 AlertDialog）");
        ok("历史走 NetDialog.textInfo",
                ma.contains("NetDialog.textInfo(this, L.s(\"WPS 历史任务\""), "未统一");
        ok("证据走 NetDialog.textInfo",
                ma.contains("NetDialog.textInfo(this, L.s(\"命中证据\""), "未统一");
        ok("运行记录走 NetDialog.textInfo",
                ma.contains("NetDialog.textInfo(this, L.s(\"运行记录\""), "未统一");
        ok("重置走 NetDialog(danger)",
                ma.contains("NetDialog(this,\n                L.s(\"重置 WPS 进度？\""), "未统一");
        ok("导入走 NetDialog(addView)",
                ma.contains("pasteDlg.addView(box)"), "未统一");
        ok("手动添加走 NetDialog(addView)", ma.contains("addDlg.addView(in)"), "未统一");
        ok("半拆确认走 NetDialog", ma.contains("halfDlg.setPositive("), "未统一");

        // ---------------------------------------------------------- 5 帮助入口
        info("⑤ 问号帮助入口 + 内容完整");
        ok("帮助入口存在", ma.contains("wpsHeader.addView(helpBtn"), "无");
        ok("帮助打开方法存在", ma.contains("private void showWpsHelpDialog()"), "无");
        ok("帮助走统一组件", ma.contains("NetDialog.textInfo(this,\n                    L.s(\"WPS PIN 弱点攻击 · 说明\""), "未统一");
        ok("帮助含「WPS PIN 是什么」", ma.contains("1. WPS PIN 是什么"), "缺条目");
        ok("帮助含「Root/通道正常≠目标支持」", ma.contains("Root 和 supplicant 通道正常"), "缺条目");
        ok("帮助含「扫描到 WPS 不代表一定能验证」", ma.contains("扫描到 WPS，不代表一定能验证"), "缺条目");
        ok("帮助含授权范围", ma.contains("仅在你本人拥有"), "缺条目");

        // ---------------------------------------------------------- 6 命名与措辞
        info("⑥ 命名与措辞（不承诺成功率）");
        ok("页面标题为「WPS PIN 弱点攻击」",
                ma.contains("WPS PIN 弱点攻击"), "未改名");
        ok("不再出现「11000 次必中」", !ma.contains("11000 次必中"), "仍有确定性措辞");
        ok("不出现「必中/guaranteed」",
                !ma.contains("必中") && !ma.contains("11k, guaranteed"), "仍有");

        // ---------------------------------------------------------- 7 目标/扫描状态机接线
        info("⑦ 目标与扫描状态由 WpsTargetManager 统一持有（UI 不自行推断）");
        ok("MainActivity 使用 WpsTargetManager",
                ma.contains("new io.github.wlmosv_png.wpaforge.wps.WpsTargetManager()"), "未接线");
        ok("裸布尔 wpsScanning 已移除", !ma.contains("wpsScanning"), "仍有裸字段");
        ok("裸时间戳 wpsScanAt 已移除", !ma.contains("wpsScanAt"), "仍有裸字段");
        ok("裸失败原因 wpsScanFailReason 已移除", !ma.contains("wpsScanFailReason"), "仍有裸字段");
        ok("裸选中字段 wpsPicked 已移除", !ma.contains("WpsController.Target wpsPicked;"), "仍有裸字段");
        ok("扫描开始走状态机 beginScan", ma.contains("wpsTargetMgr.beginScan()"), "未接线");
        ok("扫描成功收尾走 finishScan", ma.contains("wpsTargetMgr.finishScan("), "未接线");
        ok("扫描失败走 failScan", ma.contains("wpsTargetMgr.failScan("), "未接线");
        ok("选中走 select()", ma.contains("wpsTargetMgr.select("), "未接线");
        ok("空态读 isScanning()", ma.contains("wpsTargetMgr.isScanning()"), "未接线");

        // ---------------------------------------------------------- 8 日志
        info("⑦ 结构化详细日志");
        ok("日志带时间戳", ma.contains("DateFormat\n                .format(\"HH:mm:ss\""), "无时间戳");
        ok("日志记录任务创建", ma.contains("\"[任务] 创建 \""), "无");
        ok("日志记录目标身份", ma.contains("\"[目标] SSID=\""), "无");
        ok("日志记录策略", ma.contains("\"[策略] \""), "无");
        ok("日志记录能力检测", ma.contains("\"[能力] 检测完成"), "无");
        ok("日志记录停止请求", ma.contains("\"[停止] 请求已发出"), "无");
        ok("日志记录最终结果与等级", ma.contains("\"[结果] 判定=\""), "无");

        System.out.println();
        System.out.println("=== WpsUiInteractionTest: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
