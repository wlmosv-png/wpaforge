package io.github.wlmosv_png.wpaforge.audit;

import io.github.wlmosv_png.wpaforge.L;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 内置字典释放器。
 *
 * 应用自带几份基础字典（打包在 assets/builtin 里），
 * 首次启动时释放到字典目录，用户可以直接用，不需要联网下载。
 *
 * 释放策略：用标记文件记录已释放的版本，避免每次启动都覆盖用户改过的内容。
 */
public final class BuiltinDicts {

    /** 内置字典清单：assets 文件名 -> 释放后的中文名 */
    private static final String[][] ITEMS = {
            {"weak.txt",         L.s("01 内置弱口令常用.txt", "01 Built-in common weak.txt")},
            {"digits.txt",       L.s("02 内置纯数字弱口令.txt", "02 Built-in digits weak.txt")},
            {"alnum.txt",        L.s("03 内置字母数字混合.txt", "03 Built-in alphanumeric.txt")},
            {"words.txt",        L.s("04 内置英文单词.txt", "04 Built-in English words.txt")},
            {"numbers.txt",      L.s("05 内置号码变形.txt", "05 Built-in number variants.txt")},
            {"rule_8digit.txt",  L.s("90 规则-8位数字.txt", "90 Rule-8digit.txt")},
            {"rule_10digit.txt", L.s("91 规则-10位数字.txt", "91 Rule-10digit.txt")},
            {"rule_date.txt",    L.s("92 规则-日期区间.txt", "92 Rule-date range.txt")},
    };

    /** 释放版本。改动内置内容时把这个数字加一，下次启动会重新释放。 */
    private static final int RELEASE_VERSION = 1;
    private static final String MARK = ".builtin_v";

    private BuiltinDicts() {}

    public static final class Result {
        public final int released;
        public final int skipped;
        public final String message;

        Result(int r, int s, String m) {
            released = r;
            skipped = s;
            message = m;
        }
    }

    /** 是否需要释放 */
    public static boolean needsRelease(Context ctx) {
        File mark = new File(DictStore.defaultDir(ctx), MARK + RELEASE_VERSION);
        return !mark.exists();
    }

    /**
     * 释放内置字典到字典目录。
     * 已存在的同名文件不覆盖（用户可能改过），只补缺失的。
     */
    public static Result release(Context ctx) {
        File dir = DictStore.defaultDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) {
            return new Result(0, 0, L.s("无法创建字典目录", "Cannot create dictionary folder"));
        }

        int ok = 0, skip = 0;
        StringBuilder fail = new StringBuilder();

        for (String[] item : ITEMS) {
            File out = new File(dir, item[1]);
            if (out.exists() && out.length() > 0) {
                skip++;
                continue;
            }
            if (copyAsset(ctx, "builtin/" + item[0], out)) {
                ok++;
            } else {
                fail.append(item[1]).append(" ");
            }
        }

        // 写标记
        try {
            File mark = new File(dir, MARK + RELEASE_VERSION);
            try (FileOutputStream f = new FileOutputStream(mark)) {
                f.write((System.currentTimeMillis() + "\n").getBytes());
            }
        } catch (Exception ignored) {}

        String msg = L.s("释放 ", "Restored ") + ok + L.s(" 个，已存在跳过 ", " entries, skipped ") + skip + L.s(" 个", " existing");
        if (fail.length() > 0) msg += L.s("，失败: ", ", failed: ") + fail;
        return new Result(ok, skip, msg);
    }

    private static boolean copyAsset(Context ctx, String assetPath, File dest) {
        try (InputStream in = ctx.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return dest.length() > 0;
        } catch (Exception e) {
            android.util.Log.w("WPAForge", L.s("释放 ", "Restored ") + assetPath + " 失败: " + e);
            return false;
        }
    }

    /** 内置字典总条目数（供界面展示） */
    public static int count() {
        return ITEMS.length;
    }
}
