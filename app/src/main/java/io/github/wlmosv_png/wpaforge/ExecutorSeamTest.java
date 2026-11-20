package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.cap.RootChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RootChannel.Executor} 接缝的行为门禁。
 *
 * <p>钉住三件事：
 * <ul>
 *   <li>注入执行器后**不再起 su 常驻会话**（否则会白起一个进程、
 *       并且让诊断看到「在用 su」而实际不是）；</li>
 *   <li>命令原样转给注入执行器，不做二次包装；</li>
 *   <li>{@code null}（没拿到）与空串（拿到但无输出）语义不被混淆 ——
 *       这两者在判定链里含义完全不同。</li>
 * </ul>
 */
public class ExecutorSeamTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        ExecutorSeamTest t = new ExecutorSeamTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    /** 记录调用的假执行器。 */
    private static final class FakeExecutor implements RootChannel.Executor {
        final List<String> calls = new ArrayList<>();
        final String reply;
        final String name;

        FakeExecutor(String name, String reply) {
            this.name = name;
            this.reply = reply;
        }

        @Override
        public String exec(String cmd, long timeoutMs) {
            calls.add(cmd);
            return reply;
        }

        @Override
        public String name() {
            return name;
        }
    }

    private void run() {
        nameFollowsExecutor();
        commandPassedThrough();
        nullVsEmptyDistinct();
        noSuSessionWhenInjected();
        defaultExecutorIsSu();
    }

    private void nameFollowsExecutor() {
        RootChannel c = new RootChannel(new FakeExecutor("Shizuku", "ok"));
        ok("name() 反映注入的执行器", "Shizuku".equals(c.name()));
        RootChannel d = new RootChannel();
        ok("默认 name() 是 Root", "Root".equals(d.name()));
    }

    private void commandPassedThrough() {
        FakeExecutor f = new FakeExecutor("Shizuku", "Wifi is enabled");
        RootChannel c = new RootChannel(f);
        String out = c.exec("cmd wifi status", 5000);
        ok("命令原样转给执行器", f.calls.size() == 1
                && "cmd wifi status".equals(f.calls.get(0)));
        ok("返回值原样返回", "Wifi is enabled".equals(out));
    }

    private void nullVsEmptyDistinct() {
        // 没拿到 → null
        RootChannel a = new RootChannel(new FakeExecutor("Shizuku", null));
        ok("执行器返回 null → exec 返回 null", a.exec("x", 1000) == null);
        // 拿到但空输出 → 空串
        RootChannel b = new RootChannel(new FakeExecutor("Shizuku", ""));
        String r = b.exec("x", 1000);
        ok("执行器返回空串 → exec 返回空串（不是 null）",
                r != null && r.isEmpty());
    }

    /** 注入执行器后不应起 su 会话 —— 用「open() 后 exec 不产生额外 su 调用」间接验证。 */
    private void noSuSessionWhenInjected() {
        FakeExecutor f = new FakeExecutor("Shizuku", "ok");
        RootChannel c = new RootChannel(f);
        c.open();
        ok("注入执行器后 open 成功", c.isOpen());
        c.exec("cmd wifi status", 1000);
        ok("命令只走了注入执行器一次", f.calls.size() == 1);
        c.close();
        ok("close 后不再 open", !c.isOpen());
    }

    /** 默认执行器仍是 su（Root 原链冻结）。 */
    private void defaultExecutorIsSu() {
        RootChannel c = new RootChannel();
        ok("默认执行器名为 Root", "Root".equals(c.name()));
        ok("hasShellSemantics 恒为真（两者都有 shell 语义）", c.hasShellSemantics());
    }
}
