package io.github.wlmosv_png.wpaforge;

/**
 * **「白跑」防护门禁**（2026-11-20 真机事故）。
 *
 * <h3>真机现场（OPPO PDRM00 / Android 13 / Shizuku shell 身份）</h3>
 * <pre>
 *   已执行 3 / 78 · 已验证成功 0 · 已证实错误 0 · 未确定 0
 *   ⚠ 通道/环境出错 3 条
 *   日志：通道异常 · 第 1 条 · 11s ×2
 * </pre>
 * 每条卡 11 秒（= list-networks 5s + forget-network 6s 全超时），
 * 78 条要跑 14 分钟，**一条都没真正验证过**，界面却显示「运行中 3.8%」。
 *
 * <h3>三个根因（本类逐条钉住）</h3>
 * <ol>
 *   <li><b>熔断器看不见 Shizuku 超时</b>：只查 {@code RootProber.lastTimedOut()}，
 *       而 Shizuku 模式不走 RootProber → 标志恒 false → 永不熔断。</li>
 *   <li><b>开跑前没探测净检通路</b>：自检只测 {@code cmd wifi status}（快），
 *       但实际每条要跑 list-networks + forget-network。</li>
 *   <li><b>能力面板自相矛盾</b>：关联说「可用」、净检硬编码「不支持」。</li>
 * </ol>
 */
public class WhiteRunGuardTest {

    private int pass = 0;
    private int fail = 0;

    public static void main(String[] args) {
        WhiteRunGuardTest t = new WhiteRunGuardTest();
        t.run();
        System.out.println(t.pass + " 通过 / " + t.fail + " 失败");
        if (t.fail > 0) System.exit(1);
    }

