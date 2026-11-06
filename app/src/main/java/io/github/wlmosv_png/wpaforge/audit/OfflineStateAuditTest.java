package io.github.wlmosv_png.wpaforge.audit;

/**
 * 离线校验的**状态层与导入判定**审计回归。
 *
 * <h3>本类覆盖本轮审计确认的问题</h3>
 * <ol>
 *   <li><b>运行互斥必须跨 Activity 实例</b>：原来只靠 Activity 的实例字段，
 *       进程被回收/重建后可以同时跑两次，结果互相覆盖；</li>
 *   <li><b>取消是协作式的</b>：引擎卡住时界面会永久锁死 → 需要"卡住"的判据；</li>
 *   <li><b>运行痕迹</b>：进程被杀后要能和"上一次跑完的结果卡"区分开；</li>
 *   <li><b>BOM</b>：Windows 记事本导出的文件第一行被当坏行，整份被拒；</li>
 *   <li><b>缺 SSID 的记录</b>：原来被算成"不是合法的 22000 记录"，
 *       把用户引向"重新导出"这条走不通的路。</li>
 * </ol>
 */
public class OfflineStateAuditTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static final String PMKID_LINE =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*"
            + "686173686361742d6573736964***";

    /** 格式合法、只是 ESSID 为空（隐藏网络）。 */
    private static final String NO_SSID_LINE =
            "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4****";

    public static void main(String[] args) {
        System.out.println("== 1. 运行互斥：进程级，跨 Activity 实例 ==");
        OfflineRunGuard.resetForTest();
        ok("初始没人持有", !OfflineRunGuard.held());
        ok("第一次申请成功", OfflineRunGuard.tryAcquire("A"));
        ok("现在被持有", OfflineRunGuard.held());
        eq("持有者", OfflineRunGuard.owner(), "A");
        ok("第二次申请必须被拒（同进程内不允许并发双跑）",
                !OfflineRunGuard.tryAcquire("B"));
        eq("持有者没有被 B 抢走", OfflineRunGuard.owner(), "A");
        OfflineRunGuard.release("B");
        ok("旧 token 释放不掉别人的任务", OfflineRunGuard.held());
        OfflineRunGuard.release("A");
        ok("持有者释放成功", !OfflineRunGuard.held());
        ok("释放后可以被别人拿到", OfflineRunGuard.tryAcquire("C"));
        OfflineRunGuard.forceRelease();
        ok("强制释放后没人持有", !OfflineRunGuard.held());

        System.out.println("== 2. 取消与「卡住」判据（引擎卡死不能永久锁死界面）==");
        OfflineRunGuard.resetForTest();
        ok("没人跑时不判卡住", !OfflineRunGuard.stalled(1_000_000L, 0));
        OfflineRunGuard.tryAcquire("D");
        ok("还没请求取消时不判卡住", !OfflineRunGuard.stalled(1_000_000L, 0));
        OfflineRunGuard.markCancel(1_000_000L);
        ok("请求过取消", OfflineRunGuard.cancelRequested());
        eq("取消时刻", OfflineRunGuard.cancelAt(), 1_000_000L);
        ok("刚取消 1 秒内不算卡住",
                !OfflineRunGuard.stalled(1_001_000L, OfflineRunGuard.DEFAULT_STALL_MS));
        ok("取消满 15 秒（默认阈值）判定卡住",
                OfflineRunGuard.stalled(1_000_000L + OfflineRunGuard.DEFAULT_STALL_MS, 0));
        ok("阈值可注入：3 秒即判",
                OfflineRunGuard.stalled(1_003_000L, 3_000L));
        ok("阈值可注入：3 秒未到不判",
                !OfflineRunGuard.stalled(1_002_999L, 3_000L));
        OfflineRunGuard.forceRelease();
        ok("强制释放后不再判卡住（否则会反复解锁）",
                !OfflineRunGuard.stalled(2_000_000L, 0));
        ok("释放后取消标记被清掉", !OfflineRunGuard.cancelRequested());

        System.out.println("== 3. 运行痕迹：进程被杀后能和「上一次的结果卡」区分 ==");
        InflightRun r = new InflightRun("cap\\x.hc22000", "07 通用口令榜\nTOP",
                "BC154FCA7169", 1793900000000L);
        InflightRun back = InflightRun.decode(r.encode());
        ok("能解回来", back != null);
        eq("材料名（含反斜杠）", back == null ? null : back.materialName, "cap\\x.hc22000");
        eq("字典名（含换行）", back == null ? null : back.dictName, "07 通用口令榜\nTOP");
        eq("字典指纹", back == null ? null : back.dictFingerprint, "BC154FCA7169");
        eq("开始时间", back == null ? null : back.startedAt, 1793900000000L);
        ok("有效", back != null && back.valid());
        ok("同一主体判定：材料+指纹相同才算同一轮",
                r.sameSubject("cap\\x.hc22000", "BC154FCA7169"));
        ok("材料不同 -> 不是同一轮", !r.sameSubject("other.hc22000", "BC154FCA7169"));
        ok("指纹不同 -> 不是同一轮", !r.sameSubject("cap\\x.hc22000", "FFFF"));
        ok("空串解不开（没有被中断的运行）", InflightRun.decode("") == null);
        ok("null 解不开", InflightRun.decode(null) == null);
        ok("乱码解不开", InflightRun.decode("hello") == null);
        ok("时间是 0 视为无效", InflightRun.decode("0|a|b|c") == null);
        ok("字段不足解不开", InflightRun.decode("123|a") == null);
        ok("空字段也能往返",
                InflightRun.decode(new InflightRun("", "", "", 5L).encode()) != null);

        System.out.println("== 4. 导入判定：BOM ==");
        OfflineImport.Result bom = OfflineImport.analyze("\uFEFF" + PMKID_LINE + "\n");
        eq("带 UTF-8 BOM 的单条材料必须 OK（修前整份被拒）",
                bom.status, OfflineImport.Status.OK);
        eq("条数", bom.count(), 1);
        OfflineImport.Result bomCrlf = OfflineImport.analyze(
                "\uFEFF" + PMKID_LINE + "\r\n");
        eq("BOM + CRLF 也 OK", bomCrlf.status, OfflineImport.Status.OK);

        System.out.println("== 5. 导入判定：缺 SSID 是「材料不可用」，不是「文件损坏」==");
        OfflineImport.Result onlyNoSsid = OfflineImport.analyze(NO_SSID_LINE + "\n");
        eq("只有缺 SSID 的记录 -> INCOMPLETE（不是 FORMAT_INVALID）",
                onlyNoSsid.status, OfflineImport.Status.INCOMPLETE);
        eq("可用条数 0", onlyNoSsid.count(), 0);
        eq("缺 SSID 计数 = 1", onlyNoSsid.noSsid, 1);

        OfflineImport.Result mix = OfflineImport.analyze(NO_SSID_LINE + "\n" + PMKID_LINE + "\n");
        eq("一条缺 SSID + 一条正常 -> 仍然 OK", mix.status, OfflineImport.Status.OK);
        eq("可用条数只算正常的", mix.count(), 1);
        eq("被跳过的缺 SSID 条数要报出来（原来静默消失）", mix.noSsid, 1);

        OfflineImport.Result badAndNoSsid = OfflineImport.analyze(
                NO_SSID_LINE + "\n" + "WPA*01*zz*aa*bb*cc***\n");
        eq("坏行优先于缺 SSID -> FORMAT_INVALID", badAndNoSsid.status,
                OfflineImport.Status.FORMAT_INVALID);

        System.out.println("== 6. 导入判定：其它边界仍然正确 ==");
        eq("空文件 -> EMPTY", OfflineImport.analyze("").status, OfflineImport.Status.EMPTY);
        eq("只有注释 -> EMPTY", OfflineImport.analyze("# hi\n# there\n").status,
                OfflineImport.Status.EMPTY);
        eq("末尾无换行也能解析",
                OfflineImport.analyze(PMKID_LINE).status, OfflineImport.Status.OK);
        eq("不支持的记录类型 -> UNSUPPORTED",
                OfflineImport.analyze("WPA*03*aa*bb*cc*dd***\n").status,
                OfflineImport.Status.UNSUPPORTED);
        OfflineImport.Result p = OfflineImport.analyze(PMKID_LINE + "\n");
        ok("算出了字节数与 SHA-256", p.bytes > 0 && p.sha256.length() == 64);
        eq("PMKID 计数", p.pmkid, 1);
        eq("EAPOL 计数", p.eapol, 0);

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
