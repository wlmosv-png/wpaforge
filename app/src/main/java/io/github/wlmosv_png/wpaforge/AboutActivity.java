package io.github.wlmosv_png.wpaforge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wlmosv_png.wpaforge.ui.AuthorCard;

/**
 * 关于页。与「设置」分开 —— 这里只讲人和项目，不放功能开关。
 */
public class AboutActivity extends Activity {

    private static final int BG       = Color.parseColor("#0E0E11");
    private static final int SURFACE  = Color.parseColor("#1B1B24");
    private static final int ACCENT   = Color.parseColor("#FFB300");
    private static final int TEXT     = Color.parseColor("#E8E8EE");
    private static final int TEXT_DIM = Color.parseColor("#8A8A94");
    private static final int OK_GREEN = Color.parseColor("#4CAF50");

    private static final String GH     = "https://github.com/wlmosv-png";
    private static final String TG     = "https://t.me/wlmosv_bunny";

    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        dp = getResources().getDisplayMetrics().density;
        buildUi();
    }

    private int px(float v) { return Math.round(v * dp); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 标题栏
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(20), px(44), px(20), px(10));

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTextColor(ACCENT);
        back.setPadding(0, 0, px(14), 0);
        back.setOnClickListener(v -> finish());
        head.addView(back);

        TextView title = new TextView(this);
        title.setText(L.s("关于", "About"));
        title.setTextSize(20);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(title);
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(16), px(6), px(16), px(32));

        // ---- 作者卡片（带头像）----
        AuthorCard card = new AuthorCard(this);
        card.set("wlmosv", L.s("Android · Xposed · 逆向", "Android · Xposed · Reverse engineering"), "W");
        try {
            android.graphics.Bitmap bmp = BitmapFactory.decodeResource(
                    getResources(), R.drawable.author_avatar);
            if (bmp != null) card.setAvatar(bmp);
        } catch (Exception ignored) {}
        content.addView(card);

        // ---- 链接 ----
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setPadding(0, px(12), 0, px(6));
        links.addView(linkChip("GitHub", v -> open(GH)));
        links.addView(linkChip("Telegram", v -> open(TG)));
        content.addView(links);

        // ---- 一句话 ----
        content.addView(section(L.s("关于我", "About me")));
        content.addView(block(L.s("写 Android 工具，做 Xposed 模块，折腾逆向。", "I build Android tools, write Xposed modules, and dig into reverse engineering.")
                + L.s("只写能复现的东西 —— 每个结论都配得上一条命令、一段日志，", "I only write down things I can reproduce — every claim comes with a command, a log,")
                + "或者一个能跑的用例。\n\n"
                + "TG：@wlmosv_bunny"));

        // ---- 项目 ----
        content.addView(section(L.s("项目", "Projects")));

        content.addView(projectCard(
                "WPAForge",
                L.s("这个应用本身", "This app itself"),
                L.s("WPA 密码破解工具。扫描周边网络、按目标派生候选、", "A WPA passphrase cracker. Scans nearby networks, derives candidates from the target,")
                + L.s("逐条尝试连接。所有算法自研，加密部分过了 RFC 3174 / 2202 / 6070 三套标准向量。", "and tries them one by one. All algorithms are self-implemented; the crypto passes RFC 3174 / 2202 / 6070 vectors."),
                new String[]{L.s("当前项目", "Current project"), "Apache-2.0"},
                null));

        content.addView(projectCard(
                "TGAutoSign",
                L.s("Telegram 自动签到 Xposed 模块", "Xposed module for automated Telegram check-ins"),
                L.s("多账号槽位、群签到判定、定时计划、多主题界面、按钮学习、中英双语。", "Multi-account slots, group check-in classification, scheduled plans, multi-theme UI, button learning, bilingual.")
                + L.s("已适配官方 Telegram 与 NagramX。管理入口：任意聊天发 /jmb。", "Works with official Telegram and NagramX. Entry point: send /jmb in any chat."),
                new String[]{"Xposed", L.s("多账号", "Multi-account"), L.s("多主题", "Multi-theme")},
                v -> showTgAutoSign()));

        content.addView(projectCard(
                "apk-index",
                L.s("APK 静态索引与逆向检索工具", "Static APK indexing and RE search tool"),
                L.s("解 AXML、逐 dex 建索引、按字符串 / 签名 / 结构反查 hook 点，", "Parses AXML, indexes every dex, finds hook points by string / signature / structure,")
                + L.s("支持跨版本混淆名漂移对比。可作为 MCP 服务接入。", "and diffs obfuscated names across versions. Usable as an MCP service."),
                new String[]{L.s("逆向", "Reverse engineering"), "MCP", "APK"},
                v -> open(GH)));

        // ---- 感谢 ----
        content.addView(section(L.s("致谢", "Credits")));
        content.addView(block("在线字典数据来自 SecLists 项目（MIT 许可）及其他公开安全数据集。\n\n"
                + L.s("感谢所有把工具和知识公开出来的人。", "Thanks to everyone who publishes their tools and knowledge.")));

        // ---- 版本 ----
        content.addView(section(L.s("版本", "Version")));
        TextView ver = new TextView(this);
        ver.setText("WPAForge 0.5.0 (5)");
        ver.setTextSize(12);
        ver.setTextColor(TEXT_DIM);
        content.addView(ver);

        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11.5f);
        t.setTextColor(TEXT_DIM);
        t.setLetterSpacing(0.12f);
        t.setPadding(px(4), px(20), 0, px(10));
        return t;
    }

    private TextView block(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(TEXT_DIM);
        t.setLineSpacing(px(5), 1f);
        t.setBackground(roundBg(SURFACE, 18));
        t.setPadding(px(18), px(16), px(18), px(16));
        return t;
    }

    private Button linkChip(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(ACCENT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(Color.parseColor("#1F1F2A"), 18));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(16), px(10), px(16), px(10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(9);
        b.setLayoutParams(lp);
        return b;
    }

    private View projectCard(String title, String tagline, String desc,
                             String[] tags, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundBg(SURFACE, 18));
        box.setPadding(px(18), px(16), px(18), px(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(10);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15.5f);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);

        TextView tag = new TextView(this);
        tag.setText(tagline);
        tag.setTextSize(11.5f);
        tag.setTextColor(ACCENT);
        tag.setPadding(0, px(4), 0, 0);
        box.addView(tag);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(TEXT_DIM);
        d.setLineSpacing(px(4), 1f);
        d.setPadding(0, px(8), 0, 0);
        box.addView(d);

        if (tags != null && tags.length > 0) {
            LinearLayout tagRow = new LinearLayout(this);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setPadding(0, px(10), 0, 0);
            for (String s2 : tags) {
                TextView chip = new TextView(this);
                chip.setText(s2);
                chip.setTextSize(10);
                chip.setTextColor(TEXT_DIM);
                chip.setBackground(roundBg(Color.parseColor("#232330"), 10));
                chip.setPadding(px(9), px(3), px(9), px(3));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = px(6);
                tagRow.addView(chip, clp);
            }
            box.addView(tagRow);
        }

        if (onClick != null) box.setOnClickListener(onClick);
        return box;
    }

    private android.graphics.drawable.Drawable roundBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    private void showTgAutoSign() {
        io.github.wlmosv_png.wpaforge.ui.NetDialog d =
                new io.github.wlmosv_png.wpaforge.ui.NetDialog(this, "TGAutoSign",
                        "Telegram 自动签到的 Xposed 模块。\n\n"
                        + "· 管理入口：任意聊天发 /jmb\n"
                        + "· 多账号槽位，每个账号独立配置\n"
                        + "· 群签到判定：6 类互斥归类\n"
                        + "· 定时计划：可设每天几点跑，跨天窗口也对\n"
                        + "· 面板新鲜度检测：按钮过期会自动取最新的重发\n"
                        + "· 多主题（Prism）、按钮学习、中英双语\n\n"
                        + "适配官方 Telegram、NagramX、NekoX 等宿主。");
        d.addHint(L.s("需要 LSPosed 环境。", "Requires an LSPosed environment."));
        d.setNegative(L.s("关闭", "Close"), null);
        d.setPositive(L.s("去 GitHub", "Open GitHub"), dd -> open(GH));
        d.show();
    }
}