    private static String readSource(String rel) {
        try {
            String path = TestPaths.under(
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/" + rel);
            byte[] b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 去掉注释行后的源码。 */
    private static String codeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private void run() {
        breakerSeesShizukuTimeout();
        preflightProbesNetCheckPath();
        forgetCapabilityNotHardcoded();
        breakerLogSaysNotPassword();
        forgetPathSplitIntoTwoCommands();
        shizukuHasHardDeadline();
        closeStreamsBeforeJoin();
    }

    /**
     * **熔断器必须能看见超时**（2026-11-20 第二阶段）。
     *
     * <p>旧写法靠 {@code RootProber.lastTimedOut()} —— 而 Shizuku 模式根本不走
     * RootProber，标志恒 false，熔断器永不触发（真机 78 条白跑的直接原因）。
     *
     * <p>现在：超时来自 {@link io.github.wlmosv_png.wpaforge.cap.CommandExecutor.Step#timedOut}，
     * **与执行器无关** —— Root 与 Shizuku 共用同一个字段。意图一字不改：
     * 超时必须能触发熔断，而且不能只看 RootProber。
     */
    private void breakerSeesShizukuTimeout() {
        String svc = readSource("CrackService.java");
        ok("声明执行器适配器", svc.contains("CommandExecutor.ShellAdapter"));
        ok("熔断条件看 Step.timedOut", svc.contains("st.timedOut"));
        ok("熔断阈值保留", svc.contains("EXEC_TIMEOUT_LIMIT"));
        ok("超时归零在成功时", svc.contains("execTimeouts = 0;"));
        // 不再只看 RootProber
        ok("不再只有 RootProber.lastTimedOut()",
                !svc.contains("if (RootProber.lastTimedOut()) {")
                        && !svc.contains("thisTimedOut || RootProber.lastTimedOut()"));
        // CommandExecutor 必须在所有执行器上都置 timedOut
        String ce = readSource("cap/CommandExecutor.java");
        ok("CommandExecutor 对 Shizuku 置 timedOut", ce.contains("st.timedOut = r.timedOut;"));
        ok("CommandExecutor 对 su 置 timedOut", ce.contains("st.timedOut = io.github.wlmosv_png.wpaforge.audit.RootProber.lastTimedOut();"));
    }

    /** 开跑前必须实测净检通路（list-networks），不通就停。 */
    private void preflightProbesNetCheckPath() {
        String eng = readSource("audit/CrackEngine.java");
        ok("有净检通路前置探测",
                eng.contains("净检通路") && eng.contains("list-networks 在"));
        ok("探测超时即停（未消耗候选）",
                eng.contains("未消耗任何候选"));
        ok("给出具体排查方向",
                eng.contains("Shizuku 服务是否仍在运行"));
        ok("阻塞标题明确", eng.contains("通道不可用，任务未开始"));
    }

    /** 净检能力不能无条件硬编码「不支持」—— Shizuku 可用时应为可用。 */
    private void forgetCapabilityNotHardcoded() {
        String cp = readSource("cap/CapabilityProbe.java");
        ok("净检按 shizukuUsable 分支",
                cp.contains("if (x.shizukuUsable)") && cp.contains("C_WIFI_FORGET"));
        ok("Shizuku 可用时净检标记为可用",
                cp.contains("通过 Shizuku 借 shell 身份执行 forget-network"));
        ok("净检带检测证据",
                cp.contains("cmd wifi forget-network\", \"Shizuku shell\""));
    }

    /**
     * 净检路径必须**拆成两条命令**，不能合并。
     *
     * <p>合并成一条 `forget; sleep; list` 时，forget 卡住会占满整个超时，
     * 后半段回读根本没机会跑 —— 真机 11 秒就是这么来的。
     */
    private void forgetPathSplitIntoTwoCommands() {
        String eng = readSource("audit/CrackEngine.java");
        ok("forget 与回读拆成两条命令",
                !eng.contains("\"; sleep 0.15; cmd wifi list-networks\""));
        ok("forget 单独调用", eng.contains("cmd wifi forget-network \" + leftover, 4000"));
        ok("回读单独调用", eng.contains("cmd wifi list-networks\", 4000)"));
        ok("forget 超时立刻返回 false（不等回读）",
                eng.contains("forget 本身超时"));
    }

    /** ShizukuChannel 必须有硬超时兜底。 */
    private void shizukuHasHardDeadline() {
        String shz = readSource("cap/ShizukuChannel.java");
        ok("有硬超时兜底", shz.contains("硬超时兜底"));
        ok("退出循环后再检查 deadline",
                shz.contains("if (System.currentTimeMillis() >= deadline) {\n                timeout = true;"));
    }

    /**
     * **必须「先关流、再 join」** —— 这是「每条 11 秒」的真正根因。
     *
     * <p>旧顺序 join(3s) → join(3s) → close(fd)：进程没退出时两个 drain 线程
     * 阻塞在 read()，每个 join 等满 3 秒 = 6 秒；加上 5 秒 deadline = 11 秒。
     * 与用户截图「通道异常 · 11s」完全吻合。
     */
    private void closeStreamsBeforeJoin() {
        String shz = readSource("cap/ShizukuChannel.java");
        // **顺序已按真机证据改正**（2026-11-20 第三阶段）。
        //
        // 旧断言要求「close 在 join 之前」，那是上一轮的结论；
        // 真机日志（exit=0 但 out=0B、err 只读到一半）证明它会丢输出：
        // 子进程刚退出时数据还在内核缓冲里，close 让 drain 提前结束。
        // 现在正确顺序是 **先 join 读尽，只有 drain 卡住才 close 兜底**。
        String code = codeOnly(shz);
        int joinAt = code.indexOf("joinQuietly(tOut, JOIN_DRAIN_MS);");
        int closeAt = code.indexOf("closeQuietly(outFd);");
        ok("join 出现在 close 之前（先读尽再关）",
                joinAt > 0 && closeAt > 0 && joinAt < closeAt);
        ok("join 有显式时限（不再固定 3000ms）",
                shz.contains("JOIN_DRAIN_MS = 2000L")
                        && shz.contains("JOIN_FORCE_MS = 300L")
                        && !shz.contains("t.join(3000L)"));
        ok("超时先 destroy 子进程（让写端关闭，drain 才能 EOF）",
                shz.contains("超时：先杀子进程"));
    }

    /** 熔断日志必须说清「不是密码问题」。 */
    private void breakerLogSaysNotPassword() {
        String svc = readSource("CrackService.java");
        ok("熔断日志点明不是密码问题",
                svc.contains("这不是密码问题"));
        // 2026-11-20 第二阶段：通道名来自 plan.kindName()，不再写死字面量。
        // **意图不变**：熔断日志必须告知是哪条通道不行了。
        ok("熔断日志给出通道名",
                svc.contains("plan.kindName() + \"通道已不可用"));
    }
}
