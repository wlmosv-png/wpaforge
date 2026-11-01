package io.github.wlmosv_png.wpaforge.wps;

/**
 * D3 失败测试：**WPS 的 baseId/curId 读取失败不得被判成命中**。
 *
 * <h3>缺陷</h3>
 * <pre>
 *   curId != baseId && "COMPLETED".equals(state) && !"WPS".equals(keyMgmt)
 * </pre>
 * 解析失败时 id = -1（哨兵）。任一侧为 -1，该不等式都无条件成立 ——
 * 「读不到」被当成了「状态变了」。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>读取失败 / 超时 / 无效值 → 不可判定，**绝不产生 SUCCESS**。</li>
 *   <li>只有两侧都拿到有效 id 且确实不同 + COMPLETED + 非 WPS → 才算命中。</li>
 * </ul>
 *
 * <p>全部断言直接调用生产函数 {@link WpsHelper#isWpsHit}，
 * 它正是轮询循环里那个判定条件本身（已抽成纯函数，行为未变）。
 */
public final class WpsBaseIdTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "   " + detail); }
    }

    private static void info(String s) { System.out.println("  ·    " + s); }

    /** 读失败时 parseInt 的返回值，见 WpsHelper.parseInt(..., -1)。 */
    private static final int READ_FAIL = -1;

    public static void main(String[] args) {
        System.out.println("== D3 WPS baseId/curId 无效值 ==");

        // ---------- 1. 正常路径：baseId 有效，状态确实变化 -> 命中
        ok("1 正常：baseId=3 且 curId=7 且 COMPLETED 且非WPS -> 命中",
                WpsHelper.isWpsHit(3, 7, "COMPLETED", "WPA2-PSK"), "");

        // ---------- 2. baseId 有效，状态没有变化 -> 非命中
        ok("2 状态未变：curId == baseId -> 非命中",
                !WpsHelper.isWpsHit(3, 3, "COMPLETED", "WPA2-PSK"), "");

        // ---------- 3. baseId 读取失败（-1）-> 绝不命中
        ok("3 **baseId 读失败(-1) 且 curId=0 -> 非命中**",
                !WpsHelper.isWpsHit(READ_FAIL, 0, "COMPLETED", "WPA2-PSK"),
                "curId=0 是未连接的合法值，旧判定在此误报 SUCCESS");

        // ---------- 4. baseId 超时（同读失败语义）
        ok("4 **baseId 超时（解析为空 -> -1）-> 非命中**",
                !WpsHelper.isWpsHit(READ_FAIL, 5, "COMPLETED", "WPA2-PSK"), "");

        // ---------- 5. baseId 无效值（负数）
        ok("5 baseId 为无效负值(-2) -> 非命中",
                !WpsHelper.isWpsHit(-2, 5, "COMPLETED", "WPA2-PSK"), "");

        // ---------- 6. curId 读取失败 -> 绝不命中
        ok("6 **curId 读失败(-1) 且 baseId=3 -> 非命中**",
                !WpsHelper.isWpsHit(3, READ_FAIL, "COMPLETED", "WPA2-PSK"), "");

        // ---------- 7. 重复读取 / 状态抖动
        ok("7a 两侧都为 -1（都读不到）-> 非命中",
                !WpsHelper.isWpsHit(READ_FAIL, READ_FAIL, "COMPLETED", "WPA2-PSK"), "");
        ok("7b 抖动：curId 有效但状态非 COMPLETED -> 非命中",
                !WpsHelper.isWpsHit(3, 7, "ASSOCIATING", "WPA2-PSK"), "");
        ok("7c 抖动：curId 有效但仍处 WPS 会话 -> 非命中",
                !WpsHelper.isWpsHit(3, 7, "COMPLETED", "WPS"), "");

        // ---------- 8. 错误密码不因读取异常被判成功
        // 错误 PIN 时 supplicant 不会拿到有效网络，id 往往读不到或不变；
        // 旧判定在「读不到」时判成功 —— 这正是「错误密码被判成功」的入口。
        ok("8 **错误 PIN（读不到 id）不得被判成功**",
                !WpsHelper.isWpsHit(READ_FAIL, READ_FAIL, "COMPLETED", "WPA2-PSK")
                        && !WpsHelper.isWpsHit(READ_FAIL, 0, "COMPLETED", "WPA2-PSK"),
                "两条读失败组合都不得命中");

        // ---------- 9. 对照组：有效变化仍必须识别（不得误伤正常成功）
        ok("9 对照：baseId=0 且 curId=0 -> 非命中（未变化）",
                !WpsHelper.isWpsHit(0, 0, "COMPLETED", "WPA2-PSK"), "");
        ok("10 对照：baseId=0 且 curId=1 -> 命中（有效变化）",
                WpsHelper.isWpsHit(0, 1, "COMPLETED", "WPA2-PSK"), "");

        System.out.println();
        System.out.println("=== D3 WPS 测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }
}
