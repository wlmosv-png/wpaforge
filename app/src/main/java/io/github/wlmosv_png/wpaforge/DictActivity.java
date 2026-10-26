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

    private int px(float v) { return Math.round(v * dp); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 标题
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(26), px(46), px(22), px(8));
        TextView t = new TextView(this);
        t.setText(L.s("字典管理", "Dictionary management"));
        t.setTextSize(21);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(t);

        pathLabel = new TextView(this);
        pathLabel.setTextSize(9.5f);
        pathLabel.setTextColor(TEXT_DIM);
        head.addView(pathLabel);

        statLabel = new TextView(this);
        statLabel.setTextSize(11.5f);
        statLabel.setTextColor(ACCENT);
        statLabel.setPadding(0, px(4), 0, 0);
        head.addView(statLabel);

        // 搜索入口直接摆在顶上。
        // 上一版把它塞进「＋」→ 字典操作菜单里，用户找不到 —— 找不到就等于没有。
        Button searchBtn = chip(L.s("🔍  搜索字典内容", "🔍  Search in dictionaries"),
                v -> showSearchPicker());
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, px(8), 0, px(2));
        searchRow.addView(searchBtn);
        head.addView(searchRow);
        root.addView(head);

        // 操作区：两行三列，全部可见，不需要横向滚动
        // 【收拢】原来这里是两行六个大按钮，占了屏幕上部一整块，
        // 而六个功能里有四个一周用不到一次。
        // 现在全部收进右下角的悬浮按钮，列表直接顶到标题下面。
        FrameLayout stage = new FrameLayout(this);

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(px(14), px(8), px(14), px(96));
        sv.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button fab = new Button(this);
        fab.setText("+");
        fab.setAllCaps(false);
        fab.setTextSize(24);
        fab.setTextColor(AmberTheme.ON_ACCENT);
        fab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fab.setMinWidth(0); fab.setMinimumWidth(0);
        fab.setMinHeight(0); fab.setMinimumHeight(0);
        fab.setStateListAnimator(null);
        fab.setPadding(0, 0, 0, px(2));
        fab.setBackground(roundBg(ACCENT, AmberTheme.RADIUS_BTN));
        fab.setOnClickListener(v -> showActionSheet());

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(px(58), px(58));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = px(20);
        flp.bottomMargin = px(24);
        stage.addView(fab, flp);

        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /** 主操作弹窗：原来的六个入口都在这里 */
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
        pathLabel.setText(L.s("目录: ", "Directory: ") + dir.getAbsolutePath());
        listBox.removeAllViews();

        /* 顶部状态行：本目标已经试过多少条。
         * 这是"换字典不再重复跑"的可见证据 —— 用户得先看见它，
         * 才会相信"合并后跑的时候不会重复"。 */
        {
            String key = Prefs.targetKey(this);
            String label = Prefs.targetLabel(this);
            if (key != null && !key.isEmpty()) {
                io.github.wlmosv_png.wpaforge.audit.TriedSet tried =
                        io.github.wlmosv_png.wpaforge.audit.TriedSet.load(
                                io.github.wlmosv_png.wpaforge.audit.TriedSet.fileFor(this, key));
                TextView info = new TextView(this);
                info.setText(tried.size() > 0
                        ? L.s("目标 ", "Target ") + (label.isEmpty() ? "?" : label)
                                + L.s(" 已试过 ", " already tried ") + tried.size()
                                + L.s(" 条 —— 跑字典时会自动跳过，不重复劳动",
                                      " entries \u2014 auto-skipped when running, no repeats")
                        : L.s("目标 ", "Target ") + (label.isEmpty() ? "?" : label)
                                + L.s(" 还没有已试记录", " has no tried record yet"));
                info.setTextSize(11.5f);
                info.setTextColor(tried.size() > 0 ? AmberTheme.OK : TEXT_DIM);
                info.setPadding(px(4), 0, px(4), px(8));
                listBox.addView(info);
            }
        }

        List<DictStore.Entry> entries = DictStore.scan(dir);

        // 只丢弃「已经不在列表里」的选择（比如刚被删掉），**不整体清空** ——
        // 原来这里无条件 selected.clear()，而每次切换选择都会 refresh，
        // 结果就是「选一个、刷一下、没了」。
        {
            List<DictStore.Entry> keep = new ArrayList<>();
            for (DictStore.Entry sel : selected) {
                for (DictStore.Entry cur : entries) {
                    if (cur.file.equals(sel.file)) { keep.add(cur); break; }
                }
            }
            selected.clear();
            selected.addAll(keep);
        }

        // 多选状态条：只在有选择时出现，把「已选多少 + 能做什么」直接摆出来
        if (!selected.isEmpty()) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            bar.setBackground(roundBgStroked(AmberTheme.CARD_2, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
            bar.setPadding(px(14), px(10), px(14), px(10));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = px(10);
            bar.setLayoutParams(blp);

            TextView tv = new TextView(this);
            tv.setText(L.s("已选 " + selected.size() + " 本", selected.size() + " selected"));
            tv.setTextSize(12.5f);
            tv.setTextColor(TEXT);
            bar.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            bar.addView(chip(L.s("合并", "Merge"), v -> doMerge()));
            bar.addView(chip(L.s("清除", "Clear"), v -> {
                selected.clear();
                refresh();
            }));
            listBox.addView(bar);
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("目录里还没有字典。\n\n你可以：\n"
                    + L.s("· 点「新建普通」手写一个\n", "· tap “New dictionary” to write one\n")
                    + L.s("· 点「新建规则」用区间规则批量生成（如 00000000→99999999）\n", "· tap “New rule” to generate a range (e.g. 00000000→99999999)\n")
                    + L.s("· 点「导入」从手机里选一个 txt 文件\n", "· tap “Import” to pick a txt file\n")
                    + L.s("· 点「打开目录」把字典文件拷进去后刷新", "· tap “Open folder” to copy files in, then refresh"));
            empty.setTextSize(13);
            empty.setTextColor(TEXT_DIM);
            empty.setLineSpacing(px(6), 1f);
            empty.setBackground(roundBgStroked(SURFACE, AmberTheme.EDGE, AmberTheme.RADIUS_CARD, 0.8f));
            empty.setPadding(px(20), px(22), px(20), px(22));
            listBox.addView(empty);
            statLabel.setText(L.s("共 0 个字典", "0 dictionaries"));
            return;
        }

        long totalBytes = 0;
        for (DictStore.Entry e : entries) {
            totalBytes += e.sizeBytes;
            listBox.addView(row(e));
        }
        statLabel.setText(L.s("共 ", "Total ") + entries.size() + L.s(" 个字典 · ", " dictionaries · ")
                + (totalBytes / 1024 / 1024) + " MB");
    }

    private View row(DictStore.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 选中态给一个明显的底色，多选时一眼看得出选了哪几本
        box.setBackground(roundBg(selected.contains(e)
                ? AmberTheme.ACCENT_BG : SURFACE, 18));
        box.setPadding(px(16), px(14), px(16), px(14));
        // 长按进入多选（Android 标准交互）—— 这样就不必再占一个「选择」按钮，
        // 那一行原本挤了 5 个按钮，本来就不好按。
        //
        // 注意：长按必须挂到**整行的每一个子控件**上。只挂容器没用 ——
        // 手指落在按钮或文字上时，长按会被那个子控件吃掉（上一版就是这么失效的）。
        final View.OnLongClickListener longPress = v -> {
            toggleSelect(e);
            return true;
        };
        box.setOnLongClickListener(longPress);
        box.setLongClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextSize(15);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(e.isRule ? L.s("规则", "Rule") : L.s("普通", "Plain"));
        badge.setTextSize(10);
        badge.setTextColor(e.isRule ? ACCENT : TEXT_DIM);
        badge.setPadding(px(8), px(2), px(8), px(2));
        top.addView(badge);

        box.addView(top);

        TextView meta = new TextView(this);
        meta.setText(e.sizeLabel() + "   " + e.file.getName());
        meta.setTextSize(10);
        meta.setTextColor(TEXT_DIM);
        box.addView(meta);

        // 操作行
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, px(10), 0, 0);
        ops.setClipToPadding(false);
        ops.addView(smallBtn(L.s("查看", "View"), TEXT_DIM, v -> viewDict(e)));
        ops.addView(smallBtn(L.s("编辑", "Edit"), ACCENT, v -> editDict(e)));
        ops.addView(smallBtn(L.s("统计", "Stats"), OK_GREEN, v -> showStats(e)));

        ops.addView(smallBtn(L.s("删除", "Delete"), DANGER, v -> confirmDelete(e)));

        // 把长按转发给这一行的所有子控件（按钮、文字、元信息行……）
        attachLongPress(box, longPress);

        if (!selected.isEmpty()) {
            // 多选进行中：点整行也能切换，不必精准点到别处
            box.setOnClickListener(v -> toggleSelect(e));
        }
        box.addView(ops);

        return box;
    }

    /** 把长按监听装到某个视图及其所有子控件上（子控件默认会吞掉长按） */
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
            Toast.makeText(this, "请先选择至少 2 个字典（点「选择」）", Toast.LENGTH_SHORT).show();
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
                ui.post(() -> {
                    Toast.makeText(this, L.s("合并完成，共 ", "Merge complete, ") + n + L.s(" 条", " entries"),
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
