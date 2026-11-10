package io.github.wlmosv_png.wpaforge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置页专项审计的回归（2026-11-10）。
 *
 * <h3>它钉住的是什么</h3>
 * 这一轮对「设置」页做了四件事，每一件都容易在后续改动里悄悄失效，
 * 所以每条都留一个可判红的断言：
 * <ol>
 *   <li><b>派生预算真的有入口了</b>：{@code Prefs.setDeriveBudget} 此前
 *       <b>全工程没有调用者</b>（有键、有默认值、有取用点、javadoc 还写着
 *       「界面可调」），属于「代码支持但 UI 没有入口」。现在设置页必须有那一行，
 *       而且写盘必须真的落到 {@code Prefs.setDeriveBudget}。</li>
 *   <li><b>「恢复默认设置」只重置偏好</b>：最容易犯的错是顺手把用户数据
 *       （目标线索 / 当前目标）和安全项（PIN）也清了。断言两头：
 *       该在 {@code Prefs.RESET_KEYS} 里的都在，不该在的一个都不在。</li>
 *   <li><b>「破解设置」不再是空分组</b>：它此前只有一段静态说明、零个可操作项。</li>
 *   <li><b>同一事实不写两遍</b>：速度说明原本在「破解设置」与「使用边界」各写一份
 *       （内容重叠），且执行通道卡上同时出现「实测约 1 秒/条」与「0.1 条/秒」
 *       两个互相打架的数字。现在口径统一为「秒/条」、速度说明只有一处。</li>
 * </ol>
 *
 * <p>纯结构与纯函数断言，桌面 JVM 可跑；不碰 SharedPreferences
 * （android.jar 的桩会在 JVM 里抛异常），需要上下文的路径一律用源码扫描覆盖。
 */
public class SettingsPageAuditTest {

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

