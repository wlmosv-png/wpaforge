package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;
import io.github.wlmosv_png.wpaforge.audit.SuccessRule;
import io.github.wlmosv_png.wpaforge.audit.WpaLogReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 判定流程测试（**已重写**：从自证式改为真正触达生产实现）。
 *
 * <h3>为什么重写（2026-10-24，R10）</h3>
 * 旧版本**零引用任何生产类**：它自己声明了一套 {@code V} 枚举与 {@code verdict()}
 * 模型，再拿"预期"去断言自己的模型 —— 无论生产代码怎么改，它都不会红。
 * 同类的 {@code AttributionTest} 还引用了一个本地常量 {@code MIN_HIT_MS = 3000}
 * 并注释「与 CrackEngine.MIN_HIT_MS 保持一致」，而生产里那个常量早已是 0 且
 * 不再参与判定。这类测试给出的是**虚假的安全感**。
 *
 * <h3>现在测什么</h3>
 * 全部走生产实现：{@link SuccessRule#assess}（成功判定的唯一入口）、
 * {@link WpaLogReader}（日志证据）、{@link CrackEngine#classifyDeadline}（到期归类）。
 *
 * <h3>被重写掉的那条旧规则</h3>
 * 旧版主张「命令之后的可用事件即命中，**无时间门槛**」，并列举了"1.5 秒连上不算命中"
 * 之类的自证断言。现在保留其**真实意图**：成功判定不设最短耗时门槛 ——
 * 这一点由「生产里没有时间门槛」来证明（见 {@code MIN_HIT_MS} 已废弃且不参与判定）。
 */
public class VerdictFlowTest {

    private static int pass = 0, fail = 0;
    private static final String T = "LAB-TARGET";

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 生产入口：成功判定（SuccessRule.assess）==");
        SuccessRule.Assessment hit = SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 613, 613);
        check("三条件同行 + netId 一致 → SUCCESS",
                hit.verdict == CrackEngine.Verdict.SUCCESS);
        check("证据标签来自生产实现", "supplicant+netId".equals(hit.evidence));
        check("三条件缺一 → 不是成功帧（无猜测）",
                SuccessRule.assess(T, T, "ASSOCIATING", "192.168.1.3", 613, 613).verdict == null);
        check("SSID 不同 → 不是成功帧",
                SuccessRule.assess(T, "OTHER-AP", "COMPLETED", "192.168.1.3", 613, 613).verdict == null);
        check("netId 冲突 → UNKNOWN（既不成功也不失败）",
                SuccessRule.assess(T, T, "COMPLETED", "192.168.1.3", 613, 701).verdict
                        == CrackEngine.Verdict.UNKNOWN);

        System.out.println("== 无最短耗时门槛（取代旧版的'1.5 秒门槛'举证）==");
        // 判定不接收时间参数 → 结构上不可能存在耗时门槛；再用源码守卫确认
        String eng = read("/workspace/promo-wpaforge/app/src/main/java/"
                + "io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
        if (eng == null) {
            System.out.println("  N/RUN 门槛守卫（源码不可读）");
        } else {
            String code = stripComments(eng);
            check("MIN_HIT_MS 已废弃（值为 0，不构成门槛）",
                    code.contains("MIN_HIT_MS = 0L"));
            check("判定路径里没有任何 '>= MIN_HIT_MS' 之类的耗时比较",
                    !code.contains(">= MIN_HIT_MS") && !code.contains("< MIN_HIT_MS"));
            // 结构证据：assess 的**参数表**里没有任何时间/超时参数（在它的宿主类里查）
            String sr = read("/workspace/promo-wpaforge/app/src/main/java/"
                    + "io/github/wlmosv_png/wpaforge/audit/SuccessRule.java");
            String sig = "";
            if (sr != null) {
                int k = sr.indexOf("Assessment assess(");
                if (k > 0) {
                    int j = sr.indexOf('(', k);
                    int depth = 0;
                    for (int i = j; i < sr.length(); i++) {
                        char c = sr.charAt(i);
                        sig += c;
                        if (c == '(') depth++;
                        else if (c == ')') { depth--; if (depth == 0) break; }
                    }
                }
            }
            check("assess 的参数表里没有时间/超时参数（结构上无门槛）",
                    !sig.isEmpty() && !sig.contains("ms") && !sig.contains("timeout")
                            && !sig.contains("Time"));
        }

        System.out.println("== 日志证据路径（生产 WpaLogReader + 窗口切片）==");
        String bssid = "02:00:00:00:00:0a";
        String neg = "wlan0: WPA: Key negotiation completed with " + bssid + "\n";
        check("协商完成可被识别（目标 BSSID）", WpaLogReader.sawNegotiatedFor(neg, bssid));
        check("别的 BSSID 的协商完成不算", !WpaLogReader.sawNegotiatedFor(neg, "02:00:00:00:00:0b"));
        check("BSSID 为空时一律不算（宁可不判成功）", !WpaLogReader.sawNegotiatedFor(neg, ""));
        String assoc = "wlan0: Trying to associate with SSID '" + T + "'\n";
        check("关联行可被识别", WpaLogReader.sawAssociateFor(assoc, T));
        check("空输入不崩", !WpaLogReader.sawAssociateFor(null, T)
                && !WpaLogReader.sawNegotiatedFor(null, bssid));

        System.out.println("== 到期归类（生产 classifyDeadline）==");
        check("无事件 + 结束时连着目标 → UNKNOWN（无法归因，不算成功）",
                CrackEngine.classifyDeadline(false, true, false, false, false).verdict
                        == CrackEngine.Verdict.UNKNOWN);
        check("无事件 + 未连目标 + 通道正常 → TIMEOUT（**不是** AUTH_FAIL）",
                CrackEngine.classifyDeadline(false, false, false, false, false).verdict
                        == CrackEngine.Verdict.TIMEOUT);
        check("命令未生效 → ERROR（未尝试，不是密码错）",
                CrackEngine.classifyDeadline(true, false, false, false, false).verdict
                        == CrackEngine.Verdict.ERROR);

        System.out.println();
        System.out.println("=== 判定流程测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static String stripComments(String src) {
        String s1 = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder();
        for (String l : s1.split("\n")) {
            int k = l.indexOf("//");
            sb.append(k >= 0 ? l.substring(0, k) : l).append('\n');
        }
        return sb.toString();
    }

    private static String read(String p) {
        try {
            return new String(Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
