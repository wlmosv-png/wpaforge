package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.TaskQueue;

import java.util.ArrayList;
import java.util.List;

/**
 * 「队列读失败 → 被写空」这条数据丢失链的防线测试。
 *
 * <p>真实事故（2026-10-23）：队列里 16 个任务、其中 5 条已爆破出的口令，
 * 被写成了 `[]`。链条是 {@code load()} 读不到时静默返回空列表，
 * 调用方（删除/清理/入队）拿着空列表回写 —— 整个队列永久消失。
 *
 * <p>防线是 {@link TaskQueue#refuseSave}：只有「刚读失败过」+「要写空」同时成立才拒绝，
 * 用户自己清空队列不受影响。这里把两条边界都钉住。
 */
public class QueueGuardTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 队列防空写 ==");

        List<TaskQueue.Task> empty = new ArrayList<>();
        List<TaskQueue.Task> one = new ArrayList<>();
        TaskQueue.Task t = new TaskQueue.Task();
        t.id = "x";
        t.ssid = "B212";
        one.add(t);

        check("读失败 + 写空 → 拒绝（这就是那次丢队列）",
                TaskQueue.refuseSave(empty, true));
        check("读失败 + 写空(list=null) → 拒绝",
                TaskQueue.refuseSave(null, true));
        check("读取正常 + 用户清空 → 放行（别堵正常功能）",
                !TaskQueue.refuseSave(empty, false));
        check("读失败 + 写非空 → 放行（还有救回来的内容）",
                !TaskQueue.refuseSave(one, true));
        check("读取正常 + 写非空 → 放行",
                !TaskQueue.refuseSave(one, false));

        check("初始状态（没读过）不算读失败",
                !TaskQueue.lastLoadFailed());

        System.out.println();
        System.out.println("队列防线: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
