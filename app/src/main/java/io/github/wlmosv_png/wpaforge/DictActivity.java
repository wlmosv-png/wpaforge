package io.github.wlmosv_png.wpaforge;

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

    private static final int BG       = Color.parseColor("#0E0E11");
    private static final int SURFACE  = Color.parseColor("#17171C");
    private static final int ACCENT   = Color.parseColor("#FFB300");
    private static final int TEXT     = Color.parseColor("#E8E8E8");
    private static final int TEXT_DIM = Color.parseColor("#8A8A8A");
    private static final int DANGER   = Color.parseColor("#FF5252");
    private static final int OK_GREEN = Color.parseColor("#4CAF50");

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
        fab.setTextColor(Color.parseColor("#14141A"));
        fab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fab.setMinWidth(0); fab.setMinimumWidth(0);
        fab.setMinHeight(0); fab.setMinimumHeight(0);
        fab.setStateListAnimator(null);
        fab.setPadding(0, 0, 0, px(2));
        fab.setBackground(roundBg(ACCENT, 30));
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
        box.setBackground(roundBg(Color.parseColor("#1F1F2A"), 16));
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
        b.setTextColor(Color.parseColor("#14141A"));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(16), px(10), px(16), px(10));
        b.setBackground(roundBg(ACCENT, 22));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(10);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- 列表 ----------------

    private void refresh() {
        File dir = DictStore.defaultDir(this);
        pathLabel.setText(L.s("目录: ", "Directory: ") + dir.getAbsolutePath());
        listBox.removeAllViews();
        selected.clear();

        List<DictStore.Entry> entries = DictStore.scan(dir);
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
            empty.setBackground(roundBg(SURFACE, 18));
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
        box.setBackground(roundBg(SURFACE, 18));
        box.setPadding(px(16), px(14), px(16), px(14));
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
        ops.addView(smallBtn(selected.contains(e) ? L.s("已选", "Selected") : L.s("选择", "Select"),
                selected.contains(e) ? OK_GREEN : TEXT_DIM, v -> {
                    if (selected.contains(e)) selected.remove(e);
                    else selected.add(e);
                    refresh();
                }));
        ops.addView(smallBtn(L.s("删除", "Delete"), DANGER, v -> confirmDelete(e)));
        box.addView(ops);

        return box;
    }

    private Button smallBtn(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11.5f);
        b.setTextColor(color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundBg(Color.parseColor("#232330"), 16));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding(px(14), px(7), px(14), px(7));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(7);
        b.setLayoutParams(lp);
        return b;
    }

    /** 圆角背景 */
    private android.graphics.drawable.Drawable roundBg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
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

        // 普通字典：预览前 50 行
        new Thread(() -> {
            List<String> lines = DictStore.readLines(e.file);
            int show = Math.min(50, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < show; i++) sb.append(lines.get(i)).append('\n');
            final String body = L.s("共 ", "Total ") + lines.size() + L.s(" 条，显示前 ", " entries, showing first ") + show + L.s(" 条:\n\n", " entries:\n\n") + sb;
            ui.post(() -> showDialog(L.s("字典内容: ", "Dictionary contents: ") + e.name, body));
        }).start();
    }

    private String previewOf(DictRule r, int n) {
        List<Candidate> list = r.expand(n);
        StringBuilder sb = new StringBuilder();
        for (Candidate c : list) sb.append(c.password).append('\n');
        return sb.toString();
    }

    // ---------------- 编辑 ----------------

    private void editDict(DictStore.Entry e) {
        NetDialog dlg = new NetDialog(this, L.s("编辑 ", "Edit ") + e.name, null);
        final EditText area = dlg.addTextArea(L.s("每行一个口令", "One passphrase per line"), "", 12);
        dlg.addHint(L.s("只显示前 2000 条。保存会覆盖原文件，未显示的部分会丢失。", "Only the first 2000 lines are shown. Saving overwrites the file; hidden lines are lost."));
        dlg.setNegative(L.s("取消", "Cancel"), null);
        dlg.setPositive(L.s("保存", "Save"), d -> {
            List<String> out = new ArrayList<>();
            for (String l : area.getText().toString().split("\n")) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
            }
            new Thread(() -> {
                boolean ok = DictStore.save(e.file, out);
                ui.post(() -> {
                    Toast.makeText(this, ok ? L.s("已保存 ", "Saved ") + out.size() + L.s(" 条", " entries")
                            : L.s("保存失败", "Save failed"), Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }).start();
        });
        dlg.show();

        new Thread(() -> {
            List<String> lines = DictStore.readLines(e.file);
            StringBuilder sb = new StringBuilder();
            int cap = Math.min(lines.size(), 2000);
            for (int i = 0; i < cap; i++) sb.append(lines.get(i)).append('\n');
            final String text = sb.toString();
            ui.post(() -> area.setText(text));
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
            List<Candidate> sample = DictStore.load(e, 100000);
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

        NetDialog dlg = new NetDialog(this,
                L.s("合并 ", "Merge ") + files.size() + L.s(" 个字典", " dictionaries from the old folder"), L.s("重复项会自动去掉。", "Duplicates are removed automatically."));
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
                L.s("共 ", "Total ") + sources.size() + L.s(" 个公开来源。数据来自 SecLists（MIT 许可）等开源项目，", " public sources. Data comes from SecLists (MIT) and similar open-source projects,")
                + L.s("下载后存在本机。", "saved locally after download."));
        for (DictSources.Source src : sources) {
            // 每个源做成两行条目
            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setBackground(roundBg(Color.parseColor("#12121A"), 14));
            item.setPadding(px(16), px(14), px(16), px(14));

            TextView n = new TextView(this);
            n.setText(src.name + "   " + src.sizeHint);
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
        prog.addHint(L.s("下载完成后会自动过滤掉长度不合法的口令并去重。", "Invalid lengths are filtered and duplicates removed after download."));
        prog.setNegative(L.s("后台继续", "Continue in background"), null);
        prog.show();

        new Thread(() -> DictSources.download(src, DictStore.defaultDir(this),
                new DictSources.Progress() {
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
