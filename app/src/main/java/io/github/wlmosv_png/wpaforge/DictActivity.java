package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.ui.AmberTheme;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import io.github.wlmosv_png.wpaforge.ui.DictIcons;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictRule;
import io.github.wlmosv_png.wpaforge.audit.BuiltinDicts;
import io.github.wlmosv_png.wpaforge.audit.DictSources;
import io.github.wlmosv_png.wpaforge.audit.DictStore;
import io.github.wlmosv_png.wpaforge.ui.NetDialog;

/**
 * 字典管理。列表 / 查看 / 编辑 / 新建 / 导入 / 合并 / 删除。
 * 全部在设备上操作纯文本文件，用户可随时用文件管理器查看。
 */
public class DictActivity extends Activity {

    private static final int BG       = AmberTheme.BG;
    private static final int SURFACE  = AmberTheme.CARD;
    private static final int ACCENT   = AmberTheme.ACCENT;
    private static final int TEXT     = AmberTheme.TEXT;
    private static final int TEXT_DIM = AmberTheme.TEXT_DIM;
    private static final int DANGER   = AmberTheme.DANGER;
    private static final int OK_GREEN = AmberTheme.OK;

    private static final int REQ_PICK = 2001;

    private LinearLayout listBox;
    private TextView pathLabel;
    private TextView statLabel;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    /** 选中的字典（用于合并） */
    private final List<DictStore.Entry> selected = new ArrayList<>();

    /* ── 列表的筛选 / 排序 / 多选模式 ────────────────────────────────
     * 32 本字典、7 MB 的时候，用户要的是"按大小排，先跑小的"和
     * "哪些跑过、别重复跑"。所以列表必须能筛能排，而不是一个平铺的清单。 */
    private enum Filter { ALL, TRIED, UNTRIED }
    private enum SortBy { SIZE, NAME, RECENT }
    private Filter filter = Filter.ALL;
    private SortBy sortBy = SortBy.SIZE;

    /** 是否处于多选模式 —— 有模式才有分层：顶部报已选、底部升操作条 */
    private boolean selectMode = false;
    private LinearLayout selectBar;
    private TextView selectCount;

