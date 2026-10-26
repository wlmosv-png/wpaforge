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
 * 线索档案存储：**按目标分开存**，另有一份跨目标复用的"默认模板"。
 *
 * ── 为什么不能全局只留一份 ────────────────────────────────────
 * 用户的质疑是对的："每个网络都不一样，不能按照同一套词汇吧"。
 * 但"一换网就清空"又走到另一个极端：同一家的多个 AP
 * （实测设备上 B208~B217 就是一族的）每换一个都要重填四遍。
 *
 * 所以按目标分档，并且明确区分**该不该继承**：
 *
 * | 字段 | 本目标档案 | 默认模板继承 |
 * |---|---|---|
 * | 手机号 / 生日 / 姓名拼音 | 存 | ✅ 继承（人的常量） |
 * | 门牌 / 宽带账号 | 存 | ✅ 继承（地点常量） |
 * | **旧口令** | 存 | ❌ **永不继承** |
 * | 自由词 | 存 | ✅ 继承 |
 *
 * 旧口令必须是目标专属的：它是"这个网以前用过的密码"，
 * 套到别的网上不但没用，还会把用户带偏。
 *
 * 文件：
 * <pre>
 *   files/profile/&lt;key&gt;.seeds     某目标的档案
 *   files/profile/_default.seeds   默认模板
 * </pre>
 */
public final class ProfileStore {

    private static final String DIR = "profile";
    private static final String DEFAULT_NAME = "_default.seeds";

    private ProfileStore() {}

    // ---------------------------------------------------------------- 读

    /** 本目标自己的档案；没有返回 null（不是空模型 —— "没填过"和"填了又清空"要能区分） */
    public static SeedModel loadTarget(Context ctx, String key) {
        return read(file(ctx, key));
    }

    /** 本目标是否处理过（**空档案也算** —— 见 clearTarget 的语义说明） */
    public static boolean hasTarget(Context ctx, String key) {
        return file(ctx, key).isFile();
    }

    /** 默认模板；没设过返回 null */
    public static SeedModel loadTemplate(Context ctx) {
        SeedModel m = read(new File(dir(ctx), DEFAULT_NAME));
        if (m == null) m = migrateLegacy(ctx);
        return m;
    }

    // ---------------------------------------------------------------- 写

    public static boolean saveTarget(Context ctx, String key, SeedModel m) {
        return write(file(ctx, key), m);
    }

    public static boolean saveTemplate(Context ctx, SeedModel m) {
        return write(new File(dir(ctx), DEFAULT_NAME), m);
    }

    /**
     * 清空本目标的线索。
     *
     * **写成"空档案"而不是删文件** —— 两者语义不一样：
     *   没有档案  = 从没处理过这个目标
     *   空档案    = 用户明确说过"这个网不用线索"
     * 前者将来可能被模板填充，后者永远保持空。用户点了清空就该是后者。
     */
    public static boolean clearTarget(Context ctx, String key) {
        return write(file(ctx, key), new SeedModel());
    }

    /** 本目标档案键（与已试集合同一套规则：BSSID 优先） */
    public static String key(String bssid, String ssid) {
        return TriedSet.targetKey(bssid, ssid);
    }

    /**
     * 解析"这个目标该用哪份线索"。
     *
     * **不自动继承默认模板** —— 这一点是用户教回来的：
     * 之前会自动把模板内容填进来，结果"清空本目标"看起来完全没生效
     * （清完再打开，模板的内容又冒出来了）。
     * 用户的直觉是对的：每个网不一样，**默认就该是空的**。
     *
     * 模板只在用户明确点「从默认模板填充」时才带进来，
     * 可用项数通过 {@link Pick#templateCount} 告诉界面。
     */
    public static final class Pick {
        /** 本目标档案（没有档案就是空模型） */
        public final SeedModel seeds;
        /** 本目标是否保存过（含"保存为空"—— 那是"这个网不用线索"的持久决定） */
        public final boolean hasOwn;
        /** 默认模板有几项（0 = 没设过） */
        public final int templateCount;
        /** 模板内容（已去掉旧口令），可按需带入 */
        public final SeedModel template;

        Pick(SeedModel s, boolean hasOwn, int templateCount, SeedModel template) {
            this.seeds = s;
            this.hasOwn = hasOwn;
            this.templateCount = templateCount;
            this.template = template;
        }

        /** 界面上要不要显示"从默认模板填充" */
        public boolean canFillFromTemplate() {
            return templateCount > 0 && seeds.isEmpty();
        }
    }

    public static Pick pick(Context ctx, String key) {
        return decide(loadTarget(ctx, key), loadTemplate(ctx));
    }

    /**
     * {@link #pick} 的**纯逻辑部分**，抽出来是为了能在桌面单测里钉住那条回归：
     * **清空过的目标（空档案）绝不能再被模板内容填一遍**。
     *
     * <p>用户报的正是这个：清了、再打开又出现以前输入的线索。
     * 当时两层原因 —— ① 保存与清空用了不同的 key，文件压根没删掉；
     * ② 即使删掉，这里也会自动从模板继承。两个都改了。
     *
     * @param own 本目标档案，null = 从没处理过
     * @param tpl 默认模板，null = 没设过
     */
    public static Pick decide(SeedModel own, SeedModel tpl) {
        int count = (tpl == null || tpl.isEmpty()) ? 0 : tpl.count();
        SeedModel tplClean = (tpl == null) ? null : inherit(tpl);
        return new Pick(own == null ? new SeedModel() : own, own != null, count, tplClean);
    }

    /**
     * 从模板继承：**旧口令丢掉**。
     * 其余字段照抄（人的常量与地点常量在同一家里是通用的）。
     */
    public static SeedModel inherit(SeedModel tpl) {
        SeedModel m = new SeedModel();
        m.phones.addAll(tpl.phones);
        m.names.addAll(tpl.names);
        m.birthday = tpl.birthday;
        m.houseNo = tpl.houseNo;
        m.landline = tpl.landline;
        m.customs.addAll(tpl.customs);
        m.useSsid = tpl.useSsid;
        m.oldPassword = "";     // ← 关键：永不继承
        return m;
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
        String k = key == null || key.isEmpty() ? "unknown" : key.replaceAll("[^0-9a-zA-Z_]", "");
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

    /**
     * 一次性迁移：老版本把线索存在 SharedPreferences 的单一全局槽里。
     * 那些内容**当作默认模板**搬过来（用户填的是自己家的信息，多半可复用），
     * 迁移后清掉全局槽，避免两处真相打架。
     */
    private static synchronized SeedModel migrateLegacy(Context ctx) {
        if (io.github.wlmosv_png.wpaforge.Prefs.profileMigrated(ctx)) return null;
        io.github.wlmosv_png.wpaforge.Prefs.setProfileMigrated(ctx);
        String legacy = io.github.wlmosv_png.wpaforge.Prefs.seedsRaw(ctx);
        if (legacy == null || legacy.isEmpty()) return null;
        SeedModel m = SeedModel.decode(legacy);
        if (m.isEmpty()) return null;
        write(new File(dir(ctx), DEFAULT_NAME), m);
        io.github.wlmosv_png.wpaforge.Prefs.clearSeeds(ctx);
        return m;
    }
}
