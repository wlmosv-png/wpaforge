package io.github.wlmosv_png.wpaforge.audit;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 跨重启 attemptId 唯一性测试（B4）。
 *
 * <h3>它拦的是什么</h3>
 * runId = 「秒级时间 + 目标 SSID 哈希 + 进程内序号」。断点续跑队列是**跨重启
 * 持久化**的，而重启后同一个秒、同一个 SSID、同一条候选会算出**完全相同**的
 * runId —— 队列里旧任务的 attemptId 会与重启后新生成的标记互相匹配，
 * 于是「第 11 条候选」可能读到别人窗口的日志证据。
 *
 * <p>修法：把 bootId 混进哈希输入（**不改 runId 形状**，所有解析与标记匹配
 * 逻辑一行不动）。
 *
 * <pre>
 *   newRunId(t, ssid, n, bootA) == newRunId(t, ssid, n, bootA)   // 确定性
 *   newRunId(t, ssid, n, bootA) != newRunId(t, ssid, n, bootB)   // 跨启动不同
 *   newRunId(t, ssid, n, null)  == newRunId(t, ssid, n)          // 向后兼容
 * </pre>
 *
 * <h3>C2：三层唯一性（都是可论证事实，不是概率）</h3>
 * <pre>
 *   ① 跨开机         : bootId 不同 -> 哈希段不同
 *   ② 同开机跨进程   : 毫秒级时间戳不同（进程重启耗时远大于 1ms）
 *   ③ 同进程多次创建 : runOrdinal 递增 -> 序号段不同
 * </pre>
 * 注意本设计**不依赖持久化序号**，因此天然免疫「持久化序号损坏或回滚」
 * 这一故障模式（用户列出的七种情形之一）——不需要额外的自愈代码。
 */
