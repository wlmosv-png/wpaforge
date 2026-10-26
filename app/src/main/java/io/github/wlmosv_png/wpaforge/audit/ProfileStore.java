package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 线索档案：**按目标分开存，仅此一种来源**。
 *
 * ── 为什么按目标分 ────────────────────────────────────────────
 * 用户的原话："每个网络都不一样，不能按照同一套词汇吧"。
 * 所以每个网一份 `files/profile/&lt;bssid&gt;.seeds`，互不影响。
 *
 * ── 为什么没有"默认模板" ─────────────────────────────────────
 * 上一版做过一个跨目标的"默认模板"，用户直接砍掉：
 * 「默认模块没必要存在，每个 wifi 都是不一样的，你把这个设计了有啥用」。
 * 事后看是对的 —— 它带来的唯一便利（同一家的多个 AP 不用重填）
 * 远小于它造成的困惑：模板内容会被自动填进每个新目标，
 * 于是"清空本目标"看起来完全失效（清完再打开，模板内容又冒出来）。
 * 现在打开就是空的。
 *
 * ── 两种"空"要分清 ──────────────────────────────────────────
 *   没有档案 = 从没处理过这个目标
 *   空档案   = 用户明确清空过（写空文件而不是删文件，让这个决定是持久的）
 */
public final class ProfileStore {

    private static final String DIR = "profile";
    /** 上一版遗留的默认模板文件名；已废弃，会被清掉 */
    private static final String LEGACY_TEMPLATE = "_default.seeds";

    private ProfileStore() {}

    // ---------------------------------------------------------------- 读

    /** 本目标档案；null = 从没处理过（注意：空档案返回的是空模型，不是 null） */
    public static SeedModel loadTarget(Context ctx, String key) {
        return read(file(ctx, key));
    }

    /** 本目标是否处理过（**空档案也算** —— 见 clearTarget 的语义说明） */
    public static boolean hasTarget(Context ctx, String key) {
        return file(ctx, key).isFile();
    }

    // ---------------------------------------------------------------- 写

    public static boolean saveTarget(Context ctx, String key, SeedModel m) {
        return write(file(ctx, key), m);
    }

    /**
     * 清空本目标的线索。
     *
     * **写成"空档案"而不是删文件** —— 两者语义不一样：
     *   没有档案 = 从没处理过这个目标
     *   空档案   = 用户明确说过"这个网不用线索"
     * 前者将来可能被其它逻辑填充，后者永远保持空。
     */
    public static boolean clearTarget(Context ctx, String key) {
        return write(file(ctx, key), new SeedModel());
    }

    /** 本目标档案键（与已试集合同一套规则：BSSID 优先） */
    public static String key(String bssid, String ssid) {
        return TriedSet.targetKey(bssid, ssid);
    }

    // ---------------------------------------------------------------- 决策

    /**
     * 这个目标该用哪份线索。只有本目标档案这一种来源。
     *
     * <p>顺带做一次老数据迁移：上一版把线索存在 SharedPreferences 的单一槽里，
     * 现在把它迁到**当前目标**档案（用户填的就是他正在弄的那个网）。
     * 还不知道在弄哪个网就先留着，等有目标了再迁 —— 不凭空造一份模板。
     */
    public static Pick pick(Context ctx, String key) {
        migrateLegacy(ctx, key);
        removeLegacyTemplate(ctx);
        return decide(loadTarget(ctx, key));
    }

    /** {@link #pick} 的纯逻辑部分（桌面单测用） */
    public static Pick decide(SeedModel own) {
        return new Pick(own == null ? new SeedModel() : own, own != null);
    }

    public static final class Pick {
        /** 本目标档案（没有档案就是空模型） */
        public final SeedModel seeds;
        /** 本目标是否保存过（含"保存为空"= 用户说过这个网不用线索） */
        public final boolean hasOwn;

        Pick(SeedModel s, boolean hasOwn) {
            this.seeds = s;
            this.hasOwn = hasOwn;
        }
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * 一次性迁移：老版本的单槽线索 → 当前目标档案。
     *
     * @param key 当前目标键；为空表示还不知道在弄哪个网，直接跳过（下次再迁）
     */
    public static synchronized void migrateLegacy(Context ctx, String key) {
        if (io.github.wlmosv_png.wpaforge.Prefs.profileMigrated(ctx)) return;
        if (key == null || key.isEmpty()) return;
        String legacy = io.github.wlmosv_png.wpaforge.Prefs.seedsRaw(ctx);
        io.github.wlmosv_png.wpaforge.Prefs.setProfileMigrated(ctx);
        if (legacy == null || legacy.isEmpty()) return;
        SeedModel m = SeedModel.decode(legacy);
        if (m.isEmpty()) {
            io.github.wlmosv_png.wpaforge.Prefs.clearSeeds(ctx);
            return;
        }
        // 只在目标档案还不存在时写；**写成功才清旧值**（失败就留着，别把用户填的抹了）
        if (!file(ctx, key).isFile() && write(file(ctx, key), m)) {
            io.github.wlmosv_png.wpaforge.Prefs.clearSeeds(ctx);
        }
    }

    /** 清掉上一版遗留的默认模板文件（功能已砍，文件不该留在盘上） */
    public static void removeLegacyTemplate(Context ctx) {
        File f = new File(dir(ctx), LEGACY_TEMPLATE);
        if (f.isFile() && f.delete()) {
            warn("已清理上一版遗留的默认模板");
        }
    }

    // ---------------------------------------------------------------- 底层文件

    private static void warn(String msg) {
        try {
            android.util.Log.w("WPAForge", msg);
        } catch (Throwable ignored) {
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File file(Context ctx, String key) {
        String k = key == null || key.isEmpty()
                ? "unknown" : key.replaceAll("[^0-9a-zA-Z_]", "");
        return new File(dir(ctx), k + ".seeds");
    }

    private static SeedModel read(File f) {
        if (f == null || !f.isFile()) return null;
        // 存在但为空 = 用户清空过，返回空模型（不是 null）—— 见 clearTarget
        if (f.length() == 0) return new SeedModel();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return SeedModel.decode(sb.toString());
        } catch (Exception e) {
            warn("线索档案读取失败: " + e);
            return null;
        }
    }

    private static boolean write(File f, SeedModel m) {
        if (f == null || m == null) return false;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(m.encode());
            w.flush();
            return true;
        } catch (Exception e) {
            warn("线索档案写入失败: " + e);
            return false;
        }
    }
}