    /**
     * 每本字典的跑过进度：绝对路径 → {已跑条数, 总条数}。
     * 数据来自**任务队列**（Task.dictPath + offset/dictSize），不需要新增任何存储。
     */
    private final java.util.Map<String, int[]> progress = new java.util.HashMap<>();
    /** 每本字典最近用过的时间（毫秒），同样来自任务队列 */
    private final java.util.Map<String, Long> lastUsed = new java.util.HashMap<>();
    /**
     * 条数缓存：key = 路径 + 文件大小。
     * 没有它的话，每次 refresh（切筛选、勾选、返回页面）都会把每个文件重数一遍 ——
     * 7 MB 目录还好，几百 MB 的字典就是灾难。
     */
    private final java.util.Map<String, Integer> lineCountCache = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        dp = getResources().getDisplayMetrics().density;
        buildUi();
        // 首次启动释放内置字典（后台做，不卡界面）
        new Thread(() -> {
            int migrated = DictStore.migrateFromOldDir();
            if (migrated > 0) {
                ui.post(() -> {
                    Toast.makeText(this, L.s("已从旧目录迁移 ", "Migrated ") + migrated + L.s(" 个字典", " dictionaries from the old folder"),
                            Toast.LENGTH_LONG).show();
                    refresh();
                });
            }
            if (BuiltinDicts.needsRelease(this)) {
                BuiltinDicts.Result r = BuiltinDicts.release(this);
                ui.post(() -> {
                    Toast.makeText(this, L.s("已释放内置字典：", "Built-in dictionaries restored: ") + r.message,
                            Toast.LENGTH_LONG).show();
                    refresh();
                });
            }
        }).start();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回前台重读一次任务队列：刚跑完一本字典回来，徽标要立刻变
        progressLoadedAt = 0L;
        refresh();
    }

    private int px(float v) { return Math.round(v * dp); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ── 标题区 ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(20), px(44), px(20), px(4));

        TextView t = new TextView(this);
        t.setText(L.s("字典管理", "Dictionary management"));
        t.setTextSize(21);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(t);

        // 目录路径：很长，所以中间省略 + 点一下复制（不占视觉重心）
        pathLabel = new TextView(this);
        pathLabel.setTextSize(9.5f);
        pathLabel.setTextColor(AmberTheme.TEXT_FAINT);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pathLabel.setPadding(0, px(2), 0, px(2));
        pathLabel.setOnClickListener(v -> copyPath());
        head.addView(pathLabel);

        statLabel = new TextView(this);
        statLabel.setTextSize(11.5f);
        statLabel.setTextColor(ACCENT);
        head.addView(statLabel);

        /* ── 主/次按钮 ────────────────────────────────────────────
         * 上一版把主入口（新建/导入/在线）藏在右下角一个「+」里，
         * 却把「搜索」做成铺满整行的实心琥珀按钮 —— 视觉权重正好反了。
         * 现在：主操作 = 添加字典（实心），次要 = 搜索（描边）。 */
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, px(10), 0, px(2));
        Button add = AmberTheme.action(this, dp,
                L.s("+  添加字典", "+  Add dictionary"), AmberTheme.BTN_PRIMARY);
        add.setOnClickListener(v -> showAddMenu());
        btnRow.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button search = AmberTheme.action(this, dp,
                L.s("搜索", "Search"), AmberTheme.BTN_SECONDARY);
        search.setOnClickListener(v -> showSearchPicker());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = px(8);
        btnRow.addView(search, slp);
        head.addView(btnRow);
        root.addView(head);

        FrameLayout stage = new FrameLayout(this);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(px(12), px(6), px(12), px(112));
        sv.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 底部多选操作条（只在多选模式出现）──
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setVisibility(View.GONE);
        selectBar.setPadding(px(14), px(10), px(14), px(10));
        selectBar.setBackground(roundBgStroked(AmberTheme.CARD_2,
                AmberTheme.ACCENT_EDGE, AmberTheme.RADIUS_CARD, 1f));

        selectCount = new TextView(this);
        selectCount.setTextSize(13f);
        selectCount.setTextColor(TEXT);
        selectBar.addView(selectCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(miniBtn(L.s("合并", "Merge"), ACCENT, v -> doMerge()));
        selectBar.addView(miniBtn(L.s("删除", "Delete"), AmberTheme.DANGER,
                v -> confirmDeleteSelected()));
        selectBar.addView(miniBtn(L.s("取消", "Cancel"), AmberTheme.TEXT_DIM,
                v -> exitSelectMode()));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        blp.setMargins(px(12), 0, px(12), px(18));
        stage.addView(selectBar, blp);

        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /** 底部操作条上的小按钮（比 chip 更紧凑） */
    private Button miniBtn(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(12), px(8), px(12), px(8));
        b.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 路径太长，点一下复制 —— 比让用户用手指去选更实用 */
    private void copyPath() {
        java.io.File dir = DictStore.defaultDir(this);
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", dir.getAbsolutePath()));
            Toast.makeText(this, L.s("路径已复制", "Path copied"), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 「+ 添加字典」：**只放"新增"这一类动作**。
     *
     * <p>上一版这个按钮打开的是 12 项的大杂烩（新建/导入/下载/搜索/合并/刷新/换目录/清空…），
     * 用户点「添加」看到「清空全部字典」是会害怕的。管理类动作挪到「管理」里。
     */
    private void showAddMenu() {
        NetDialog d = new NetDialog(this, L.s("添加字典", "Add dictionary"), null);
        d.addItem(L.s("新建普通表（手写）", "New plain list (hand-written)"),
                dd -> dialogNewPlain());
        d.addItem(L.s("新建规则（区间批量生成）", "New rule (range-generated)"),
                dd -> dialogNewRule());
        d.addItem(L.s("从手机导入文件", "Import a file from the device"),
                dd -> pickFile());
        d.addItem(L.s("在线字典来源", "Online dictionary sources"),
                dd -> showOnlineSources());
        d.addItem(L.s("插入内置字典", "Restore built-in dictionaries"),
                dd -> releaseBuiltin());
        d.addItem(L.s("── 管理 ──", "── Manage ──"), dd -> showActionSheet());
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    /** 管理类操作（换目录/清空/合并/刷新等） */
    // ---------------- 字典内容搜索 ----------------

    /** 搜索入口：先选一本字典 */
    private void showSearchPicker() {
        final List<DictStore.Entry> all = DictStore.scan(DictStore.defaultDir(this));
        if (all.isEmpty()) {
            Toast.makeText(this, L.s("还没有字典", "no dictionaries yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        NetDialog d = new NetDialog(this, L.s("搜索哪本字典？", "Search which dictionary?"), null);
        for (DictStore.Entry e : all) {
            final DictStore.Entry picked = e;
            d.addItem(e.name + "   (" + e.sizeLabel() + ")", dd -> showSearchDialog(picked));
        }
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    /** 搜索对话框：关键词 + 匹配方式（点一下轮换） */
    private void showSearchDialog(final DictStore.Entry e) {
        final NetDialog dlg = new NetDialog(this, L.s("搜索 ", "Search ") + e.name, null);
        final EditText kw = dlg.addField(L.s("关键词", "Keyword"), "");
        final int[] mode = {io.github.wlmosv_png.wpaforge.audit.DictSearch.MODE_CONTAINS};
        final Button modeBtn = chip(
                L.s("匹配方式：", "Match: ")
                        + io.github.wlmosv_png.wpaforge.audit.DictSearch.modeLabel(mode[0]),
                v -> {
                    mode[0] = (mode[0] + 1) % 4;
                    ((Button) v).setText(L.s("匹配方式：", "Match: ")
                            + io.github.wlmosv_png.wpaforge.audit.DictSearch.modeLabel(mode[0]));
                });
        dlg.addView(modeBtn);
        dlg.addHint(L.s("点上面的按钮切换：包含 / 前缀 / 后缀 / 正则。",
                "Tap the button above to switch: contains / prefix / suffix / regex."));
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.setPositive(L.s("搜索", "Search"), d -> {
            String k = kw.getText().toString().trim();
            if (k.isEmpty()) {
                Toast.makeText(this, L.s("请输入关键词", "enter a keyword"), Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            runSearch(e, k, mode[0]);
        });
        dlg.show();
    }

    /** 执行搜索：读出内容 → 纯逻辑匹配（带条数上限） */
    private void runSearch(final DictStore.Entry e, final String kw, final int mode) {
        new Thread(() -> {
            final List<String> lines = DictStore.readLines(e.file);
            final List<io.github.wlmosv_png.wpaforge.audit.DictSearch.Match> hits =
                    io.github.wlmosv_png.wpaforge.audit.DictSearch.search(
                            lines, kw, mode, io.github.wlmosv_png.wpaforge.audit.DictSearch.DEFAULT_LIMIT);
            final int total = lines.size();
            ui.post(() -> showSearchResult(e, kw, mode, hits, total));
        }, "wpaforge-dict-search").start();
    }

    /** 结果：行号 + 内容；点一条复制，也可只保留命中项生成新字典 */
    private void showSearchResult(final DictStore.Entry e, String kw, int mode,
                                  final List<io.github.wlmosv_png.wpaforge.audit.DictSearch.Match> hits,
                                  int total) {
        String head = L.s(
                "在 " + total + " 条里按「" + io.github.wlmosv_png.wpaforge.audit.DictSearch.modeLabel(mode)
                        + "」匹配「" + kw + "」，找到 " + hits.size() + " 条"
                        + (hits.size() >= io.github.wlmosv_png.wpaforge.audit.DictSearch.DEFAULT_LIMIT
                        ? "（已达显示上限）" : ""),
                total + " entries, " + io.github.wlmosv_png.wpaforge.audit.DictSearch.modeLabel(mode)
                        + " match for \"" + kw + "\": " + hits.size());
        NetDialog d = new NetDialog(this, L.s("搜索结果", "Search results"), head);
        if (hits.isEmpty()) {
            d.setPositive(L.s("知道了", "Got it"), null);
        } else {
            for (final io.github.wlmosv_png.wpaforge.audit.DictSearch.Match m : hits) {
                d.addItem(L.s("第 " + m.lineNo + " 行   ", "#" + m.lineNo + "   ") + m.text, dd -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pw", m.text));
                    }
                    Toast.makeText(this, L.s("已复制该条", "copied"), Toast.LENGTH_SHORT).show();
                });
            }
            d.setNegative(L.s("关闭", "Close"), null);
            d.setPositive(L.s("只保留命中项", "Keep only hits"), dd -> keepOnlyHits(e, kw, mode));
        }
        d.show();
    }

    /** 把命中的行导出成一本新字典（原字典不动） */
    private void keepOnlyHits(final DictStore.Entry e, final String kw, final int mode) {
        new Thread(() -> {
            List<String> lines = DictStore.readLines(e.file);
            List<io.github.wlmosv_png.wpaforge.audit.DictSearch.Match> hits =
                    io.github.wlmosv_png.wpaforge.audit.DictSearch.search(lines, kw, mode, 0);
            List<String> out = new ArrayList<>();
            for (io.github.wlmosv_png.wpaforge.audit.DictSearch.Match m : hits) out.add(m.text);
            if (out.isEmpty()) {
                ui.post(() -> Toast.makeText(this, L.s("没有命中项", "no hits"),
                        Toast.LENGTH_SHORT).show());
                return;
            }
            final File f = new File(DictStore.defaultDir(this), e.name + "-筛选.txt");
            boolean ok = DictStore.save(f, out);
            final int n = out.size();
            ui.post(() -> {
                Toast.makeText(this, ok
                                ? L.s("已生成新字典，共 " + n + " 条", "created a new dictionary: " + n)
                                : L.s("生成失败", "failed"),
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "wpaforge-dict-filter").start();
    }

    private void showActionSheet() {
        NetDialog d = new NetDialog(this, L.s("字典操作", "Dictionary actions"), null);
        d.addItem(L.s("新建字典（空白普通表）", "New dictionary (blank list)"),
                dd -> dialogNewPlain());
        d.addItem(L.s("新建规则（区间批量生成）", "New rule (range-generated)"),
                dd -> dialogNewRule());
        d.addItem(L.s("从手机导入文件", "Import file from device"),
                dd -> pickFile());
        d.addItem(L.s("在线字典来源", "Online dictionary sources"),
                dd -> showOnlineSources());
        d.addItem(L.s("搜索字典内容", "Search inside a dictionary"),
                dd -> showSearchPicker());
        d.addItem(L.s("合并所选（自动去重）", "Merge selected (de-duplicated)"),
                dd -> doMerge());
        d.addItem(L.s("刷新列表", "Refresh list"), dd -> refresh());
        d.addItem(L.s("打开字典目录", "Open dictionary folder"), dd -> openFolder());
        d.addItem(L.s("选择字典目录", "Choose dictionary folder"),
                dd -> pickDictDir());
        d.addItem(L.s("恢复默认目录", "Reset to default folder"),
                dd -> resetDictDir());
        d.addItem(L.s("释放内置字典", "Restore built-in dictionaries"),
                dd -> releaseBuiltin());
        d.addItem(L.s("清空全部字典", "Delete all dictionaries"),
                dd -> confirmClearAll());
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    /** 等分权重布局参数 */
    private LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = px(8);
        return lp;
    }

    /** 两行操作按钮：主标题 + 小字说明 */
    private LinearLayout actionBtn(String title, String hint, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(roundBgStroked(AmberTheme.CARD_2, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
        box.setPadding(px(8), px(14), px(8), px(14));
        box.setClickable(true);
        box.setOnClickListener(l);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.5f);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextSize(10);
        h.setTextColor(TEXT_DIM);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, px(3), 0, 0);
        box.addView(h);

        return box;
    }

    /** 「更多」菜单 */
    private void showMoreMenu() {
        NetDialog d = new NetDialog(this, L.s("更多操作", "More actions"), null);
        d.addItem(L.s("刷新列表", "Refresh list"), dd -> refresh());
        d.addItem(L.s("打开字典目录", "Open dictionary folder"), dd -> openFolder());
        d.addItem(L.s("释放内置字典", "Restore built-in dictionaries"), dd -> releaseBuiltin());
        d.addItem(L.s("清空全部字典", "Delete all dictionaries"), dd -> confirmClearAll());
        d.setNegative(L.s("取消", "Cancel"), null);
        d.show();
    }

    private static final int REQ_PICK_DIR = 2101;

    /**
     * 让用户选一个可写目录存字典。
     *
     * 为什么必需：Android 11+ 的 scoped storage 下，普通应用
     * 建不了 /sdcard/Download 下的新目录，程序会自动退到应用
     * 私有外部目录（Android/data/<pkg>/files/dict/）—— 那个路径
     * **文件管理器里看不到**，用户会以为释放失败了。
     */
    private void pickDictDir() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(i, REQ_PICK_DIR);
        } catch (Throwable t) {
            Toast.makeText(this, L.s("本机不支持选目录",
                    "This device does not support folder picking"), Toast.LENGTH_LONG).show();
        }
    }

    /** 处理用户选中的字典目录 */
    private void handlePickedDir(Intent data) {
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {}
        String path = uriToPath(uri);
        if (path == null) {
            NetDialog.info(this, L.s("无法使用该目录", "Cannot use that folder"),
                    L.s("选中的目录无法转成可写路径。\n\n"
                                    + "建议在手机存储里先建一个文件夹，"
                                    + "再选它。",
                            "That folder cannot be turned into a writable path.\n\n"
                                    + "Create a folder in device storage first, then pick it."));
            return;
        }
        File dir = new File(path, "WPAForge-dict");
        if (!dir.exists() && !dir.mkdirs()) {
            NetDialog.info(this, L.s("无法写入", "Not writable"),
                    L.s("选中的目录写不进去。\n",
                            "The selected folder is not writable.\n") + dir.getAbsolutePath());
            return;
        }
        DictStore.setDir(this, dir);
        refresh();
        Toast.makeText(this, L.s("已切换到: ", "Now using: ") + dir.getAbsolutePath(),
                Toast.LENGTH_LONG).show();
    }

    /** content URI → 真实路径；转不了返回 null */
    private static String uriToPath(Uri uri) {
        try {
            String p = uri.getPath();
            if (p == null) return null;
            int i = p.indexOf(": ");
            if (i >= 0) return p.substring(i + 2);
            final String[] IDS = {"/tree/primary:", "/tree/", "/document/primary:", "/document/"};
            for (String marker : IDS) {
                int j = p.indexOf(marker);
                if (j >= 0) {
                    String tail = p.substring(j + marker.length());
                    if (tail.startsWith("/")) return tail;
                    return "/sdcard/" + tail;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void resetDictDir() {
        DictStore.clearDir(this);
        refresh();
        Toast.makeText(this, L.s("已恢复自动选择目录",
                "Folder selection reset to automatic"), Toast.LENGTH_SHORT).show();
    }

    private void releaseBuiltin() {
        new Thread(() -> {
            BuiltinDicts.Result r = BuiltinDicts.release(this);
            ui.post(() -> {
                NetDialog.info(this, L.s("释放内置字典", "Restore built-in dictionaries"), r.message);
                refresh();
            });
        }).start();
    }

    private void confirmClearAll() {
        NetDialog d = new NetDialog(this, L.s("清空全部字典", "Delete all dictionaries"),
                L.s("将删除字典目录里的所有 txt 文件。此操作不可恢复。", "This deletes every txt file in the dictionary folder. It cannot be undone."));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.setDanger(L.s("全部删除", "Delete all"), dd -> {
            java.util.List<DictStore.Entry> list = DictStore.scan(DictStore.defaultDir(this));
            int n = 0;
            for (DictStore.Entry e : list) if (e.file.delete()) n++;
            Toast.makeText(this, L.s("删除了 ", "Deleted ") + n + " 个文件", Toast.LENGTH_SHORT).show();
            refresh();
        });
        d.show();
    }

    private Button chip(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        b.setTextColor(AmberTheme.ON_ACCENT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(16), px(10), px(16), px(10));
        b.setBackground(roundBg(ACCENT, AmberTheme.RADIUS_BTN));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(10);
        b.setLayoutParams(lp);
        io.github.wlmosv_png.wpaforge.ui.UiKit.enableTapHaptic(b);
        return b;
    }

    // ---------------- 列表 ----------------

    private void refresh() {
        File dir = DictStore.defaultDir(this);
        pathLabel.setText(dir.getAbsolutePath());
        listBox.removeAllViews();

        List<DictStore.Entry> entries = DictStore.scan(dir);
        pruneSelection(entries);
        loadProgressIfStale();
        loadMissingLineCounts(entries);
        sortEntries(entries);

        List<DictStore.Entry> shown = new ArrayList<>();
        for (DictStore.Entry e : entries) {
            boolean tried = progress.containsKey(e.file.getAbsolutePath());
            if (filter == Filter.TRIED && !tried) continue;
            if (filter == Filter.UNTRIED && tried) continue;
            shown.add(e);
        }

        long totalBytes = 0;
        int triedN = 0;
        for (DictStore.Entry e : entries) {
            totalBytes += e.sizeBytes;
            if (progress.containsKey(e.file.getAbsolutePath())) triedN++;
        }
        statLabel.setText(selectMode
                ? L.s("已选 " + selected.size() + " 本", selected.size() + " selected")
                : L.s("共 " + entries.size() + " 个字典 · " + (totalBytes / 1024 / 1024)
                                + " MB · 跑过 " + triedN + " 本",
                        entries.size() + " dictionaries · " + (totalBytes / 1024 / 1024)
                                + " MB · " + triedN + " tried"));

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(L.s("目录里还没有字典。\n\n点上面的「+ 添加字典」：\n"
                            + "· 新建普通表，手写几条\n"
                            + "· 新建规则，按区间批量生成（如 00000000→99999999）\n"
                            + "· 从手机里导入 txt\n"
                            + "· 用在线来源下载",
                    "No dictionaries yet.\n\nTap \"+ Add dictionary\" above to create, "
                            + "import or download one."));
            empty.setTextSize(13);
            empty.setTextColor(AmberTheme.TEXT_DIM);
            empty.setLineSpacing(px(5), 1f);
            empty.setBackground(roundBgStroked(AmberTheme.CARD, AmberTheme.EDGE,
                    AmberTheme.RADIUS_CARD, 0.8f));
            empty.setPadding(px(18), px(20), px(18), px(20));
            listBox.addView(empty);
        } else {
            listBox.addView(filterRow(entries.size(), triedN));
            View tl = targetLine();
            if (tl != null) listBox.addView(tl);
            if (shown.isEmpty()) {
                TextView none = new TextView(this);
                none.setText(filter == Filter.TRIED
                        ? L.s("这本网络还没有跑过任何字典。", "Nothing has been tried on this network yet.")
                        : L.s("没有未跑过的字典 —— 都被跑过了。", "No untried dictionaries left."));
                none.setTextSize(12.5f);
                none.setTextColor(AmberTheme.TEXT_DIM);
                none.setPadding(px(4), px(16), px(4), px(16));
                listBox.addView(none);
            } else {
                for (DictStore.Entry e : shown) listBox.addView(row(e));
            }
        }
        updateSelectBar();
    }

    /** 选择项随列表刷新清理（已删除的字典不该留在选择里） */
    private void pruneSelection(List<DictStore.Entry> entries) {
        List<DictStore.Entry> keep = new ArrayList<>();
        for (DictStore.Entry sel : selected) {
            for (DictStore.Entry cur : entries) {
                if (cur.file.equals(sel.file)) { keep.add(cur); break; }
            }
        }
        selected.clear();
        selected.addAll(keep);
    }

    // ---------------------------------------------------------------- 筛选 / 排序

    private void sortEntries(List<DictStore.Entry> entries) {
        switch (sortBy) {
            case NAME:
                entries.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
                break;
            case RECENT:
                entries.sort((x, y) -> Long.compare(
                        lastUsed.getOrDefault(y.file.getAbsolutePath(), 0L),
                        lastUsed.getOrDefault(x.file.getAbsolutePath(), 0L)));
                break;
            case SIZE:
            default:
                entries.sort((x, y) -> Long.compare(y.sizeBytes, x.sizeBytes));
                break;
        }
    }

    private View filterRow(int total, int triedN) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, px(2), 0, px(8));

        row.addView(filterChip(L.s("全部 " + total, "All " + total), Filter.ALL),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(filterChip(L.s("跑过 " + triedN, "Tried " + triedN), Filter.TRIED),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(filterChip(L.s("未跑 " + (total - triedN), "Untried " + (total - triedN)),
                Filter.UNTRIED), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sp = new TextView(this);
        sp.setText(L.s("排序", "Sort"));
        sp.setTextSize(11f);
        sp.setTextColor(AmberTheme.TEXT_FAINT);
        sp.setPadding(px(6), 0, px(6), 0);
        row.addView(sp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(sortChip(L.s("大小", "Size"), SortBy.SIZE));
        row.addView(sortChip(L.s("名称", "Name"), SortBy.NAME));
        row.addView(sortChip(L.s("最近", "Recent"), SortBy.RECENT));
        return row;
    }

    private Button filterChip(String label, Filter f) {
        boolean on = filter == f;
        Button b = miniChip(label, on ? AmberTheme.ON_ACCENT : AmberTheme.TEXT_DIM,
                on ? ACCENT : AmberTheme.CARD_3);
        b.setOnClickListener(v -> {
            filter = f;
            refresh();
        });
        return b;
    }

    private Button sortChip(String label, SortBy sb) {
        boolean on = sortBy == sb;
        Button b = miniChip(label, on ? AmberTheme.ON_ACCENT : AmberTheme.TEXT_DIM,
                on ? ACCENT : AmberTheme.CARD_3);
        b.setOnClickListener(v -> {
            sortBy = sb;
            refresh();
        });
        return b;
    }

    private Button miniChip(String label, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11f);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(11), px(7), px(11), px(7));
        b.setBackground(roundBg(bgColor, AmberTheme.RADIUS_CHIP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 当前目标那行小字（有记录时才出现） */
    private View targetLine() {
        String key = Prefs.targetKey(this);
        if (key == null || key.isEmpty()) return null;
        io.github.wlmosv_png.wpaforge.audit.TriedSet tried =
                io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                        io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key));
        if (tried.size() == 0) return null;
        TextView tv = new TextView(this);
        tv.setText(L.s("本网络已试过 " + tried.size() + " 条 · 跑字典时自动跳过 · 点这里看判定基准",
                tried.size() + " tried on this network \u00b7 skipped automatically "
                        + "\u00b7 tap for how it works"));
        tv.setTextSize(11f);
        tv.setTextColor(AmberTheme.OK);
        tv.setPadding(px(2), px(4), px(2), px(12));
        // 可点：把"过滤基准"讲清楚。
        // 用户的原话是"字典太大我没法测，我就想知道过滤的基准"——
        // 与其让人猜，不如把规则连同本机真实数字一起摆在页面上。
        tv.setOnClickListener(v -> showFilterBasis());
        return tv;
    }

    /**
     * 「过滤基准」说明。
     *
     * <p>为什么做成页面内可查：用户没法拿几百 MB 的字典做实验来验证"会不会重复跑"，
     * 所以把**判据、记账时机、会重复的三种情况、上限**连同**本机真实数字**一起写出来，
     * 他能自己对账（条数 = 记录文件字节数 ÷ 8）。
     */
    private void showFilterBasis() {
        String key = Prefs.targetKey(this);
        if (key == null || key.isEmpty()) return;
        io.github.wlmosv_png.wpaforge.audit.TriedSet tried =
                io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                        io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key));
        java.io.File f = io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key);
        String when = f.isFile()
                ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                        .format(new java.util.Date(f.lastModified()))
                : L.s("还没写过", "not written yet");

        StringBuilder sb = new StringBuilder();
        sb.append(L.s("本网络已试过 ", "Tried on this network: ")).append(tried.size())
                .append(L.s(" 条\n记录文件 ", "\nRecord file: "))
                .append(f.isFile() ? (f.length() + " B") : "—")
                .append(L.s("（每条 8 字节，条数 = 字节数 ÷ 8）\n最近写入 ",
                        " (8 bytes each; count = bytes / 8)\nlast write: "))
                .append(when).append("\n\n");

        sb.append(L.s("▸ 判据是什么\n候选口令本身的 64 位哈希。不看字典文件、不看第几行、不看顺序。\n"
                        + "所以换任何字典、任何顺序，都不会重复跑已经试过的。\n\n",
                "▸ What counts as \"tried\"\nThe 64-bit hash of the candidate itself. "
                        + "Not the file, not the line number, not the order.\n\n"));
        sb.append(L.s("▸ 什么时候记账\n每条候选跑完、并且拿到**明确结论**（密码错误 / 命中）时才记。\n"
                        + "超时、无结论的不记 —— 那些下次会重新试，因为它们还没被真正排除。\n\n",
                "▸ When it is recorded\nAfter a candidate finishes with a **definite verdict** "
                        + "(wrong password / hit). Timeouts and inconclusive ones are NOT "
                        + "recorded.\n\n"));
        sb.append(L.s("▸ 唯一会重复的三种情况\n"
                        + "· 强杀进程：最后最多 32 条（约 2 分钟）的记账丢失\n"
                        + "· 没结论的候选：故意不记，下次再试\n"
                        + "· 换到别的网络：那是另一套记录（故意不共享 —— 同一个密码在别的网\n"
                        + "  失败不代表这个网也失败）\n\n",
                "▸ The only cases that repeat\n"
                        + "· Process killed: up to the last 32 recordings (~2 min) are lost\n"
                        + "· Inconclusive candidates: deliberately not recorded\n"
                        + "· Another network: separate record (never shared)\n\n"));
        sb.append(L.s("▸ 上限\n每个网络 100 万条（8 MB）。到顶会在日志里提示，不会静默丢弃。",
                "▸ Limit\n1,000,000 entries per network (8 MB); you get a notice when reached."));

        NetDialog d = new NetDialog(this, L.s("过滤基准", "How filtering works"), sb.toString());
        d.setPositive(L.s("知道了", "Got it"), null);
        d.show();
    }

    // ---------------------------------------------------------------- 进度（来自任务队列）

    private long progressLoadedAt = 0L;

    /** 读任务队列（进页面、回前台、跑完回来时读一次就够，不必每次 refresh 都读） */
    private void loadProgressIfStale() {
        if (System.currentTimeMillis() - progressLoadedAt < 3000L) return;
        loadProgressAsync();
    }

    private void loadProgressAsync() {
        progressLoadedAt = System.currentTimeMillis();
        new Thread(() -> {
            java.util.List<io.github.wlmosv_png.wpaforge.audit.TaskQueue.Task> q =
                    io.github.wlmosv_png.wpaforge.audit.TaskQueue.load(DictActivity.this);
            final java.util.Map<String, int[]> p = new java.util.HashMap<>();
            final java.util.Map<String, Long> lu = new java.util.HashMap<>();
            for (io.github.wlmosv_png.wpaforge.audit.TaskQueue.Task t : q) {
                if (t.dictPath == null || t.dictPath.isEmpty()) continue;
                int[] cur = p.get(t.dictPath);
                if (cur == null) {
                    cur = new int[]{0, 0};
                    p.put(t.dictPath, cur);
                }
                boolean done = t.state == io.github.wlmosv_png.wpaforge.audit.TaskQueue.State.DONE_HIT
                        || t.state == io.github.wlmosv_png.wpaforge.audit.TaskQueue.State.DONE_MISS;
                cur[0] = Math.max(cur[0], done ? t.dictSize : t.offset);
                cur[1] = Math.max(cur[1], t.dictSize);
                long when = t.createdAt + Math.max(0, t.elapsedMs);
                Long prev = lu.get(t.dictPath);
                if (prev == null || when > prev) lu.put(t.dictPath, when);
            }
            ui.post(() -> {
                progress.clear();
                progress.putAll(p);
                lastUsed.clear();
                lastUsed.putAll(lu);
                refresh();
            });
        }, "wpaforge-dict-progress").start();
    }

    /** 先用缓存填上条数，只把没数过的交给后台（这一列直接决定"要跑多久"） */
    private void loadMissingLineCounts(final List<DictStore.Entry> entries) {
        final List<DictStore.Entry> todo = new ArrayList<>();
        for (DictStore.Entry e : entries) {
            if (e.isRule) continue;
            Integer c = lineCountCache.get(cacheKey(e));
            if (c != null) {
                e.lineCount = c;
            } else if (e.lineCount <= 0) {
                todo.add(e);
            }
        }
        if (todo.isEmpty()) return;
        new Thread(() -> {
            boolean changed = false;
            for (DictStore.Entry e : todo) {
                int n = DictStore.countLines(e.file);
                if (n > 0) {
                    lineCountCache.put(cacheKey(e), n);
                    e.lineCount = n;
                    changed = true;
                }
            }
            if (changed) ui.post(this::refresh);
        }, "wpaforge-dict-count").start();
    }

    private static String cacheKey(DictStore.Entry e) {
        return e.file.getAbsolutePath() + "@" + e.sizeBytes;
    }

    private static String fmtCount(int n) {
        if (n >= 10000) return L.s((n / 10000) + " 万", (n / 1000) + "k");
        return String.valueOf(n);
    }

    /** 副行：大小 · 条数 · 上次使用（文件名不重复写，标题就是它） */
    private String metaLine(DictStore.Entry e) {
        StringBuilder sb = new StringBuilder(e.sizeLabel());
        if (e.isRule) {
            sb.append(L.s(" · 区间规则", " \u00b7 range rule"));
        } else if (e.lineCount > 0) {
            sb.append(" · ").append(e.lineCount >= 10000
                            ? fmtCount(e.lineCount) + L.s(" 条", " entries")
                            : e.lineCount + L.s(" 条", " entries"));
        }
        Long when = lastUsed.get(e.file.getAbsolutePath());
        if (when != null && when > 0) {
            sb.append(L.s(" · 上次 ", " \u00b7 last "))
                    .append(new java.text.SimpleDateFormat("MM-dd", java.util.Locale.ROOT)
                            .format(new java.util.Date(when)));
        }
        return sb.toString();
    }

    /** 状态徽标：这一本跑了没跑、跑了多少 —— 本 App 最该一眼看到的信息 */
    private TextView statusBadge(DictStore.Entry e) {
        int[] p = progress.get(e.file.getAbsolutePath());
        TextView tv = new TextView(this);
        tv.setTextSize(10.5f);
        tv.setPadding(px(2), 0, px(2), 0);
        if (p == null) {
            tv.setText(L.s("没跑过", "new"));
            tv.setTextColor(AmberTheme.TEXT_FAINT);
        } else if (p[1] > 0 && p[0] >= p[1]) {
            tv.setText(L.s("✓ 跑过", "✓ done"));
            tv.setTextColor(AmberTheme.OK);
        } else {
            tv.setText(L.s("◐ " + fmtCount(p[0]) + "/" + fmtCount(p[1]),
                    "◐ " + p[0] + "/" + p[1]));
            tv.setTextColor(ACCENT);
        }
        return tv;
    }

    // ---------------------------------------------------------------- 行

    private View row(DictStore.Entry e) {
        final boolean sel = selected.contains(e);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // 行高约 62dp：一屏 9 本左右（上一版 130dp，一屏 4 本）
        box.setBackground(sel ? roundBg(AmberTheme.ACCENT_BG, 14)
                : roundBg(AmberTheme.SUNKEN, 14));
        box.setPadding(px(12), px(10), px(12), px(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(6);
        box.setLayoutParams(lp);

        ImageView pick = new ImageView(this);
        pick.setImageDrawable(DictIcons.sized(
                sel ? DictIcons.Kind.PICKED : DictIcons.Kind.PICK,
                sel ? ACCENT : AmberTheme.TEXT_DIM, dp, 22f));
        pick.setVisibility((selectMode || sel) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(24), px(24));
        plp.rightMargin = px(10);
        box.addView(pick, plp);
        pick.setOnClickListener(v -> {
            toggleSelect(e);
            refresh();
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextSize(14.5f);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView meta = new TextView(this);
        meta.setText(metaLine(e));
        meta.setTextSize(10.5f);
        meta.setTextColor(AmberTheme.TEXT_FAINT);
        meta.setSingleLine(true);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = px(2);
        col.addView(name);
        col.addView(meta, mlp);
        box.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        box.addView(statusBadge(e));

        ImageView more = new ImageView(this);
        more.setImageDrawable(DictIcons.sized(DictIcons.Kind.MORE, AmberTheme.TEXT_DIM, dp, 18f));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(px(34), px(34));
        xlp.leftMargin = px(4);
        more.setLayoutParams(xlp);
        more.setPadding(px(8), px(8), px(8), px(8));
        box.addView(more);
        more.setOnClickListener(v -> showRowMenu(e));

        // 点整行 = 看内容（最高频动作）；长按 = 进多选，**给一次轻振动**
        box.setOnClickListener(v -> {
            if (selectMode) {
                toggleSelect(e);
                refresh();
            } else {
                viewDict(e);
            }
        });
        box.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            enterSelectMode(e);
            return true;
        });
        return box;
    }

    // ---------------------------------------------------------------- 多选模式

    private void enterSelectMode(DictStore.Entry e) {
        selectMode = true;
        if (!selected.contains(e)) selected.add(e);
        refresh();
    }

    private void exitSelectMode() {
        selectMode = false;
        selected.clear();
        refresh();
    }

    private void updateSelectBar() {
        if (selectBar == null) return;
        selectBar.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        if (selectMode && selectCount != null) {
            selectCount.setText(L.s("已选 " + selected.size() + " 本", selected.size() + " selected"));
        }
    }

    private void confirmDeleteSelected() {
        if (selected.isEmpty()) return;
        final List<DictStore.Entry> victims = new ArrayList<>(selected);
        NetDialog d = new NetDialog(this, L.s("删除选中的字典", "Delete selected dictionaries"),
                L.s("将删除 " + victims.size() + " 本，此操作不可恢复。",
                        victims.size() + " file(s) will be deleted. This cannot be undone."));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.setDanger(L.s("删除", "Delete"), dd -> {
            int ok = 0;
            for (DictStore.Entry e : victims) if (e.file.delete()) ok++;
            selected.clear();
            selectMode = false;
            Toast.makeText(this, L.s("已删除 " + ok + " 本", "Deleted " + ok),
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
        d.show();
    }

    @Override
    public void onBackPressed() {
        if (selectMode) {
            exitSelectMode();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 行内「⋯」菜单

    private void showRowMenu(DictStore.Entry e) {
        NetDialog d = new NetDialog(this, e.name, metaLine(e));
        d.addItem(L.s("查看内容", "View contents"), dd -> viewDict(e));
        d.addItem(L.s("编辑", "Edit"), dd -> editDict(e));
        d.addItem(L.s("统计", "Stats"), dd -> showStats(e));
        d.addItem(L.s("重命名", "Rename"), dd -> renameDict(e));
        d.addItem(L.s("复制一份", "Duplicate"), dd -> duplicateDict(e));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.setDanger(L.s("删除", "Delete"), dd -> confirmDelete(e));
        d.show();
    }

    private void renameDict(DictStore.Entry e) {
        NetDialog d = new NetDialog(this, L.s("重命名", "Rename"), e.file.getName());
        final EditText et = d.addField(L.s("新名称", "New name"), e.name);
        d.setNegative(L.s("取消", "Cancel"), null);
        d.setPositive(L.s("保存", "Save"), dd -> {
            String nn = et.getText().toString().trim().replaceAll("[/\\:*?\"<>|]", "_");
            if (nn.isEmpty()) return;
            File to = new File(e.file.getParentFile(), nn + ".txt");
            if (to.exists()) {
                Toast.makeText(this, L.s("同名已存在", "Name already exists"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (e.file.renameTo(to)) {
                Toast.makeText(this, L.s("已重命名", "Renamed"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, L.s("重命名失败", "Rename failed"), Toast.LENGTH_SHORT).show();
            }
            refresh();
        });
        d.show();
    }

    private void duplicateDict(DictStore.Entry e) {
        new Thread(() -> {
            File to = new File(e.file.getParentFile(), e.name + L.s(" 副本", " copy") + ".txt");
            int i = 2;
            while (to.exists()) {
                to = new File(e.file.getParentFile(),
                        e.name + L.s(" 副本", " copy") + i + ".txt");
                i++;
            }
            boolean ok = false;
            try (java.io.FileInputStream in = new java.io.FileInputStream(e.file);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ok = true;
            } catch (Exception ignored) {}
            final boolean done = ok;
            ui.post(() -> {
                Toast.makeText(this, done ? L.s("已复制", "Duplicated")
                        : L.s("复制失败", "Copy failed"), Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "wpaforge-dict-dup").start();
    }

    private void attachLongPress(View v, View.OnLongClickListener l) {
        if (v == null) return;
        v.setOnLongClickListener(l);
        v.setLongClickable(true);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                attachLongPress(g.getChildAt(i), l);
            }
        }
    }

    /** 切换某本字典的选中状态（长按或多选态下点击整行触发） */
    private void toggleSelect(DictStore.Entry e) {
        boolean removed = false;
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).file.equals(e.file)) {
                selected.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) selected.add(e);
    }

    private Button smallBtn(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(AmberTheme.CARD_3, AmberTheme.RADIUS_CHIP));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        // 内边距加大：原来纵向只有 7dp，行内按钮的可点区域偏小、不好按
        b.setPadding(px(14), px(10), px(14), px(10));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(7);
        b.setLayoutParams(lp);
        io.github.wlmosv_png.wpaforge.ui.UiKit.enableTapHaptic(b);
        return b;
    }

    /** 圆角背景 */
    /** 圆角背景 —— 走 UiKit：可点元素自带水波纹与禁用态压暗 */
    private android.graphics.drawable.Drawable roundBg(int color, float radiusDp) {
        return io.github.wlmosv_png.wpaforge.ui.UiKit.ripple(color, px(radiusDp));
    }

    /** 同 {@link #roundBg}，外加一条极淡描边 —— 近黑底上「卡片/行」的分组靠它立住。 */
    private android.graphics.drawable.Drawable roundBgStroked(int fill, int edge,
                                                             float radiusDp, float strokeDp) {
        return io.github.wlmosv_png.wpaforge.ui.UiKit.roundStroked(
                fill, edge, px(radiusDp), Math.max(1f, px(strokeDp)));
    }

    // ---------------- 查看 ----------------

    private void viewDict(DictStore.Entry e) {
        if (e.isRule) {
            List<String> lines = DictStore.readLines(e.file);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            DictRule r = DictRule.parse(sb.toString());
            showDialog(L.s("规则字典: ", "Rule dictionary: ") + e.name,
                    r.serialize() + "\n展开后约 " + DictRule.formatCount(r.estimateCount())
                    + " 条\n\n首几条示例:\n" + previewOf(r, 10));
            return;
        }

        // 普通字典：先给一段，底部可以「再显示更多」直到全部看完。
        //
        // 旧实现只显示前 50 条 —— 用户的原话是「有的用户就想看全部不行嘛」。
        // 一次性把 16 万条塞进一个 TextView 会卡死，所以按段展开。
        new Thread(() -> {
            final List<String> lines = DictStore.readLines(e.file);
            ui.post(() -> showContentView(e, lines, VIEW_STEP));
        }, "wpaforge-dict-view").start();
    }

    /** 每段显示多少条 */
    private static final int VIEW_STEP = 500;

    /** 字典内容分段展示：带行号、可继续展开到全部 */
    private void showContentView(final DictStore.Entry e, final List<String> lines, final int want) {
        final int n = Math.min(want, lines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i + 1).append(".  ").append(lines.get(i)).append('\n');
        }
        final NetDialog d = new NetDialog(this, L.s("字典内容：", "Contents: ") + e.name,
                L.s("共 " + lines.size() + " 条，已显示 " + n + " 条",
                    lines.size() + " total, " + n + " shown"));

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11.5f);
        tv.setTextColor(TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(px(3), 1f);
        tv.setTextIsSelectable(true);
        tv.setBackground(roundBg(SURFACE, AmberTheme.RADIUS_CHIP));
        tv.setPadding(px(12), px(12), px(12), px(12));
        d.addView(tv);

        if (n < lines.size()) {
            d.addItem(L.s("再显示 " + VIEW_STEP + " 条", "Show " + VIEW_STEP + " more"), dd -> {
                d.dismiss();
                showContentView(e, lines, n + VIEW_STEP);
            });
            d.addItem(L.s("全部展开（可能较慢）", "Show all (may be slow)"), dd -> {
                d.dismiss();
                showContentView(e, lines, lines.size());
            });
        }
        d.setNegative(L.s("关闭", "Close"), null);
        d.show();
    }

    private String previewOf(DictRule r, int n) {
        List<Candidate> list = r.expand(n);
        StringBuilder sb = new StringBuilder();
        for (Candidate c : list) sb.append(c.password).append('\n');
        return sb.toString();
    }

    // ---------------- 编辑 ----------------

    private void editDict(DictStore.Entry e) {
        new Thread(() -> {
            final List<String> lines = DictStore.readLines(e.file);
            ui.post(() -> showEditDialog(e, lines));
        }, "wpaforge-dict-edit").start();
    }

    /** 小文件直接给全貌可编辑；超过这个行数只提供「插入」，避免把大文件塞进输入框 */
    private static final int EDIT_INLINE_LIMIT = 3000;

    /**
     * 编辑字典。
     *
     * <p><b>给全貌</b>：小文件（≤ {@value #EDIT_INLINE_LIMIT} 条）直接把**全部内容**
     * 放进可编辑框，用户想看哪就想看哪、想改哪就改哪 —— 这才是「编辑」。
     *
     * <p><b>给位置</b>：大文件不塞进输入框（16 万行会卡死），改为「插入到第 N 行」，
     * 留空就是追加到末尾。原有内容一个字不动。
     *
     * <p>唯一会丢数据的动作是「替换全部…」，单独按钮 + 二次确认。
     */
    private void showEditDialog(final DictStore.Entry e, final List<String> lines) {
        final boolean fullEditable = lines.size() <= EDIT_INLINE_LIMIT;
        final NetDialog dlg = new NetDialog(this, L.s("编辑 ", "Edit ") + e.name, null);

        final TextView countTv = new TextView(this);
        countTv.setTextSize(11.5f);
        countTv.setTextColor(TEXT_DIM);
        countTv.setText(L.s("原有 " + lines.size() + " 条",
                lines.size() + " existing entries"));
        dlg.addView(countTv);

        final EditText area = dlg.addTextArea(
                fullEditable
                        ? L.s("全部内容（可直接修改）", "Full contents (editable)")
                        : L.s("要新增的口令（每行一个）", "Passphrases to ADD (one per line)"),
                "", fullEditable ? 10 : 8);

        final EditText atLine;
        if (fullEditable) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            area.setText(sb.toString());
            atLine = null;
            dlg.addHint(L.s("上面就是文件的全部内容。保存会整份写回。",
                    "That is the entire file. Saving writes it back as a whole."));
            dlg.setNegative(L.s("取消", "Cancel"), null);
            dlg.setPositive(L.s("保存", "Save"), d -> {
                final List<String> out = splitClean(area.getText().toString());
                dlg.dismiss();
                new Thread(() -> {
                    boolean ok = DictStore.save(e.file, out);
                    final int n = out.size();
                    ui.post(() -> {
                        Toast.makeText(this, ok ? L.s("已保存 " + n + " 条", "saved " + n)
                                        : L.s("保存失败", "Save failed"),
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                }).start();
            });
        } else {
            atLine = dlg.addField(
                    L.s("插入到第几行（留空 = 末尾）", "Insert at line (blank = end)"), "");
            dlg.addHint(L.s(
                    "文件较大（" + lines.size() + " 条），这里只做插入，原有内容不会被改动。"
                            + "要看全貌用「查看」，要定位用「搜索」。",
                    "Large file (" + lines.size() + " entries) - insert only. "
                            + "Use View for the whole file, Search to locate an entry."));
            dlg.setNegative(L.s("取消", "Cancel"), null);
            dlg.setPositive(L.s("插入", "Insert"), d -> {
                final List<String> add = splitClean(area.getText().toString());
                if (add.isEmpty()) {
                    Toast.makeText(this, L.s("没有新增内容", "nothing to add"),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                int at = lines.size();
                String raw = atLine.getText().toString().trim();
                if (!raw.isEmpty()) {
                    try {
                        at = Math.max(0, Math.min(Integer.parseInt(raw) - 1, lines.size()));
                    } catch (Throwable ignored) {
                        Toast.makeText(this, L.s("行号要填数字", "line must be a number"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                final int pos = at;
                dlg.dismiss();
                new Thread(() -> {
                    List<String> all = new ArrayList<>(lines);
                    all.addAll(pos, add);
                    boolean ok = DictStore.save(e.file, all);
                    final int total = all.size();
                    final int added = add.size();
                    ui.post(() -> {
                        Toast.makeText(this, ok
                                        ? L.s("已插入 " + added + " 条到第 " + (pos + 1) + " 行，共 " + total + " 条",
                                              "inserted " + added + " at line " + (pos + 1) + ", " + total + " total")
                                        : L.s("保存失败", "Save failed"),
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    });
                }).start();
            });
        }

        dlg.setDanger(L.s("替换全部…", "Replace all…"), d -> confirmReplaceAll(e, area));
        dlg.show();
    }

    /** 把文本切成干净的口令列表（去空行、去注释、去首尾空白） */
    private static List<String> splitClean(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /**
     * 整份替换：把输入框内容当作**全部**内容。
     *
     * 这是唯一会丢数据的动作，所以单独一个按钮 + 二次确认，并且把
     * 「N 条 → M 条」写清楚。默认的编辑路径（追加）永远不会走到这里。
     */
    private void confirmReplaceAll(DictStore.Entry e, EditText area) {
        final List<String> fresh = splitClean(area.getText().toString());
        new Thread(() -> {
            final int old = DictStore.readLines(e.file).size();
            ui.post(() -> {
                NetDialog c = new NetDialog(this, L.s("替换全部？", "Replace everything?"),
                        L.s("将把 " + old + " 条整份替换为 " + fresh.size() + " 条，原内容不可恢复。",
                            "Replace all " + old + " entries with " + fresh.size()
                                    + " - this cannot be undone."));
                c.setNegative(L.s("取消", "Cancel"), null);
                c.setDanger(L.s("确认替换", "Replace"), d -> {
                    c.dismiss();
                    if (fresh.isEmpty()) {
                        Toast.makeText(this, L.s("内容为空，已取消", "empty - cancelled"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        boolean ok = DictStore.save(e.file, fresh);
                        final int n = fresh.size();
                        ui.post(() -> {
                            Toast.makeText(this, ok ? L.s("已替换为 " + n + " 条",
                                            "replaced with " + n)
                                            : L.s("保存失败", "Save failed"),
                                    Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    }).start();
                });
                c.show();
            });
        }).start();
    }

    // ---------------- 新建 ----------------

    private void dialogNewPlain() {
        NetDialog dlg = new NetDialog(this, L.s("新建普通字典", "New plain dictionary"), L.s("每行一个密码。", "One passphrase per line."));
        EditText nameEt = dlg.addField(L.s("字典名称", "Dictionary name"), L.s("我的弱口令", "My weak passphrases"));
        EditText bodyEt = dlg.addTextArea("每行一个密码", "", 7);
        dlg.addHint(L.s("长度不足 8 位的会被自动忽略（WPA 口令至少 8 位）。", "Entries shorter than 8 characters are skipped (WPA requires at least 8)."));
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.setPositive(L.s("创建", "Create"), d -> {
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) name = "新字典";
            String body = bodyEt.getText().toString();
            File f = new File(DictStore.defaultDir(this), name + ".txt");
            final String fn = name;
            new Thread(() -> {
                boolean ok = DictStore.save(f, splitLines(body));
                ui.post(() -> {
                    Toast.makeText(this, ok ? L.s("已创建 ", "Created ") + fn : "创建失败",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }).start();
        });
        dlg.show();
    }

    private void dialogNewRule() {
        NetDialog dlg = new NetDialog(this, L.s("新建规则字典", "New rule dictionary"),
                L.s("用区间描述海量口令，不占空间。", "Describe huge sets with a range — takes no space."));
        EditText nameEt   = dlg.addField(L.s("名称", "Name"), "8位纯数字");
        EditText prefixEt = dlg.addField(L.s("前缀（可空）", "Prefix (optional)"), "");
        EditText suffixEt = dlg.addField(L.s("后缀（可空）", "Suffix (optional)"), "");
        EditText startEt  = dlg.addField(L.s("起始", "Start"), "00000000");
        EditText endEt    = dlg.addField(L.s("末尾", "End"), "99999999");
        EditText stepEt   = dlg.addField(L.s("步长（默认 1）", "Step (default 1)"), "1");

        TextView est = new TextView(this);
        est.setTextSize(11.5f);
        est.setTextColor(ACCENT);
        est.setPadding(px(4), px(8), px(4), px(4));
        dlg.addView(est);

        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                try {
                    long st = Long.parseLong(startEt.getText().toString().trim());
                    long en = Long.parseLong(endEt.getText().toString().trim());
                    long sp = 1;
                    try { sp = Long.parseLong(stepEt.getText().toString().trim()); }
                    catch (Exception ignored) {}
                    if (sp <= 0) sp = 1;
                    if (en < st) { est.setText(L.s("起始大于末尾，不会生成任何口令", "Start is greater than end — nothing will be generated")); return; }
                    long n = (en - st) / sp + 1;
                    long sec = n * 7;
                    String time = sec < 3600 ? (sec / 60) + " 分钟"
                            : sec < 86400 ? (sec / 3600) + " 小时"
                            : (sec / 86400) + " 天";
                    est.setText(L.f("约 %s 条，", "about %s entries, ", DictRule.formatCount(n))
                            + "按 7 秒/条需 " + time);
                } catch (Exception ex) {
                    est.setText(L.s("填入纯数字的起始与末尾后会显示预估条数", "Fill in numeric start and end to see the estimated count"));
                }
            }
        };
        startEt.addTextChangedListener(watcher);
        endEt.addTextChangedListener(watcher);
        stepEt.addTextChangedListener(watcher);
        watcher.afterTextChanged(null);

        dlg.addHint(L.s("手机逐条连接最快约 7 秒/条，条数不是越多越好。", "Sequential attempts take at least ~7s each on a phone — more is not better."));
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.setPositive(L.s("创建", "Create"), dd -> {
            DictRule r = new DictRule();
            r.prefix = prefixEt.getText().toString().trim();
            r.suffix = suffixEt.getText().toString().trim();
            r.start = startEt.getText().toString().trim();
            r.end = endEt.getText().toString().trim();
            try { r.step = Integer.parseInt(stepEt.getText().toString().trim()); }
            catch (Exception ignored) { r.step = 1; }
            if (!r.isValid()) {
                Toast.makeText(this, L.s("起始和末尾必须填", "Start and end are required"), Toast.LENGTH_SHORT).show();
                return;
            }
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) name = "新规则";
            File f = new File(DictStore.defaultDir(this), name + ".txt");
            final DictRule rule = r;
            final String fn = name;
            new Thread(() -> {
                boolean ok = DictStore.saveRule(f, rule);
                final long n = rule.estimateCount();
                ui.post(() -> {
                    Toast.makeText(this, ok
                            ? L.s("已创建 ", "Created ") + fn + "（约 " + DictRule.formatCount(n) + L.s(" 条）", " entries)")
                            : L.s("创建失败", "Create failed"), Toast.LENGTH_LONG).show();
                    refresh();
                });
            }).start();
        });
        dlg.show();
    }

    private List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    // ---------------- 导入 ----------------

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器: " + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;

        // 选目录（字典存放位置）
        if (req == REQ_PICK_DIR) {
            handlePickedDir(data);
            return;
        }

        if (req != REQ_PICK) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        new Thread(() -> {
            int ok = 0;
            StringBuilder report = new StringBuilder();
            for (Uri u : uris) {
                try {
                    // 关键：保留原文件名，别用时间戳
                    String orig = queryDisplayName(u);
                    if (orig == null || orig.isEmpty()) {
                        orig = "导入-" + System.currentTimeMillis() % 100000 + ".txt";
                    }
                    if (!orig.toLowerCase().endsWith(".txt")
                            && !orig.toLowerCase().endsWith(".lst")
                            && !orig.toLowerCase().endsWith(".dic")) {
                        orig = orig + ".txt";
                    }
                    File dst = new File(DictStore.defaultDir(this), orig);

                    // 重名时加序号，不覆盖已有字典
                    int seq = 1;
                    String base = orig.replaceAll("\\.(txt|lst|dic)$", "");
                    String ext = orig.substring(base.length());
                    while (dst.exists()) {
                        dst = new File(DictStore.defaultDir(this), base + "-" + (seq++) + ext);
                    }

                    try (InputStream in = getContentResolver().openInputStream(u);
                         FileOutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    report.append(dst.getName()).append("\n");
                    ok++;
                } catch (Exception e) {
                    report.append(L.s("失败: ", "Failed: ")).append(e).append("\n");
                }
            }
            final int n = ok;
            final String rep = report.toString();
            ui.post(() -> {
                Toast.makeText(this, L.s("导入 ", "Imported ") + n + " 个文件", Toast.LENGTH_SHORT).show();
                if (!rep.isEmpty()) android.util.Log.i("WPAForge", L.s("导入结果:\n", "Import result:\n") + rep);
                refresh();
            });
        }).start();
    }

    /** 从 SAF 的 Uri 里取原始文件名 */
    private String queryDisplayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------------- 统计 / 合并 / 删除 ----------------

    private void showStats(DictStore.Entry e) {
        new Thread(() -> {
            long n = DictStore.count(e.file);
            // 只抽样，不读全量。
            //
            // 原来这里是 100000：对 48 万行的号码变形表会一次
            // 造出十几 MB 的 Candidate 对象，ColorOS 上直接 OOM，
            // 用户点「统计」就闪退。统计本身是抽样语义，
            // 2000 条足够代表长度分布与字符构成。
            List<Candidate> sample = DictStore.load(e, 2000);
            int minLen = 99, maxLen = 0;
            long digits = 0, letters = 0, mixed = 0;
            for (Candidate c : sample) {
                int L = c.password.length();
                if (L < minLen) minLen = L;
                if (L > maxLen) maxLen = L;
                boolean d = c.password.matches("\\d+");
                boolean a = c.password.matches("[A-Za-z]+");
                if (d) digits++;
                else if (a) letters++;
                else mixed++;
            }
            final String body = L.s("文件: ", "File: ") + e.file.getName() + "\n"
                    + L.s("大小: ", "Size: ") + e.sizeLabel() + "\n"
                    + L.s("条数: ", "Entries: ") + n + "\n"
                    + L.s("类型: ", "Type: ") + (e.isRule ? L.s("规则字典", "Rule dictionary") : L.s("普通字典", "Plain dictionary")) + "\n\n"
                    + L.s("抽样 ", "Sampled ") + sample.size() + " 条:\n"
                    + L.s("  纯数字: ", "  digits only: ") + digits + "\n"
                    + L.s("  纯字母: ", "  letters only: ") + letters + "\n"
                    + L.s("  混合: ", "  mixed: ") + mixed + "\n"
                    + L.s("  长度范围: ", "  length range: ") + (sample.isEmpty() ? "-" : minLen + " ~ " + maxLen);
            ui.post(() -> showDialog("统计: " + e.name, body));
        }).start();
    }

    private void doMerge() {
        if (selected.size() < 2) {
            Toast.makeText(this, L.s("先勾选至少 2 本字典：点每行左边的圆圈，或长按整行",
                            "Tick at least 2 dictionaries: tap the circle on the left, or long-press a row"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<File> files = new ArrayList<>();
        for (DictStore.Entry e : selected) files.add(e.file);

        String triedNote = "";
        {
            String key = Prefs.targetKey(this);
            if (key != null && !key.isEmpty()) {
                int n = io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                        io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key)).size();
                if (n > 0) {
                    triedNote = L.s("\n合并结果里跑过的 " + n + " 条候选会在跑的时候自动跳过（按目标分别记）。",
                            "\nThe " + n + " candidates already tried on this target will be "
                                    + "skipped automatically (tracked per target).");
                }
            }
        }
        NetDialog dlg = new NetDialog(this,
                L.s("合并 ", "Merge ") + files.size() + L.s(" 个字典", " dictionaries from the old folder"),
                L.s("重复项会自动去掉。", "Duplicates are removed automatically.") + triedNote);
        EditText et = dlg.addField(L.s("合并后的名称", "Merged name"),
                "合并-" + System.currentTimeMillis() % 10000);
        dlg.addHint(L.s("合并后生成一个新的普通字典，原字典不受影响。", "Creates a new plain dictionary; the originals are untouched."));
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.setPositive(L.s("合并", "Merge"), d -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) name = L.s("合并字典", "Merged dictionary");
            File target = new File(DictStore.defaultDir(this), name + ".txt");
            new Thread(() -> {
                int n = DictStore.merge(files, target, 500000);
                // 合并完顺手报出"其中多少条已经跑过" —— 用户不用翻日志也知道会跳过什么
                int skipped = 0;
                String key = Prefs.targetKey(DictActivity.this);
                if (key != null && !key.isEmpty()) {
                    io.github.wlmosv_png.wpaforge.audit.TriedSet tried =
                            io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                                    io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(
                                            DictActivity.this, key));
                    // 流式统计：合并结果可能有几十万行，不能整份读进内存
                    skipped = io.github.wlmosv_png.wpaforge.audit.TriedSet.countIn(target, tried);
                }
                final int skipN = skipped;
                ui.post(() -> {
                    Toast.makeText(DictActivity.this, skipN > 0
                                    ? L.s("合并完成，共 " + n + " 条；其中 " + skipN
                                            + " 条本目标跑过，开始时会自动跳过",
                                          "Merged " + n + " entries; " + skipN
                                                  + " already tried on this target will be skipped")
                                    : L.s("合并完成，共 " + n + " 条",
                                          "Merge complete, " + n + " entries"),
                            Toast.LENGTH_LONG).show();
                    refresh();
                });
            }).start();
        });
        dlg.show();
    }

    private void confirmDelete(DictStore.Entry e) {
        NetDialog d = new NetDialog(this, L.s("删除字典", "Delete dictionary"),
                L.s("确定删除「", "Delete “") + e.name + L.s("」？此操作不可恢复。", "”? This cannot be undone."));
        d.setNegative(L.s("取消", "Cancel"), null);
        d.setDanger(L.s("删除", "Delete"), dlg -> {
            boolean ok = e.file.delete();
            Toast.makeText(this, ok ? L.s("已删除", "Deleted") : L.s("删除失败", "Delete failed"), Toast.LENGTH_SHORT).show();
            refresh();
        });
        d.show();
    }

    /** 在线字典源列表 */
    private void showOnlineSources() {
        java.util.List<DictSources.Source> sources = DictSources.list();
        NetDialog dlg = new NetDialog(this, L.s("在线字典", "Online sources"),
                L.s("共 ", "Total ") + sources.size()
                        + L.s(" 个公开来源，按类型分组（SecLists 为 MIT，其余多数未声明许可）。",
                                " public sources grouped by type (SecLists is MIT; most others declare no licence).")
                        + L.s("本应用只提供下载入口，不打包任何数据。",
                                " This app only links; it bundles no data.")
                        + L.s("\n在线爆破每个候选都要真连一次 AP（约 1.5 条/秒），条目上标的是预计耗时。",
                                "\nEach candidate is a real association attempt (~1.5/s); the time shown is an estimate."));
        String lastCat = "";
        for (DictSources.Source src : sources) {
            // 分类标题：源按类型分组，避免一屏全是看不懂的大表名字
            if (src.category != null && !src.category.isEmpty()
                    && !src.category.equals(lastCat)) {
                lastCat = src.category;
                TextView h = new TextView(this);
                h.setText(src.category);
                h.setTextSize(12);
                h.setTextColor(ACCENT);
                h.setPadding(px(4), px(12), px(4), px(4));
                dlg.addView(h);
            }
            // 每个源做成两行条目
            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setBackground(roundBg(AmberTheme.SUNKEN, AmberTheme.RADIUS_CHIP));
            item.setPadding(px(16), px(14), px(16), px(14));

            TextView n = new TextView(this);
            n.setText(src.name + "   " + src.sizeHint
                    + (src.entries > 0 ? " · " + DictSources.estimateText(src.entries) : "")
                    + (src.entries > DictSources.HUGE_ENTRIES
                    ? L.s("  ⚠ 太大，在线跑不完", "  ! too large to finish online") : ""));
            n.setTextSize(13.5f);
            n.setTextColor(TEXT);
            item.addView(n);

            TextView d = new TextView(this);
            d.setText(src.desc);
            d.setTextSize(11);
            d.setTextColor(TEXT_DIM);
            d.setPadding(0, px(4), 0, 0);
            item.addView(d);

            TextView lic = new TextView(this);
            lic.setText(L.s("许可: ", "License: ") + src.license + L.s("   点击下载", "   tap to download"));
            lic.setTextSize(10);
            lic.setTextColor(ACCENT);
            lic.setPadding(0, px(5), 0, 0);
            item.addView(lic);

            item.setOnClickListener(v -> downloadSource(src));
            dlg.addView(item);
        }
        dlg.setNegative(L.s("关闭", "Close"), null);
        dlg.show();
    }

    /** 下载一个源 */
    private void downloadSource(DictSources.Source src) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(L.s("准备下载…", "Preparing download…"));
        tv.setTextSize(12.5f);
        tv.setTextColor(TEXT);
        tv.setPadding(px(4), px(6), px(4), px(6));

        final NetDialog prog = new NetDialog(this, L.s("正在下载 ", "Downloading ") + src.name, null);
        prog.addView(tv);
        android.widget.TextView noteTv = new android.widget.TextView(this);
        noteTv.setTextSize(11f);
        noteTv.setTextColor(TEXT_DIM);
        noteTv.setPadding(px(4), px(2), px(4), px(6));
        prog.addView(noteTv);
        prog.addHint(L.s("自动过滤掉短于 8 位（WPA 下限）与超过 63 位的口令并去重，"
                        + "所以最终条数会比原始行数少；条目上写的是原始条数。",
                "Entries shorter than 8 (the WPA minimum) or longer than 63 are filtered and duplicates removed, "
                        + "so the final count is lower than the raw line count shown on the item."));
        prog.setNegative(L.s("后台继续", "Continue in background"), null);
        prog.show();

        new Thread(() -> DictSources.download(src, DictStore.defaultDir(this),
                new DictSources.Progress() {
                    @Override public void onNote(String line) {
                        ui.post(() -> noteTv.setText(line));
                    }
                    @Override public void onStart(int totalBytes) {
                        ui.post(() -> tv.setText(totalBytes > 0
                                ? L.s("开始下载，约 ", "Starting, about ") + (totalBytes / 1024) + " KB"
                                : L.s("开始下载…", "Starting…")));
                    }
                    @Override public void onProgress(long downloaded, int totalBytes) {
                        ui.post(() -> {
                            if (totalBytes > 0) {
                                int pct = (int) (downloaded * 100 / totalBytes);
                                tv.setText(L.s("已下载 ", "Downloaded ") + (downloaded / 1024) + " KB  /  "
                                        + (totalBytes / 1024) + " KB   (" + pct + "%)");
                            } else {
                                tv.setText(L.s("已下载 ", "Downloaded ") + (downloaded / 1024) + " KB");
                            }
                        });
                    }
                    @Override public void onDone(java.io.File file, int lines) {
                        ui.post(() -> {
                            prog.dismiss();
                            NetDialog.info(DictActivity.this, L.s("下载完成", "Download complete"),
                                    file.getName() + "\n\n可用口令 " + lines + L.s(" 条，已存入字典目录。", " entries, saved to the dictionary folder."));
                            refresh();
                        });
                    }
                    @Override public void onError(String message) {
                        ui.post(() -> {
                            prog.dismiss();
                            NetDialog.info(DictActivity.this, L.s("下载失败", "Download failed"), message);
                        });
                    }
                })).start();
    }

    /**
     * 打开字典目录。
     *
     * 没有通用的「打开文件夹」Intent，各 ROM 支持不同，所以多路尝试：
     *   1. 交给系统文件管理器（多数 ROM 认 resource/folder）
     *   2. 退而用 ACTION_GET_CONTENT 打开该目录所在位置
     *   3. 全失败则把路径复制到剪贴板并说明在哪找
     */
    private void openFolder() {
        File dir = DictStore.defaultDir(this);
        String path = dir.getAbsolutePath();

        // 1) 标准文件夹 Intent
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + path), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        // 2) 试 DocumentsUI 打开父目录
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse("file://" + (dir.getParent() != null
                    ? dir.getParent() : path)), "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        // 3) 兜底：复制路径，并说清楚怎么去
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
            }
        } catch (Exception ignored) {}

        NetDialog d = new NetDialog(this, L.s("字典目录", "Dictionary folder"),
                path);
        d.addHint(L.s("没有应用能直接打开文件夹，路径已复制到剪贴板。\n"
                        + "你可以用「文件管理」进入这个路径查看或替换字典文件。",
                "No app can open folders directly. The path is copied to the clipboard.\n"
                        + "Use a file manager to browse it."));
        d.setNegative(L.s("知道了", "Got it"), null);
        d.show();
    }

    private void showDialog(String title, String body) {
        NetDialog.textInfo(this, title, body);
    }
}
