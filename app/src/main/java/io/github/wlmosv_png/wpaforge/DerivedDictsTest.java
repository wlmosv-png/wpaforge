package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.DerivedDicts;
import io.github.wlmosv_png.wpaforge.audit.TaskQueue;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 派生产物（缓存）识别与清理自检。
 *
 * <p>盯四件事：
 * <ol>
 *   <li><b>识别</b>：中英文名、新旧命名（{@code SSID 派生}、{@code 00 内置生成器}）都要认；
 *       用户自己的字典一个都不能误判成派生；</li>
 *   <li><b>归组</b>：带指纹与不带指纹是同一目标；旧名 {@code SSID 派生 · X}
 *       不能被并成「SSID 派生」一组（否则滚动删除会连别的目标的产物一起删）；</li>
 *   <li><b>滚动</b>：写新文件前只删**同组**的旧产物，同名与非派生物不动；</li>
 *   <li><b>保护</b>：排队 / 正在跑 / 暂停中的任务引用的文件**永远不删**
 *       —— 这条是硬约束，删了续跑的 offset 就对不上。</li>
 * </ol>
 */
public class DerivedDictsTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  " + detail); }
    }

    public static void main(String[] a) throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "wpf-derived-test");
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        for (File f : tmp.listFiles()) { //noinspection ResultOfMethodCallIgnored
            f.delete();
        }

        System.out.println("== 识别 ==");
        ok("新版派生名", DerivedDicts.isDerivedName("定向派生 · B209"), "");
        ok("新版派生名（带指纹）", DerivedDicts.isDerivedName("定向派生 · B209 · 55d32539"), "");
        ok("旧版派生名", DerivedDicts.isDerivedName("SSID 派生 · 2.4G-3"), "");
        ok("旧版派生名（带指纹）", DerivedDicts.isDerivedName("SSID 派生 · FAST_E972 · a69644f7"), "");
        ok("生成器（现行名）", DerivedDicts.isDerivedName("内置生成器.txt"), "");
        ok("生成器（历史名）", DerivedDicts.isDerivedName("00 内置生成器.txt"), "");
        ok("生成器（英文名）", DerivedDicts.isDerivedName("Built-in generator.txt"), "");
        ok("英文派生名", DerivedDicts.isDerivedName("Targeted · B209 · 55d32539"), "");
        ok("用户自己的表不算", !DerivedDicts.isDerivedName("01 内置弱口令常用.txt"), "");
        ok("下载的表不算", !DerivedDicts.isDerivedName("WPA 专用 TOP 4800.txt"), "");
        ok("合并结果不算", !DerivedDicts.isDerivedName("合并-5687.txt"), "");
        ok("空名不算", !DerivedDicts.isDerivedName("") && !DerivedDicts.isDerivedName(null), "");
        ok("「派生」出现在中间不算（只有前缀才算）",
                !DerivedDicts.isDerivedName("厂商算法（BSSID 派生）列表.txt"), "");

        System.out.println("== 归组 ==");
        ok("带指纹与不带指纹同组",
                DerivedDicts.groupKey("定向派生 · B209 · 55d32539")
                        .equals(DerivedDicts.groupKey("定向派生 · B209")), "");
        ok("不同目标不同组",
                !DerivedDicts.groupKey("定向派生 · B209").equals(DerivedDicts.groupKey("定向派生 · B214")), "");
        ok("旧名不会被并成「SSID 派生」一组",
                !DerivedDicts.groupKey("SSID 派生 · 2.4G-3").equals(DerivedDicts.groupKey("SSID 派生 · FAST_E972")),
                DerivedDicts.groupKey("SSID 派生 · 2.4G-3"));
        ok("生成器的历史名与现行名同组",
                DerivedDicts.groupKey("00 内置生成器.txt").equals(DerivedDicts.groupKey("内置生成器.txt")), "");
        ok("生成器组是特殊键",
                DerivedDicts.groupKey("内置生成器").equals(DerivedDicts.GROUP_GENERATOR), "");

        System.out.println("== 滚动（sameGroup）==");
        File[] mk = {
                new File(tmp, "定向派生 · B209.txt"),
                new File(tmp, "定向派生 · B209 · 55d32539.txt"),
                new File(tmp, "定向派生 · B214.txt"),
                new File(tmp, "SSID 派生 · 2.4G-3 · a69644f7.txt"),
                new File(tmp, "01 内置弱口令常用.txt"),
                new File(tmp, "00 内置生成器.txt"),
        };
        for (File f : mk) {
            try (FileOutputStream o = new FileOutputStream(f)) { o.write("12345678\n".getBytes()); }
        }
        List<File> same = DerivedDicts.sameGroup(tmp, "定向派生 · B209.txt");
        ok("同组只挑出 B209 的旧产物（1 份）", same.size() == 1, "" + same.size());
        ok("挑出的是带指纹那份",
                same.get(0).getName().equals("定向派生 · B209 · 55d32539.txt"), same.get(0).getName());

        List<File> forGen = DerivedDicts.sameGroup(tmp, "内置生成器.txt");
        ok("生成器滚动只挑历史名那一份", forGen.size() == 1
                && forGen.get(0).getName().equals("00 内置生成器.txt"),
                forGen.toString());
        ok("同名文件不算旧产物（走覆盖写）",
                DerivedDicts.sameGroup(tmp, "定向派生 · B214.txt").isEmpty(), "");
        ok("非派生物永不被挑中",
                DerivedDicts.sameGroup(tmp, "定向派生 · B209.txt").stream()
                        .noneMatch(f -> f.getName().startsWith("01 ")), "");

        System.out.println("== 保护名单 ==");
        TaskQueue.Task pending = new TaskQueue.Task();
        pending.dictPath = new File(tmp, "定向派生 · B209.txt").getAbsolutePath();
        pending.state = TaskQueue.State.PENDING;
        TaskQueue.Task running = new TaskQueue.Task();
        running.dictPath = new File(tmp, "定向派生 · B209 · 55d32539.txt").getAbsolutePath();
        running.state = TaskQueue.State.RUNNING;
        TaskQueue.Task paused = new TaskQueue.Task();
        paused.dictPath = new File(tmp, "SSID 派生 · 2.4G-3 · a69644f7.txt").getAbsolutePath();
        paused.state = TaskQueue.State.PAUSED;
        TaskQueue.Task done = new TaskQueue.Task();
        done.dictPath = new File(tmp, "定向派生 · B214.txt").getAbsolutePath();
        done.state = TaskQueue.State.DONE_MISS;
        TaskQueue.Task generationOnly = new TaskQueue.Task();
        generationOnly.dictPath = "";
        generationOnly.state = TaskQueue.State.PENDING;

        List<TaskQueue.Task> tasks = new ArrayList<>(Arrays.asList(
                pending, running, paused, done, generationOnly, null));
        Set<String> prot = DerivedDicts.protectedPaths(tasks);
        ok("排队/正在跑/暂停都进保护名单", prot.size() == 3, "" + prot.size());
        ok("已完成的不进保护名单", !prot.contains(done.dictPath), "");
        ok("空路径不进保护名单（内置生成器任务）", !prot.contains(""), "");
        ok("null 任务不炸", DerivedDicts.protectedPaths(new ArrayList<>()).isEmpty(), "");

        System.out.println("== 清理（victims）==");
        List<File> all = new ArrayList<>(Arrays.asList(tmp.listFiles()));
        List<File> victims = DerivedDicts.victims(all,
                f -> f.getName(), f -> f.getAbsolutePath(), prot);
        // 5 份派生 − 3 份被任务占着 = 2 份可清（已完成那轮 + 历史名的生成器）
        ok("5 份派生里挑出 2 份可清", victims.size() == 2, victims.toString());
        ok("可清的是「已完成的 B214」与「历史名 00 内置生成器」",
                victims.stream().anyMatch(f -> f.getName().equals("定向派生 · B214.txt"))
                        && victims.stream().anyMatch(f -> f.getName().equals("00 内置生成器.txt")),
                victims.toString());
        ok("非派生物（01 弱口令）不在清理对象里",
                victims.stream().noneMatch(f -> f.getName().startsWith("01 ")), "");
        ok("被任务占着的绝不进清理对象",
                victims.stream().noneMatch(f -> prot.contains(f.getAbsolutePath())), "");

        Set<String> none = new HashSet<>();
        List<File> all2 = DerivedDicts.victims(all, f -> f.getName(), f -> f.getAbsolutePath(), none);
        ok("没有任务占用时可清 5 份派生", all2.size() == 5, "" + all2.size());
        ok("victims 也认历史名（00 内置生成器）",
                all2.stream().anyMatch(f -> f.getName().equals("00 内置生成器.txt")), "");

        System.out.println("== 体积显示 ==");
        ok("小于 1KB 用 B", DerivedDicts.sizeLabel(512).endsWith("B"), DerivedDicts.sizeLabel(512));
        ok("KB 级", DerivedDicts.sizeLabel(112 * 1024).equals("112 KB"), DerivedDicts.sizeLabel(112 * 1024));
        ok("MB 级", DerivedDicts.sizeLabel(5 * 1024 * 1024 + 512 * 1024).equals("5.5 MB"),
                DerivedDicts.sizeLabel(5 * 1024 * 1024 + 512 * 1024));

        System.out.println("== 命名（唯一入口）==");
        ok("生成器规范名不带 00 前缀",
                !DerivedDicts.generatorName().startsWith("00 "), DerivedDicts.generatorName());
        ok("派生名带指纹",
                DerivedDicts.targetedName("B209", "55d32539").endsWith(" · 55d32539"), "");
        ok("无指纹时不补分隔符",
                !DerivedDicts.targetedName("B209", "").contains(" · " + " · "), "");
        ok("指纹为 none 时也不补", !DerivedDicts.targetedName("B209", "none").endsWith("none"), "");

        System.out.println();
        System.out.println("=== 派生产物测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
