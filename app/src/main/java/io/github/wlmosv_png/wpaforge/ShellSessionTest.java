package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ShellSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 常驻 shell 会话的**分帧协议**测试。
 *
 * <p>整套提速（每条 5s → ~1.5s）就靠这一层：同一个 {@code su -c sh} 里连打命令。
 * 分帧错一次，就可能把上一条的输出当成这一条 —— 那不再是慢，而是**判错**。
 * 所以边界必须钉死：序列号必须对上、哨兵没读到就必须判失败（返回 null），
 * 让调用方走「通道异常」分支（只会漏判）。
 */
public class ShellSessionTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    private static List<String> lines(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    public static void main(String[] args) {
        System.out.println("== 常驻 shell 分帧 ==");

        // 1) 包装：命令 + 哨兵两行
        String w = ShellSession.wrap("cmd wifi status", 7);
        check("包装含原命令", w.startsWith("cmd wifi status\n"));
        check("包装含哨兵与退出码占位", w.contains("__WPF_END__7__$?"));
        check("包装以换行收尾", w.endsWith("\n"));

        // 2) 正常切分
        ShellSession.Frame f = ShellSession.split(
                lines("Wifi is connected to \"B212\"", "WifiInfo: ...", "__WPF_END__7__0"), 7);
        check("切出输出", f != null && f.output.contains("Wifi is connected to \"B212\""));
        check("退出码 0", f != null && f.code == 0);
        check("哨兵行本身不进输出", f != null && !f.output.contains("__WPF_END__"));

        // 3) 非零退出码照样是完整帧
        ShellSession.Frame f2 = ShellSession.split(
                lines("error: x", "__WPF_END__7__1"), 7);
        check("退出码 1 也算完整帧", f2 != null && f2.code == 1);

        // 4) 空输出
        ShellSession.Frame f3 = ShellSession.split(lines("__WPF_END__9__0"), 9);
        check("空输出可用", f3 != null && f3.output.isEmpty());

        // 5) ★ 最要紧：哨兵没读到 → 必须判失败，绝不能拿残帧当结果
        check("没哨兵 → null", ShellSession.split(
                lines("Wifi is connected to \"B212\"", "半截输出"), 7) == null);
        check("空行表 → null", ShellSession.split(new ArrayList<>(), 7) == null);
        check("null → null", ShellSession.split(null, 7) == null);

        // 6) ★ 序列号必须对上：上一条的哨兵不能被当成本条
        check("旧序列号的哨兵不算数", ShellSession.split(
                lines("上一条的输出", "__WPF_END__6__0"), 7) == null);
        check("序列号前缀差不被误吃（7 vs 70）", ShellSession.split(
                lines("x", "__WPF_END__70__0"), 7) == null);

        // 7) 输出里出现「像哨兵的行」也不影响（只认自己的 seq）
        ShellSession.Frame f4 = ShellSession.split(lines(
                "log: __WPF_END__7__ is a string in a log", "__WPF_END__7__0"), 7);
        check("输出里的相似行不误判", f4 != null && f4.output.contains("is a string in a log"));

        // 8) 退出码读不出来时仍然是完整帧（code=-1），不当作丢帧
        ShellSession.Frame f5 = ShellSession.split(lines("x", "__WPF_END__7__?"), 7);
        check("退出码异常仍有帧", f5 != null && f5.code == -1);

        // 9) 多行输出按序拼回（含空行）
        ShellSession.Frame f6 = ShellSession.split(lines("a", "", "b", "__WPF_END__3__0"), 3);
        check("多行输出保序且保留空行", f6 != null && f6.output.equals("a\n\nb\n"));

        System.out.println();
        System.out.println("常驻 shell 分帧: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