    private static final String ROOT = TestPaths.root() + "/";
    private static final String SRC =
            ROOT + "app/src/main/java/io/github/wlmosv_png/wpaforge/";

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉块注释与行注释 —— 注释里提到一个名字不等于代码里还在用它。 */
    private static String stripComments(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** 只取 {@code buildSettingsTab()} 的正文（到「组内分隔线」注释为止）。 */
    private static String settingsRegion(String ma) {
        int a = ma.indexOf("private void buildSettingsTab() {");
        int b = ma.indexOf("/** 组内分隔线（上方留白由调用方给）。 */");
        if (a < 0 || b <= a) return "";
        return ma.substring(a, b);
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** 从一个方法签名切到它的下一个 {@code \n    /**}，取方法正文（够用且不依赖括号配对）。 */
    private static String methodOf(String s, String sig) {
        int a = s.indexOf(sig);
        if (a < 0) return "";
        int b = s.indexOf("\n    /**", a + sig.length());
        if (b < 0) b = Math.min(s.length(), a + 6000);
        return s.substring(a, b);
    }

    public static void main(String[] args) {
        System.out.println("== 设置页专项审计（被测根 " + TestPaths.identity() + "）==");

        String maRaw = read(SRC + "MainActivity.java");
        String prefsRaw = read(SRC + "Prefs.java");
        String lRaw = read(SRC + "L.java");
        if (maRaw == null || prefsRaw == null || lRaw == null) {
            System.out.println("FAIL 读不到源码：" + (maRaw == null ? "MainActivity " : "")
                    + (prefsRaw == null ? "Prefs " : "") + (lRaw == null ? "L" : ""));
            fail++;
            System.out.println(pass + " 通过 / " + fail + " 失败");
            System.exit(1);
        }
        String maCode = stripComments(maRaw);
        String prefsCode = stripComments(prefsRaw);
        String regionRaw = settingsRegion(maRaw);
        String region = stripComments(regionRaw);

        // ---------------------------------------------------------------- A 纯函数
        System.out.println("-- A 派生预算的取值域（纯函数）");
        eq("clampBudget(0) -> 下限", Prefs.BUDGET_MIN, Prefs.clampBudget(0));
        eq("clampBudget(-5) -> 下限", Prefs.BUDGET_MIN, Prefs.clampBudget(-5));
        eq("clampBudget(29) -> 下限", Prefs.BUDGET_MIN, Prefs.clampBudget(29));
        eq("clampBudget(30) -> 原值", 30, Prefs.clampBudget(30));
        eq("clampBudget(120) -> 原值", 120, Prefs.clampBudget(120));
        eq("clampBudget(2000) -> 上限", Prefs.BUDGET_MAX, Prefs.clampBudget(2000));
        eq("clampBudget(99999) -> 上限", Prefs.BUDGET_MAX, Prefs.clampBudget(99999));
        eq("下限比上限小", true, Prefs.BUDGET_MIN < Prefs.BUDGET_MAX);
        eq("默认值在合法区间内", true,
                Prefs.DEFAULT_BUDGET >= Prefs.BUDGET_MIN
                        && Prefs.DEFAULT_BUDGET <= Prefs.BUDGET_MAX);

        boolean choicesOk = Prefs.BUDGET_CHOICES != null && Prefs.BUDGET_CHOICES.length >= 2;
        boolean monotonic = choicesOk;
        boolean hasDefault = false;
        if (choicesOk) {
            for (int i = 0; i < Prefs.BUDGET_CHOICES.length; i++) {
                int v = Prefs.BUDGET_CHOICES[i];
                if (v < Prefs.BUDGET_MIN || v > Prefs.BUDGET_MAX) choicesOk = false;
                if (i > 0 && v <= Prefs.BUDGET_CHOICES[i - 1]) monotonic = false;
                if (v == Prefs.DEFAULT_BUDGET) hasDefault = true;
            }
        }
        ok("档位全部落在合法区间内", choicesOk);
        ok("档位严格递增（下拉顺序即代价顺序）", monotonic);
        ok("默认值本身是可选档位之一（用户能看见自己现在在哪一档）", hasDefault);
        eq("mode(null) 不崩且恒为 Root",
                Prefs.MODE_ROOT, Prefs.mode(null));

        // ---------------------------------------------------------------- B RESET_KEYS 契约
        System.out.println("-- B「恢复默认设置」重置范围（契约）");
        List<String> keys = new ArrayList<>();
        for (String k : Prefs.RESET_KEYS) keys.add(k);
        String[] mustReset = {
                "pin_bssid", "mac_rotate", "derive_budget",
                "skip_policy_id", "plan_auto_advance", "dict_guide_seen",
        };
        for (String k : mustReset) {
            ok("会重置 " + k, keys.contains(k));
        }
        String[] mustKeep = {
                "app_pin_sha256", "target_seeds", "target_key", "target_label",
                "run_ordinal_max", "last_beat_ms", "avg_rate_ms", "rate_samples",
                "picked_dict_dir", "language",
        };
        for (String k : mustKeep) {
            ok("**不**重置 " + k, !keys.contains(k));
        }
        ok("清单无重复项", keys.size() == new java.util.HashSet<>(keys).size());
        ok("RESET_KEYS 是公开契约（界面文案与它同源）",
                prefsCode.contains("public static final String[] RESET_KEYS"));

        eq("语言键常量 = \"language\"", "language", L.KEY_LANGUAGE);
        ok("L 提供退回「跟随系统」的入口",
                stripComments(lRaw).contains("public static void resetToAuto(Context ctx)"));

        // ---------------------------------------------------------------- C 设置页结构
        System.out.println("-- C 设置页结构（源码扫描）");
        ok("取到了 buildSettingsTab() 正文", region.length() > 2000);
        ok("「破解设置」不再是空分组：有派生预算行",
                region.contains("budgetBtn") && region.contains("showBudgetPicker"));
        ok("派生预算行的文字由 refreshBudgetRow 统一刷新",
                region.contains("refreshBudgetRow();"));
        ok("「破解设置」组用与其它组相同的卡片容器（不再是一块裸 rowBg）",
                region.contains("LinearLayout crackCard = AmberTheme.card(this, dp, AmberTheme.RADIUS_CARD)"));
        ok("「工具」组里有恢复默认设置入口",
                region.contains("resetDefaults")
                        && region.contains("toolsCard.addView(resetDefaults)")
                        && region.contains("confirmResetDefaults"));
        ok("连接选项写明了生效时机（下一个任务，不静默改正在跑的任务）",
                region.contains("下一个任务"));
        ok("恢复默认真的调 Prefs.resetToDefaults（不是只弹个框）",
                maCode.contains("Prefs.resetToDefaults(this)"));
        ok("恢复默认同时把语言退回跟随系统",
                maCode.contains("L.resetToAuto(this)"));
        ok("恢复默认同时清掉手动字典目录",
                maCode.contains("DictStore.clearDir(this)"));
        ok("恢复默认的文案与 Prefs.RESET_KEYS 对得上（防两边各写一份慢慢走散）",
                maCode.contains("会退回出厂值的（仅偏好）")
                        && maCode.contains("连接选项：锁定 AP / 更换随机 MAC → 关")
                        && maCode.contains("派生预算 → 120 条")
                        && maCode.contains("语言 → 跟随系统")
                        && maCode.contains("字典目录 → 自动选择"));
        ok("读取派生预算的地方仍在（预算不是个孤儿键）",
                maCode.contains("Prefs.deriveBudget(this)"));
        ok("写盘落到 Prefs.setDeriveBudget",
                maCode.contains("Prefs.setDeriveBudget(this, nv)"));

        // 速度口径只在 fillModeInfo 里改过，单独取它的正文，别拿整文件当范围。
        String modeInfo = methodOf(maCode, "private void fillModeInfo() {");
        ok("取到了 fillModeInfo() 正文", modeInfo.length() > 400);
        ok("执行通道速度改用「秒/条」口径",
                modeInfo.contains("Prefs.avgSecLabel(this)"));
        eq("执行通道不再出现「条/秒」的旧口径（同一张卡自相矛盾）",
                0, countOf(modeInfo, "avgRateLabel(this)"));
        eq("「关于速度」不再在设置页出现第二次（内容已并入破解设置）",
                0, countOf(regionRaw, "\"关于速度\""));

        // ---------------------------------------------------------------- E 保存反馈
        System.out.println("-- E 设置写盘的反馈（不再「提示成功但其实没写」）");
        ok("Prefs.setPin 回报写入结果（boolean + commit）",
                prefsCode.contains("public static boolean setPin(Context ctx, String plain)")
                        && prefsCode.contains("sp(ctx).edit().putString(K_PIN, h).commit()"));
        ok("Prefs.clearPin 同样回报结果",
                prefsCode.contains("public static boolean clearPin(Context ctx)")
                        && prefsCode.contains("sp(ctx).edit().remove(K_PIN).commit()"));
        ok("设置页按返回值决定 PIN 提示文案",
                maCode.contains("boolean ok = Prefs.setPin(this, v)")
                        && maCode.contains("boolean ok = Prefs.clearPin(this)")
                        && maCode.contains("PIN 未保存（系统拒绝写入）")
                        && maCode.contains("清除未生效（系统拒绝写入）"));
        ok("恢复默认也按返回值决定提示（commit）",
                prefsCode.contains("public static boolean resetToDefaults(Context ctx)")
                        && maCode.contains("已恢复默认设置")
                        && maCode.contains("恢复失败（写入被拒）"));

        // ---------------------------------------------------------------- D 死代码已清
        System.out.println("-- D 已失效的旧设置（死代码）确实不在代码里了");
        eq("Prefs 里没有 modeIsManual", 0, countOf(prefsCode, "modeIsManual"));
        eq("Prefs 里没有 crack_mode 键", 0, countOf(prefsCode, "crack_mode"));
        eq("Prefs 里没有 autoCache", 0, countOf(prefsCode, "autoCache"));
        int stray = 0;
        for (String f : listJava(SRC)) {
            String body = stripComments(read(SRC + f));
            if (body.contains("modeIsManual") || body.contains("\"crack_mode\"")) stray++;
        }
        eq("全工程没有其它地方引用 modeIsManual / crack_mode", 0, stray);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    /** 包目录下的全部 .java 文件名（不含子目录，够用且不依赖递归工具）。 */
    private static List<String> listJava(String dir) {
        List<String> out = new ArrayList<>();
        File[] fs = new File(dir).listFiles();
        if (fs == null) return out;
        for (File f : fs) if (f.isFile() && f.getName().endsWith(".java")) out.add(f.getName());
        return out;
    }
}
