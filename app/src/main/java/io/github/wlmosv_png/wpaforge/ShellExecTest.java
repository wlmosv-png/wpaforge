package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.ShellExec;

/**
 * 进程执行的超时保护测试 —— 其中「孙进程不退出」这一条是**死锁回归用例**。
 *
 * 背景：旧实现超时后只 destroyForcibly 直接子进程，而 `su -c "cmd wifi ..."`
 * 会派生孙进程。孙进程持有管道写端 → 上层 readLine() 永远等不到 EOF →
 * 整条引擎线程永久阻塞（实测现象：切到后台任务完全不动，回前台才继续）。
 *
 * 这里的用例真的去起一个「父进程等着、孙进程长睡」的进程树，如果没有正确
 * 终止整棵树并关掉读端，它会挂到测试超时。
 */
public class ShellExecTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 正常执行 ==");
        ShellExec.Result r1 = ShellExec.run(new String[]{"sh", "-c", "echo hello"}, 5000);
        ok("输出正确", r1.output.contains("hello"));
        ok("未标记超时", !r1.timedOut);

        System.out.println("== 普通超时 ==");
        long t0 = System.currentTimeMillis();
        ShellExec.Result r2 = ShellExec.run(new String[]{"sh", "-c", "sleep 30"}, 1000);
        long ms2 = System.currentTimeMillis() - t0;
        ok("约 1 秒返回，而不是等 30 秒", ms2 < 5000);
        ok("标记为超时", r2.timedOut);

        System.out.println("== 死锁回归：孙进程不退出 ==");
        long t1 = System.currentTimeMillis();
        ShellExec.Result r3 = ShellExec.run(
                new String[]{"sh", "-c", "sh -c 'sleep 30' & wait"}, 1200);
        long ms3 = System.currentTimeMillis() - t1;
        ok("孙进程场景也能按时返回（旧实现会永久卡死）", ms3 < 6000);
        ok("标记为超时", r3.timedOut);

        System.out.println("== 边界与异常 ==");
        ShellExec.Result r4 = ShellExec.run(new String[]{"/nonexistent-xyz-abc"}, 2000);
        ok("命令不存在不抛异常", r4 != null);
        ShellExec.Result r5 = ShellExec.run(new String[]{"sh", "-c", "true"}, 3000);
        ok("空输出且未超时", r5.output.trim().isEmpty() && !r5.timedOut);
        ShellExec.Result r6 = ShellExec.run(
                new String[]{"sh", "-c", "echo a; echo b; echo c"}, 3000);
        ok("多行输出完整", r6.output.contains("a") && r6.output.contains("c"));
        ShellExec.Result r7 = ShellExec.run(
                new String[]{"sh", "-c", "echo err 1>&2"}, 3000);
        ok("stderr 被合并进来", r7.output.contains("err"));

        System.out.println("== 超时后的输出不丢（部分输出也算）==");
        ShellExec.Result r8 = ShellExec.run(
                new String[]{"sh", "-c", "echo first; sleep 30"}, 1200);
        ok("超时前已输出的内容保留", r8.output.contains("first"));
        ok("同时标记超时", r8.timedOut);

        System.out.println();
        System.out.println("=== 进程执行测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
