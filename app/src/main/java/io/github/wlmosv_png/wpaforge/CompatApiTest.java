package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.TestPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 低版本 Android 适配门禁（2026-11-15）。
 *
 * <h3>为什么需要它（真实事故）</h3>
 * 用户反馈「安卓 9 不能用」。查下来两条线，都不是靠猜：
 *
 * <ol>
 *   <li><b>{@code String.repeat(int)} 是 API 33 才有的库方法</b>，而本工程
 *       {@code minSdk = 26}、d8 又不做 libcore 反糖 —— 在 Android 8~12 上调用即
 *       {@code NoSuchMethodError}，而且**编译期完全看不出来**。
 *       生产代码里真有一处（局域网探测的进度条）。</li>
 *   <li><b>{@code cmd wifi} 的子命令集随版本变</b>：AOSP pie-release 的
 *       {@code WifiShellCommand} 只有 4 条调试命令，**连 {@code status} 都没有**，
 *       更没有 {@code connect-network}。而旧自检的判据是「输出含 Wifi/Wi-Fi/wlan/connected」——
 *       help 文本首行恰是 {@code "Wi-Fi (wifi) commands:"}，于是**在 Android 9 上照样报
 *       「通道已接入」**，破解却一条都跑不出来。</li>
 * </ol>
 *
 * <p>第 1 条靠**这张表**守住；第 2 条靠**运行时探测**守住（本类断言探测代码还在）。
 */
