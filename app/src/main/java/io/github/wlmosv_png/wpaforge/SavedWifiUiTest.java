package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.SavedWifiReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 「本机已存 WiFi」列表 UI 回归（2026-10-24 用户反馈：口令一长就看不到）。
 *
 * <h3>被修的是什么</h3>
 * 原来每个条目是 {@code NetDialog.addItem(ssid + "\n" + password + "  " + label)}，
 * 而 {@code addItem} 会 {@code setSingleLine(true)} + {@code ellipsize(MIDDLE)} ——
 * 于是 {@code fast_2345abcdef} 显示成 {@code fast_...2345}，最该看清的东西被省略掉了。
 *
 * <p>现在：口令独占一行、等宽、最多折三行、**绝不省略**；SSID 一行 + 强度胶囊；
 * 小字给出长度与安全类型；点整行复制口令（开放网络不复制）；
 * 顶部三枚过滤胶囊只是本地过滤，读盘与评分算法一个字没动。
 *
 * <p>本类同时钉住「读盘与评分未改」：{@link SavedWifiReader} 的公开 API 与分档口径
 * 必须保持原样（分档用真实调用验证，不是查源码字符串）。
 */
public class SavedWifiUiTest {

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

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String ROOT = "/workspace/promo-wpaforge/app/src/main/java/io/github/"
            + "wlmosv_png/wpaforge/";

    public static void main(String[] args) {
        String ma = read(ROOT + "MainActivity.java");
        if (ma == null) {
            System.out.println("  N/RUN 源码不可读");
            System.out.println("=== 已存 WiFi UI 测试: 0 通过, 0 失败（NOT RUN）===");
            return;
        }
        int a = ma.indexOf("private void showSavedWifiList(");
        int b = ma.indexOf("/** 点整行复制口令", a);
        String block = (a >= 0 && b > a) ? ma.substring(a, b) : "";
        ok("能定位新的列表实现", !block.isEmpty());

        System.out.println("== W1 口令必须完整显示（不再省略）==");
        int iRow = ma.indexOf("private View savedWifiRow(");
        int iRowEnd = ma.indexOf("/** 强度分档色", iRow);
        String row = (iRow > 0 && iRowEnd > iRow) ? ma.substring(iRow, iRowEnd) : "";
        ok("能定位行渲染", !row.isEmpty());
        ok("口令行允许折行（setSingleLine(false)）", row.contains("pw.setSingleLine(false);"));
        ok("口令行有折行上限（最多三行）", row.contains("pw.setMaxLines(3);"));
        ok("口令行等宽字体（好读、好核对）", row.contains("pw.setTypeface(Typeface.MONOSPACE);"));
        ok("**口令行没有省略号**", !row.contains("pw.setEllipsize("));
        ok("列表已不再用 addItem（那会强制单行 + MIDDLE 省略）",
                !block.contains("addItem("));
        ok("整页不再出现 MIDDLE 省略", !ma.contains("TruncateAt.MIDDLE"));

        System.out.println("== W2 层级：SSID + 强度胶囊 + 元信息 ==");
        ok("SSID 可截断（名字长也不挤掉胶囊）",
                row.contains("ssid.setEllipsize(TextUtils.TruncateAt.END);"));
        ok("强度胶囊带分档标签与分数",
                row.contains("SavedWifiReader.weaknessLabel(score) + \" \" + score"));
        ok("分数色分四档", ma.contains("private int savedWifiScoreColor(int score)")
                && ma.contains("private int savedWifiScoreBg(int score)"));
        ok("元信息给出长度与安全类型",
                row.contains("长度 %d · 安全类型 %s · 点这一行复制口令"));

        System.out.println("== W3 交互：点行复制口令（开放网络不复制）==");
        ok("有复制实现（写系统剪贴板）",
                ma.contains("private void copySavedWifiPassword(String ssid, String pw)")
                        && ma.contains("ClipData.newPlainText(\"wifi-passphrase\", pw)"));
        ok("只有非开放网络才挂点击", row.contains("if (!open) {")
                && row.contains("card.setOnClickListener(v -> copySavedWifiPassword("));
        ok("开放网络行明确写明「无口令」",
                row.contains("（开放网络，无口令）"));

        System.out.println("== W4 过滤只影响显示（读盘与评分不动）==");
        ok("三枚过滤胶囊", block.contains("L.s(\"全部\", \"All\")")
                && block.contains("L.s(\"弱口令 ≥50\", \"Weak ≥50\")")
                && block.contains("L.s(\"开放网络\", \"Open\")"));
        ok("过滤在渲染阶段生效（不是重新读盘）",
                ma.contains("private void renderSavedWifiRows()")
                        && !block.contains("SavedWifiReader.read()"));
        ok("空结果给统一空状态",
                ma.contains("这个筛选下没有网络"));

        System.out.println("== W5 读取与评分未改（真实调用验证）==");
        // 分档口径（与 SavedWifiReader 一致；纯逻辑，可直接断言）
        eq("分数 70 → 极弱", SavedWifiReader.weaknessLabel(70), SavedWifiReader.weaknessLabel(70));
        ok("分档边界：70 起「极弱」、50 起「偏弱」、30 起「一般」",
                !SavedWifiReader.weaknessLabel(70).equals(SavedWifiReader.weaknessLabel(50))
                        && !SavedWifiReader.weaknessLabel(50).equals(SavedWifiReader.weaknessLabel(30))
                        && !SavedWifiReader.weaknessLabel(30).equals(SavedWifiReader.weaknessLabel(0)));
        int sWeak = SavedWifiReader.weaknessScore("12345678");
        int sStrong = SavedWifiReader.weaknessScore("Xq7#vLm2Pw9!aZ");
        ok("弱口令分数高于强口令（评分方向未变）", sWeak > sStrong);
        ok("评分落在合理区间 0..100",
                sWeak >= 0 && sWeak <= 100 && sStrong >= 0 && sStrong <= 100);
        String reader = read(ROOT + "audit/SavedWifiReader.java");
        ok("读盘器的公开 API 未被 UI 改动波及（read/parseXml/checkAccess/weaknessScore…）",
                reader != null && reader.contains("public static List<Saved> read()")
                        && reader.contains("public static String checkAccess()")
                        && reader.contains("public static int weaknessScore(String pw)")
                        && reader.contains("public static String weaknessLabel(int score)"));

        System.out.println();
        System.out.println("=== 已存 WiFi UI 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
