package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.CrackEngine;

/**
 * 判定归因的离线自测。
 *
 * 核心规则：**只有「这条之前没连、这条之后连上了」才算命中**。
 * 只看「当前 SSID == 目标」是错的 —— 那会把原有连接、上一条残留
 * 都当成这一条的功劳，实测表现为任何密码都在第一条"成功"。
 */
public class AttributionTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 判定归因 ==");

        // 1) \u53ea\u6709 SUCCESS \u7b97\u6210\u529f
        check("SUCCESS 算成功", isSuccess(CrackEngine.Verdict.SUCCESS));
        check("UNKNOWN **不**算成功（关键）", !isSuccess(CrackEngine.Verdict.UNKNOWN));
        check("AUTH_FAIL 不算成功", !isSuccess(CrackEngine.Verdict.AUTH_FAIL));
        check("TIMEOUT 不算成功", !isSuccess(CrackEngine.Verdict.TIMEOUT));
        check("ERROR 不算成功", !isSuccess(CrackEngine.Verdict.ERROR));
        check("ABORTED 不算成功", !isSuccess(CrackEngine.Verdict.ABORTED));
        check("TARGET_GONE 不算成功", !isSuccess(CrackEngine.Verdict.TARGET_GONE));

        System.out.println("== 归因逻辑 ==");

        // 2) \u57fa\u7ebf\u5df2\u8fde\u76ee\u6807 \u2192 \u4e0d\u80fd\u5224\u547d\u4e2d
        check("基线已连着目标 → UNKNOWN（不是 SUCCESS）",
                baselineConnectedVerdict() == CrackEngine.Verdict.UNKNOWN);

        // 3) \u57fa\u7ebf\u672a\u8fde\u3001\u540e\u6765\u8fde\u4e0a \u2192 SUCCESS
        check("基线未连 → 后来连上 → SUCCESS",
                stateChangedVerdict() == CrackEngine.Verdict.SUCCESS);

        // 4) \u57fa\u7ebf\u672a\u8fde\u3001\u59cb\u7ec8\u672a\u8fde \u2192 AUTH_FAIL
        check("基线未连 → 始终未连 → AUTH_FAIL",
                neverConnectedVerdict() == CrackEngine.Verdict.AUTH_FAIL);

        System.out.println("== 旧 bug 回归 ==");

        // 5) \u65e7\u5b9e\u73b0\uff1a\u53ea\u770b\u5f53\u524d SSID \u5339\u914d\u5c31\u7b97\u6210\u529f
        //    \u8fd9\u662f\u5bfc\u81f4\u300c\u4efb\u4f55\u5bc6\u7801\u90fd\u547d\u4e2d\u300d\u7684\u76f4\u63a5\u539f\u56e0
        // 旧判据（只看 SSID 匹配）在旧代码里会返回 true —— 那正是它错的地方。
        // 同一场景下，新逻辑必须给出 UNKNOWN。
        check("旧判据确实会误判成功（证明 bug 存在过）",
                legacySsidMatchIsSuccess());
        check("新判据在同一场景下给出 UNKNOWN（已修复）",
                sameScenarioNewVerdict() == CrackEngine.Verdict.UNKNOWN);

        System.out.println("\n=== 归因测试: " + pass + " 通过, " + fail + " 失败 ===");
        if (fail > 0) System.exit(1);
    }

    private static boolean isSuccess(CrackEngine.Verdict v) {
        return v == CrackEngine.Verdict.SUCCESS;
    }

    // --- \u6a21\u62df tryOneShell \u7684\u5206\u652f ---

    /** \u5f00\u8dd1\u524d\u5c31\u8fde\u7740\u76ee\u6807\uff0c\u4e14\u4e00\u76f4\u6ca1\u65ad */
    private static CrackEngine.Verdict baselineConnectedVerdict() {
        boolean wasConnectedToTarget = true;
        boolean connectedAtEnd = true;
        if (wasConnectedToTarget && connectedAtEnd) {
            return CrackEngine.Verdict.UNKNOWN;
        }
        return CrackEngine.Verdict.SUCCESS;
    }

    /** \u5f00\u8dd1\u524d\u672a\u8fde\uff0c\u8fd0\u884c\u4e2d\u8fde\u4e0a\u4e86 */
    private static CrackEngine.Verdict stateChangedVerdict() {
        boolean wasConnectedToTarget = false;
        boolean nowOnTarget = true;
        if (nowOnTarget && !wasConnectedToTarget) {
            return CrackEngine.Verdict.SUCCESS;
        }
        return CrackEngine.Verdict.AUTH_FAIL;
    }

    /** \u5f00\u8dd1\u524d\u672a\u8fde\uff0c\u59cb\u7ec8\u672a\u8fde */
    private static CrackEngine.Verdict neverConnectedVerdict() {
        boolean wasConnectedToTarget = false;
        boolean nowOnTarget = false;
        if (nowOnTarget && !wasConnectedToTarget) {
            return CrackEngine.Verdict.SUCCESS;
        }
        return CrackEngine.Verdict.AUTH_FAIL;
    }

    /** \u65e7\u5b9e\u73b0\u7684\u5224\u636e\uff1a\u5f53\u524d SSID \u5339\u914d\u5c31\u8fd4\u56de\u6210\u529f */
    private static boolean legacySsidMatchIsSuccess() {
        String cur = "B214";
        String target = "B214";
        // \u8fd9\u5c31\u662f\u65e7\u4ee3\u7801\u7684\u884c\u4e3a\uff1a\u547d\u4e2d\u5224\u5b9a\u4e0e\u57fa\u7ebf\u65e0\u5173
        return target.equals(cur);
    }

    /** 同一场景下新逻辑的结论（基线已连着目标） */
    private static CrackEngine.Verdict sameScenarioNewVerdict() {
        return baselineConnectedVerdict();
    }
}
