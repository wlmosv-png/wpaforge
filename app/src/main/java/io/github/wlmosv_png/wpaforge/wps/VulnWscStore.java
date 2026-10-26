package io.github.wlmosv_png.wpaforge.wps;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 「已知可打（Pixie）设备名单」的本地缓存与下载。
 *
 * <h3>为什么不打包进 APK</h3>
 * 这份名单来自社区（OneShot 项目维护），**仓库里没有 LICENSE 文件**。
 * 许可不明的东西不进安装包 —— 改成按需下载，界面上如实标注来源。
 *
 * <h3>下载通道</h3>
 * raw.githubusercontent.com 在部分网络下直连不通，所以复用字典源那套多通道探测
 * （{@link io.github.wlmosv_png.wpaforge.audit.DictSources#candidateUrls}），
 * 逐个试，谁通用谁。
 */
public final class VulnWscStore {

    /** 名单来源（OneShot 项目维护的社区名单） */
    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/kimocoder/OneShot/master/vulnwsc.txt";

    private static final String DIR = "wps";
    private static final String NAME = "vulnwsc.txt";
    /** 名单至少这么多行才算有效（防止把错误页/空文件当名单） */
    private static final int MIN_LINES = 50;

    private static volatile List<VulnWsc.Entry> cache;

    private VulnWscStore() {}

    public static File file(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return new File(d, NAME);
    }

    public static boolean exists(Context ctx) {
        File f = file(ctx);
        return f.isFile() && f.length() > 0;
    }

    /** 读名单（进程内缓存）。没有名单返回空表，不抛。 */
    public static List<VulnWsc.Entry> load(Context ctx) {
        List<VulnWsc.Entry> c = cache;
        if (c != null) return c;
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file(ctx)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            // 读不到就当没有名单：上层按「未标记」处理，不编造命中
        }
        List<VulnWsc.Entry> parsed = VulnWsc.parse(lines);
        cache = parsed;
        return parsed;
    }

    public static void clearCache() {
        cache = null;
    }

    public static final class Result {
        public final boolean ok;
        public final int entries;
        public final String message;

        Result(boolean ok, int entries, String message) {
            this.ok = ok;
            this.entries = entries;
            this.message = message;
        }
    }

    /** 下载名单并落盘。阻塞，调用方自己放后台线程。 */
    public static Result update(Context ctx) {
        String[] urls = io.github.wlmosv_png.wpaforge.audit.DictSources
                .candidateUrls(SOURCE_URL);
        String lastErr = "";
        for (String u : urls) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "WPAForge/1.0");
                if (c.getResponseCode() != 200) {
                    lastErr = "HTTP " + c.getResponseCode();
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                int lines = 0;
                try (InputStream in = c.getInputStream();
                     BufferedReader r = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.length() > 200) continue;
                        sb.append(line).append('\n');
                        lines++;
                    }
                }
                if (lines < MIN_LINES) {
                    lastErr = "内容不像名单（" + lines + " 行）";
                    continue;
                }
                File dst = file(ctx);
                try (FileOutputStream o = new FileOutputStream(dst)) {
                    o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                cache = null;
                int n = load(ctx).size();
                return new Result(true, n, "OK");
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName();
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            }
        }
        return new Result(false, 0, lastErr.isEmpty() ? "no channel" : lastErr);
    }
}