public final class BootUniqueTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    private static void eq(String name, int expect, int actual) {
        ok(name, expect == actual, "期望=" + expect + " 实际=" + actual);
    }

    static final long T0 = 1_764_000_000_000L;
    static final String SSID = "LAB-BOOT";
    /** 真机实测样本（/proc/sys/kernel/random/boot_id）。 */
    static final String BOOT_A = "d159099b-29b5-45f5-8c8f-da602993a032";
    static final String BOOT_B = "0f3c7a11-8e2d-4c55-9a10-7b6e5d4c3b2a";

    /** 只读 bootId 的假通道。 */
    static final class BootShell implements CrackEngine.ShellChannel {
        final String bootIdOut;
        int calls = 0;
        BootShell(String out) { bootIdOut = out; }
        @Override public String exec(String cmd, long timeoutMs) {
            if (cmd == null) return "";
            if (cmd.contains("boot_id")) { calls++; return bootIdOut; }
            return "";
        }
        @Override public String name() { return "boot-shell"; }
    }

    private static CrackEngine engineWith(String bootOut) throws Exception {
        Constructor<?> ctor = CrackEngine.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        CrackEngine e = (CrackEngine) ctor.newInstance(0);
        e.setShellChannel(new BootShell(bootOut));
        return e;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 跨重启 attemptId 唯一性（B4）===");

        // ---------------------------------------------------------- 1) 纯逻辑
        String a1 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);
        String a2 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);
        String b1 = AttemptId.newRunId(T0, SSID, 0, BOOT_B);
        String legacy = AttemptId.newRunId(T0, SSID, 0);
        String withNull = AttemptId.newRunId(T0, SSID, 0, null);
        info("bootA=" + a1 + "  bootB=" + b1 + "  legacy=" + legacy);

        ok("同一 bootId + 同参数 -> 同 runId（确定性）", a1 != null && a1.equals(a2),
                "a1=" + a1 + " a2=" + a2);
        ok("**不同 bootId + 完全同参数 -> 不同 runId**（跨重启不重号）",
                a1 != null && b1 != null && !a1.equals(b1),
                "a=" + a1 + " b=" + b1);
        ok("bootId=null 与旧三参版本逐字一致（向后兼容）",
                legacy != null && legacy.equals(withNull), "legacy=" + legacy + " null=" + withNull);
        ok("bootId 为空串等价于 null", legacy != null
                && legacy.equals(AttemptId.newRunId(T0, SSID, 0, "")), "");

        // 注意：runOf(...) 吃的是 **attemptId**（含 # 序号），不是 runId。
        // 传 runId 进去必然返回 null —— 那是本测试第一版写错的断言，不是生产缺陷。
        String idA = AttemptId.of(a1, 11);
        ok("runId 形状不变（attemptId 能解析回同一 runId）",
                idA != null && a1.equals(AttemptId.runOf(idA)), "id=" + idA);
        ok("attemptId 仍可解析回序号 11",
                idA != null && AttemptId.seqOf(idA) == 11 && AttemptId.wellFormed(idA), "id=" + idA);
        ok("跨启动的两个 attemptId 不相等",
                !String.valueOf(AttemptId.of(a1, 11)).equals(String.valueOf(AttemptId.of(b1, 11))), "");

        // ------------------------------------------------ 1b) 七种碰撞情形逐条
        System.out.println();
        System.out.println("-- 七种情形逐条验证（C2）--");

        // ① 同一秒内连续创建多个 run（秒级实现会碰撞，毫秒级不会）
        String[] sameSecond = new String[6];
        for (int k = 0; k < 6; k++) {
            sameSecond[k] = AttemptId.newRunId(T0 + k * 100L, SSID, 0, BOOT_A);
        }
        int dup1 = 0;
        for (int a = 0; a < sameSecond.length; a++) {
            for (int b = a + 1; b < sameSecond.length; b++) {
                if (sameSecond[a].equals(sameSecond[b])) dup1++;
            }
        }
        info("同一秒内 6 个 run（间隔 100ms）= " + sameSecond[0] + " ... " + sameSecond[5]);
        eq("① 同一秒内连续创建 6 个 run 互不相同", 0, dup1);

        // ② 同一毫秒 + 不同进程序号（同进程快速连续）
        String m0 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);
        String m1 = AttemptId.newRunId(T0, SSID, 1, BOOT_A);
        ok("② 同一毫秒 + 不同序号 -> 不同", m0 != null && !m0.equals(m1), m0 + " vs " + m1);

        // ③ 同一开机周期内进程重启（序号都从 0 起，时间戳不同）
        String p1 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);           // 重启前
        String p2 = AttemptId.newRunId(T0 + 300L, SSID, 0, BOOT_A);    // 重启后（300ms 后）
        ok("③ **同一开机内进程重启**（序号召回 0，时间 +300ms）-> 不同",
                p1 != null && p2 != null && !p1.equals(p2), p1 + " vs " + p2);

        // ④ 同一 SSID 是前提（下面所有用例都用同一个 SSID，故意的）
        String q1 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);
        String q2 = AttemptId.newRunId(T0 + 1L, SSID, 0, BOOT_A);
        ok("④ 同一 SSID 下仅时间差 1ms -> 不同", !q1.equals(q2), q1 + " vs " + q2);

        // ⑤ 相同进程序号（重启后都是 0）+ 设备重启（bootId 变）
        String r1 = AttemptId.newRunId(T0, SSID, 0, BOOT_A);
        String r2 = AttemptId.newRunId(T0, SSID, 0, BOOT_B);
        ok("⑤ 同 SSID 同时间同序号 + 设备重启（bootId 变）-> 不同",
                !r1.equals(r2), r1 + " vs " + r2);

        // ⑥ bootId 读取失败（null）：仍必须靠毫秒级区分跨进程
        String n1 = AttemptId.newRunId(T0, SSID, 0, null);
        String n2 = AttemptId.newRunId(T0 + 300L, SSID, 0, null);
        ok("⑥ bootId 读不到时，跨进程（+300ms 同序号）仍不碰撞",
                n1 != null && n2 != null && !n1.equals(n2), n1 + " vs " + n2);
        ok("⑥ bootId 读不到 + 完全相同参数 -> 仍确定性（可复现，便于排查）",
                n1.equals(AttemptId.newRunId(T0, SSID, 0, null)), "");

        // ⑦ 持久化序号损坏/回滚 —— 本设计不依赖它，因此无该故障面；
        //    用「连续 400 个不同毫秒」穷举，证明唯一性只由时间/序号/boot 决定。
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int collide = 0;
        for (int k = 0; k < 400; k++) {
            String id = AttemptId.newRunId(T0 + k, SSID, 0, BOOT_A);
            if (!seen.add(id)) collide++;
        }
        eq("⑦ 连续 400 个毫秒 400 个 runId 无碰撞（不依赖任何持久化状态）", 0, collide);

        // 组合穷举：3 个 bootId × 3 个时间 × 3 个序号 = 27 组，必须全不同
        java.util.HashSet<String> cross = new java.util.HashSet<>();
        String[] boots = { BOOT_A, BOOT_B, null };
        for (String bt : boots) {
            for (int t = 0; t < 3; t++) {
                for (int o = 0; o < 3; o++) {
                    cross.add(AttemptId.newRunId(T0 + t, SSID, o, bt));
                }
            }
        }
        eq("组合穷举：3 bootId × 3 时间 × 3 序号 = 27 个 runId 全不同",
                27, cross.size());

        // ---------------------------------------------------------- 2) 生产入口
        System.out.println();
        System.out.println("-- 生产入口：CrackEngine 读 boot_id --");
        CrackEngine e = engineWith(BOOT_A + "\n");
        Method m = CrackEngine.class.getDeclaredMethod("bootId");
        m.setAccessible(true);
        Object got = m.invoke(e);
        info("读到 bootId=" + got);
        ok("能从 /proc/sys/kernel/random/boot_id 读到并去空白",
                BOOT_A.equals(got), "got=" + got);

        CrackEngine e2 = engineWith(BOOT_A + "\n");
        Object got2 = m.invoke(e2);
        ok("两次读取值一致（确定性）", String.valueOf(got).equals(String.valueOf(got2)), "");

        CrackEngine e3 = engineWith("");
        Object got3 = m.invoke(e3);
        ok("读不到 -> null（降级，不伪造）", got3 == null, "got=" + got3);

        // 缓存：只读一次
        BootShell sh = new BootShell(BOOT_A + "\n");
        CrackEngine e4 = (CrackEngine) CrackEngine.class.getDeclaredConstructor(int.class)
                .newInstance(0);
        e4.setShellChannel(sh);
        m.invoke(e4); m.invoke(e4); m.invoke(e4);
        ok("bootId 被缓存（多次调用只读一次文件）", sh.calls == 1, "calls=" + sh.calls);

        // ---------------------------------------------------------- 3) 源码守卫
        System.out.println();
        System.out.println("-- 源码守卫 --");
        try {
            String root = io.github.wlmosv_png.wpaforge.TestPaths.root();
            java.io.File f = new java.io.File(root,
                    "app/src/main/java/io/github/wlmosv_png/wpaforge/audit/CrackEngine.java");
            String code = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            ok("run() 生成 runId 时传入 bootId",
                    code.contains("AttemptId.newRunId(System.currentTimeMillis(), target.ssid, runOrdinal++,\n                        bootId())"),
                    "未接线");
            ok("bootId 读不到时不伪造（返回 null 而非随机值）",
                    code.contains("bootIdCache = s;") && code.contains("return bootIdCache;"), "");
        } catch (Throwable t) {
            fail++;
            System.out.println("  FAIL 源码守卫异常: " + t);
        }

        System.out.println();
        System.out.println("=== 跨重启唯一性测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}