public class CompatApiTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  " + detail);
        }
    }

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    /** minSdk 之上的库 API：出现在生产代码里 = 真机上必崩 */
    private static final String[][] FORBIDDEN = {
            // 字符串字面量接收者：Java 里字面量必然以 " 收尾再接 .method(，
            // 这样写既不会漏（本例就是这么抓到的），也不会误伤自定义的 r.lines()
            {"\"\\s*\\.\\s*repeat\\s*\\(", "String.repeat", "33"},
            {"\"\\s*\\.\\s*lines\\s*\\(", "String.lines", "34"},
            {"\"\\s*\\.\\s*strip\\s*\\(", "String.strip", "33"},
            {"\"\\s*\\.\\s*isBlank\\s*\\(", "String.isBlank", "33"},
            {"\"\\s*\\.\\s*formatted\\s*\\(", "String.formatted", "34"},
            // 无歧义的静态工厂
            {"\\bList\\.of\\s*\\(", "List.of", "34"},
            {"\\bSet\\.of\\s*\\(", "Set.of", "34"},
            {"\\bMap\\.of\\s*\\(", "Map.of", "34"},
            {"\\bList\\.copyOf\\s*\\(", "List.copyOf", "34"},
            {"\\bMap\\.copyOf\\s*\\(", "Map.copyOf", "34"},
            {"\\bMap\\.ofEntries\\s*\\(", "Map.ofEntries", "34"},
            // 流与 IO
            {"\\.toUnmodifiable(List|Set|Map)\\s*\\(", "Collectors.toUnmodifiable*", "34"},
            {"\\bInputStream\\b[^;\\n]*\\.readAllBytes\\s*\\(\\s*\\)", "InputStream.readAllBytes", "33"},
            {"\\.transferTo\\s*\\(", "InputStream.transferTo", "33"},
            {"\\bPath\\.of\\s*\\(", "Path.of", "30"},
            {"Files\\.readString\\s*\\(", "Files.readString", "33"},
            {"Files\\.writeString\\s*\\(", "Files.writeString", "33"},
            {"requireNonNullElse\\s*\\(", "Objects.requireNonNullElse", "34"},
    };

    /** 高于 minSdk 的**框架**调用：必须包在 SDK_INT 判断里，本类只查「是否存在」 */
    private static final String[][] FRAMEWORK = {
            {"getInsetsController\\s*\\(", "View.getInsetsController", "30"},
            {"setDecorFitsSystemWindows\\s*\\(", "Window.setDecorFitsSystemWindows", "30"},
            {"setSystemGestureExclusionRects\\s*\\(", "setSystemGestureExclusionRects", "29"},
            {"MediaStore\\.Downloads", "MediaStore.Downloads", "29"},
            {"VibratorManager", "VibratorManager", "31"},
            {"setRenderEffect\\s*\\(", "View.setRenderEffect", "31"},
            {"registerScreenCaptureCallback\\s*\\(", "registerScreenCaptureCallback", "34"},
    };

    public static void main(String[] a) {
        String manifest = read(TestPaths.under("app/src/main/AndroidManifest.xml"));
        ok("AndroidManifest.xml 可读", manifest != null);
        int minSdk = -1;
        if (manifest != null) {
            Matcher m = Pattern.compile("android:minSdkVersion=\"(\\d+)\"").matcher(manifest);
            if (m.find()) minSdk = Integer.parseInt(m.group(1));
        }
        ok("读到 minSdk", minSdk > 0, "" + minSdk);
        ok("minSdk 仍是 26（低于 33 就必须守下面这张表）", minSdk == 26, "" + minSdk);

        List<File> prod = new ArrayList<>();
        collect(new File(TestPaths.javaPkgDir()), prod);

        System.out.println("== 生产代码不得使用 minSdk 之上的库 API ==");
        List<String> bad = new ArrayList<>();
        for (File f : prod) {
            String name = f.getName();
            if (name.endsWith("Test.java") || name.equals("TestPaths.java")) continue;
            String raw = read(f.getAbsolutePath());
            if (raw == null) continue;
            String code = stripComments(raw);
            for (String[] row : FORBIDDEN) {
                Matcher m = Pattern.compile(row[0]).matcher(code);
                while (m.find()) {
                    int line = code.substring(0, m.start()).split("\n", -1).length;
                    bad.add(name + ":" + line + " 用 " + row[1] + "（API>=" + row[2] + "）");
                }
            }
        }
        ok("无 API>26 的库调用（repeat / List.of / readAllBytes …）", bad.isEmpty(), bad.toString());

        System.out.println("== 框架调用要么不用、要么带版本判断 ==");
        List<String> fw = new ArrayList<>();
        for (File f : prod) {
            String name = f.getName();
            if (name.endsWith("Test.java")) continue;
            String raw = read(f.getAbsolutePath());
            if (raw == null) continue;
            String code = stripComments(raw);
            for (String[] row : FRAMEWORK) {
                Matcher m = Pattern.compile(row[0]).matcher(code);
                while (m.find()) {
                    int line = code.substring(0, m.start()).split("\n", -1).length;
                    if (!nearSdkCheck(code, line)) {
                        fw.add(name + ":" + line + " " + row[1] + "（API>=" + row[2] + "）无版本判断");
                    }
                }
            }
        }
        ok("高版本框架调用都在 SDK_INT 判断内", fw.isEmpty(), fw.toString());

        System.out.println("== 「cmd wifi 子命令」必须运行时探测，不许按版本号猜 ==");
        String engine = read(TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java"));
        ok("CrackEngine 可读", engine != null);
        if (engine != null) {
            String code = stripComments(engine);
            // 2026-11-20 改：判据从「grep 用法表」换成「实际调用子命令看返回」。
            // 用法表会被 ROM 裁剪/折行/分帧截断 —— 用户在安卓 13 上就撞到
            // 「status 有、forget 有、中间 connect-network 没有」这种不可能的组合。
            ok("探测 connect-network 是否在（实际调用）",
                    code.contains("probeSubcommand(\"connect-network\")")
                            && code.contains("connect-network"));
            ok("探测 forget-network 是否在（实际调用）",
                    code.contains("probeSubcommand(\"forget-network\")"));
            ok("探测 status 是否在（实际调用）",
                    code.contains("probeSubcommand(\"status\")"));
            ok("判据走 ShellSubcommand.detect（不靠 contains 用法表）",
                    code.contains("ShellSubcommand.detect"));
            ok("把「用法表」与「状态输出」分开判",
                    code.contains("commands:") && code.contains("looksLikeWifi"));
            ok("缺 connect-network 时通道判为不可用",
                    code.contains("unsupported (no connect-network)"));
            ok("「未知」不得当成「没有」（只有 MISSING 才判死）",
                    code.contains("connectMissing = (stConnect == ShellSubcommand.MISSING)"));
        }

        System.out.println("== 局域网探测的进度条不再用 String.repeat ==");
        String lan = read(TestPaths.under(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/scan/LanProbeExecutor.java"));
        ok("LanProbeExecutor 可读", lan != null);
        if (lan != null) {
            String code = stripComments(lan);
            ok("没有 repeat 调用", !code.contains(".repeat("));
            ok("改用自建 bar()", code.contains("static String bar(int n)"));
        }

        System.out.println();
        System.out.println("=== 低版本适配: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- helpers

    /** 该行前后 8 行里是否出现 SDK_INT 判断 */
    private static boolean nearSdkCheck(String code, int line) {
        String[] ls = code.split("\n", -1);
        int from = Math.max(0, line - 9), to = Math.min(ls.length, line + 8);
        for (int i = from; i < to; i++) {
            if (ls[i].contains("SDK_INT")) return true;
        }
        return false;
    }

    private static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(src.length(), i + 2);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
