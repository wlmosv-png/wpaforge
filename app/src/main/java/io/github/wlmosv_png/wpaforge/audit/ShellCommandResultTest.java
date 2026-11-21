package io.github.wlmosv_png.wpaforge.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道结果语义门禁（2026-11-21）。
 *
 * <p><b>重建声明</b>：源码已丢失，按保留的编译产物重新编写，<b>不是</b>原始源码。
 *
 * <h3>修复前的语义错位</h3>
 * <ul>
 *   <li>{@code ShellSession.exec()} 在 {@code split()} 之后只取 {@code f.output}，
 *       把 {@code f.code}（退出码）**直接丢弃**。</li>
 *   <li>{@code ShellExec.Result.ok()} 只返回 {@code !timedOut}，含义是「没超时」，
 *       却被到处当成「命令成功」用。</li>
 * </ul>
 * 合起来：exit 127（命令不存在）、exit 1（su 被拒）、exit 0（真无输出）
 * 在通道层**完全无法区分**。
 *
 * <p>纯离线：不起任何进程、不执行 su、不联网。
 */
public final class ShellCommandResultTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void ok(String name, boolean cond) { ok(name, cond, ""); }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static List<String> lines(String... a) {
        List<String> l = new ArrayList<>();
        for (String s : a) l.add(s);
        return l;
    }

    public static void main(String[] args) {
        System.out.println("== 通道结果语义 ==");
        try {
            // ------------------------------------------------ 1. split 退出码
            System.out.println();
            System.out.println("-- 1. ShellSession.split() 正确切出退出码 --");
            {
                List<String> ls = lines("hello", "world", ShellSession.MARK + "1__0");
                ShellSession.Frame f = ShellSession.split(ls, 1);
                ok("exit 0 被正确解析", f != null && f.code == 0,
                        f == null ? "null" : "code=" + f.code);
                ok("输出内容不含哨兵行",
                        f != null && f.output.contains("hello")
                                && f.output.contains("world")
                                && !f.output.contains(ShellSession.MARK),
                        f == null ? "null" : f.output);
            }
            {
                List<String> ls = lines("su: permission denied",
                        ShellSession.MARK + "2__1");
                ShellSession.Frame f = ShellSession.split(ls, 2);
                ok("exit 1（权限拒绝）被正确解析", f != null && f.code == 1,
                        f == null ? "null" : "code=" + f.code);
            }
            {
                List<String> ls = lines("sh: cmd: not found", ShellSession.MARK + "3__127");
                ShellSession.Frame f = ShellSession.split(ls, 3);
                ok("exit 127（命令不存在）被正确解析", f != null && f.code == 127,
                        f == null ? "null" : "code=" + f.code);
            }
            {
                List<String> ls = lines(ShellSession.MARK + "4__0");
                ShellSession.Frame f = ShellSession.split(ls, 4);
                ok("空输出 + exit 0 仍是成功帧",
                        f != null && f.code == 0 && f.output.trim().isEmpty(),
                        f == null ? "null" : "code=" + f.code);
            }
            ok("找不到哨兵 → null（必须当失败）",
                    ShellSession.split(lines("hello"), 9) == null);
            ok("seq 不匹配 → null",
                    ShellSession.split(lines(ShellSession.MARK + "1__0"), 2) == null);

            // ------------------------------------------------ 2. ShellExec.Result 语义
            System.out.println();
            System.out.println("-- 2. ShellExec.Result：ok != succeeded --");
            {
                ShellExec.Result r0 = newResult("out", false, 0);
                ok("exit 0 + 未超时 → ok() 为真", r0.ok());
                ok("exit 0 + 未超时 → succeeded() 为真", r0.succeeded());
                ok("exit 0 → hasExitCode() 为真", r0.hasExitCode());
            }
            {
                ShellExec.Result r1 = newResult("Permission denied", false, 1);
                ok("exit 1 + 未超时 → ok() 仍为真（它只表示没超时）", r1.ok());
                ok("★ exit 1 + 未超时 → succeeded() 必须为假", !r1.succeeded());
            }
            {
                ShellExec.Result r127 = newResult("not found", false, 127);
                ok("★ exit 127 → succeeded() 为假", !r127.succeeded());
                ok("exit 127 → ok() 为真（未超时）", r127.ok());
            }
            {
                ShellExec.Result rt = newResult("partial", true, -1);
                ok("超时 → ok() 为假", !rt.ok());
                ok("超时 → succeeded() 为假", !rt.succeeded());
                ok("超时 → hasExitCode() 为假（强杀后退出码无意义）", !rt.hasExitCode());
            }
            {
                ShellExec.Result rn = newResult("x", false, -1);
                ok("拿不到退出码 → succeeded() 保守为假", !rn.succeeded());
                ok("拿不到退出码 → hasExitCode() 为假", !rn.hasExitCode());
            }

            // ------------------------------------------------ 3. 三态贯穿
            System.out.println();
            System.out.println("-- 3. 「命令失败」与「确实没有」在解析层就分开 --");
            {
                long a = ListNetworksParser.findNetIdOrUnreadable(
                        "Network Id      SSID            Security type\n", "X");
                ok("可判定空列表 → -1（确认没有）", a == -1L, "got=" + a);
                long b = ListNetworksParser.findNetIdOrUnreadable("Permission denied\n", "X");
                ok("权限拒绝 → NETID_UNREADABLE",
                        b == ListNetworksParser.NETID_UNREADABLE, "got=" + b);
                long c = ListNetworksParser.findNetIdOrUnreadable(
                        "Network Id      SSID            Security type\n"
                                + "613   X   wpa2-psk\n", "X");
                ok("找到目标 → 613", c == 613L, "got=" + c);
                long d = ListNetworksParser.findNetIdOrUnreadable(
                        "Network Id      SSID            Security type\n"
                                + "613   X   wpa2-psk\n", "X", 1);
                ok("退出码非 0 → 即使文本正常也判读不到",
                        d == ListNetworksParser.NETID_UNREADABLE, "got=" + d);
                ok("三态值互不相同（-1 vs -2）", -1L != ListNetworksParser.NETID_UNREADABLE);
            }

            // ------------------------------------------------ 4. 六种情形
            System.out.println();
            System.out.println("-- 4. 六种情形至少两两可分 --");
            String[] desc = {
                    "命令成功且找到目标", "命令成功且确认没有", "命令失败（exit 1）",
                    "命令不存在（exit 127）", "命令超时", "输出无法解析",
            };
            long[] vals = {
                    ListNetworksParser.findNetIdOrUnreadable("613   X   wpa2-psk\n", "X", 0),
                    ListNetworksParser.findNetIdOrUnreadable("", "X", 0),
                    ListNetworksParser.findNetIdOrUnreadable("Permission denied", "X", 1),
                    ListNetworksParser.findNetIdOrUnreadable("not found", "X", 127),
                    ListNetworksParser.findNetIdOrUnreadable(null, "X"),
                    ListNetworksParser.findNetIdOrUnreadable("garbage garbage", "X", 0),
            };
            for (int i = 0; i < desc.length; i++) info(desc[i] + " -> " + vals[i]);
            ok("「找到目标」与其他五种可区分",
                    vals[0] >= 0 && vals[1] == -1L && vals[2] < 0 && vals[3] < 0
                            && vals[4] < 0 && vals[5] < 0);
            ok("「确认没有」(-1) 与「读不到」(-2) 严格分开",
                    vals[1] == -1L && vals[4] == ListNetworksParser.NETID_UNREADABLE);
            ok("四种失败情形全部归入 NETID_UNREADABLE",
                    vals[2] == ListNetworksParser.NETID_UNREADABLE
                            && vals[3] == ListNetworksParser.NETID_UNREADABLE
                            && vals[4] == ListNetworksParser.NETID_UNREADABLE
                            && vals[5] == ListNetworksParser.NETID_UNREADABLE);

        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 未预期异常: " + t);
            t.printStackTrace(System.out);
        }

        System.out.println();
        System.out.println("=== 通道结果语义测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    /** 同包可直调包级构造器。 */
    private static ShellExec.Result newResult(String out, boolean timedOut, int code) {
        return new ShellExec.Result(out, timedOut, code);
    }
}
