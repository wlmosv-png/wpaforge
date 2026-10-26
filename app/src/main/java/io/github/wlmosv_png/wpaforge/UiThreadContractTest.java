package io.github.wlmosv_png.wpaforge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 界面线程契约检查（源码级门禁）。
 *
 * ── 为什么需要它 ─────────────────────────────────────────────
 * 同一个坑连踩两次，都是线上才炸：
 *   ① `showResultCard` 里强转 null 的 LayoutParams → NPE
 *   ② `onTaskDone` 里把界面代码写在了 `ui.post(...)` **外面**
 *      → `CalledFromWrongThreadException: Expected main, Calling wpaforge-crack`
 * 第二次的直接后果是：回调在工作线程抛异常 → `renderProgressCardState()` 没执行
 * → 界面永远停在「收尾中」，用户以为卡死。
 *
 * 服务回调（{@code UiCallback}）是在**工作线程**上执行的，里面碰任何 View 都是错的。
 * 这条规则靠人眼守不住（我已经漏了一次），所以把它变成测试。
 */
public class UiThreadContractTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    /** 会被判定为"碰了 View"的调用特征 */
    private static final String[] UI_MARKERS = {
            ".setText(", ".setVisibility(", ".setEnabled(", ".setAlpha(",
            ".removeAllViews(", ".addView(", ".setBackground(", ".setImageDrawable(",
            "showHitCard(", "showResultCard(", "hideResultCard(",
            "appendStream(", "appendStreamColored(", "appendStreamDetail(",
            "renderQueue(", "renderCandidates(", "renderProgressCardState(",
            "updateDictStatus(", "Toast.makeText(",
    };

    public static void main(String[] a) throws Exception {
        File src = findSource();
        ok("找到 MainActivity.java", src != null,
                "从当前目录向上找不到 app/src/main/java/.../MainActivity.java");
        if (src == null) {
            summary();
            return;
        }
        String text = read(src);

        int cbStart = text.indexOf("CrackService.UiCallback svcCallback");
        ok("找到 svcCallback 定义", cbStart > 0, "");
        if (cbStart < 0) {
            summary();
            return;
        }
        // 回调匿名类的范围：到 "};" 结束（其后紧跟的下一个 "};" 就是它的收尾）
        int cbEnd = text.indexOf("\n    };", cbStart);
        ok("找到 svcCallback 范围", cbEnd > cbStart, "");
        String body = text.substring(cbStart, cbEnd > cbStart ? cbEnd : text.length());

        // 逐个回调方法检查
        String[] methods = {"onQueueChanged", "onAttempt(", "onAttemptResult",
                "onProgress", "onTaskDone", "onLog", "onRestoreDone"};
        for (String m : methods) {
            int s = body.indexOf("public void " + m);
            if (s < 0) {
                ok("回调 " + m + " 存在", false, "");
                continue;
            }
            int e = nextMethodStart(body, s + 10);
            String method = body.substring(s, e);
            ok("回调 " + m + " 不直接碰 View（全部在 ui.post 内）",
                    uiCallsArePosted(method), firstOffender(method));
        }

        // 反向保护：showResultCard 必须自带主线程守卫
        ok("showResultCard 自带主线程守卫",
                text.contains("Looper.myLooper() != android.os.Looper.getMainLooper()"),
                "缺守卫：一旦被工作线程调到就会再次 CalledFromWrongThread");

        // 反向保护：actioButton 工厂不许强转 getLayoutParams
        ok("不再强转 getLayoutParams（那正是 NPE 的来源）",
                !text.contains("(LinearLayout.LayoutParams) bigger.getLayoutParams()")
                        && !text.contains("(LinearLayout.LayoutParams) giveUp.getLayoutParams()"),
                "");

        summary();
    }

    /** 方法体里所有"碰 View"的调用，是否都在 ui.post( 之后 */
    private static boolean uiCallsArePosted(String methodBody) {
        return firstOffender(methodBody).isEmpty();
    }

    /** 返回第一个越界的调用（没有越界就返回空串） */
    private static String firstOffender(String methodBody) {
        int posted = methodBody.indexOf("ui.post(");
        for (String marker : UI_MARKERS) {
            int idx = 0;
            while (true) {
                int at = methodBody.indexOf(marker, idx);
                if (at < 0) break;
                if (posted < 0 || at < posted) {
                    int lineEnd = methodBody.indexOf('\n', at);
                    return marker + " 出现在 ui.post 之前 → "
                            + methodBody.substring(at, lineEnd < 0 ? methodBody.length() : lineEnd).trim();
                }
                idx = at + marker.length();
            }
        }
        return "";
    }

    private static int nextMethodStart(String body, int from) {
        int next = body.indexOf("\n        public void ", from);
        int end = body.indexOf("\n        };", from);
        int best = body.length();
        if (next > 0) best = Math.min(best, next);
        if (end > 0) best = Math.min(best, end);
        return best;
    }

    // ---------------------------------------------------------------- 文件定位

    private static File findSource() {
        List<String> rel = Arrays.asList(
                "app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java",
                "promo-wpaforge/app/src/main/java/io/github/wlmosv_png/wpaforge/MainActivity.java");
        File cur = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 4 && cur != null; depth++) {
            for (String r : rel) {
                File f = new File(cur, r);
                if (f.isFile()) return f;
            }
            cur = cur.getParentFile();
        }
        return null;
    }

    private static String read(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void summary() {
        System.out.println();
        System.out.println("=== 界面线程契约测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    // 占位，避免 IDE 报未使用
    static List<String> unused() { return new ArrayList<>(); }
}
