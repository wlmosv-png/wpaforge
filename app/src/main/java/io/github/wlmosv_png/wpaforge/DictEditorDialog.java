package io.github.wlmosv_png.wpaforge;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Selection;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.wlmosv_png.wpaforge.ui.AmberTheme;
import io.github.wlmosv_png.wpaforge.ui.NetDialog;
import io.github.wlmosv_png.wpaforge.audit.LineNumbers;

/**
 * 带**行号栏**的字典编辑器。
 *
 * <h3>它替换什么</h3>
 * 旧编辑器（{@code DictActivity.showEditDialog}）把整个文件塞进一个裸
 * {@code EditText}：没有行号、没有光标位置、改没改也看不出来。字典是
 * **按行**使用的资产（「第 128 行那条要删掉」是最常见的诉求），没有行号
 * 就等于让人在两千多行里用眼睛数。
 *
 * <h3>行号怎么实现（以及为什么这么选）</h3>
 * <pre>
 *   ┌──────────┬──────────────────────────────┐
 *   │ 行号栏    │  HorizontalScrollView        │
 *   │ (TextView)│   └ EditText（横向滚动）      │
 *   └──────────┴──────────────────────────────┘
 *        ↑ 只同步**纵向**滚动
 * </pre>
 * <ul>
 *   <li>行号栏**在横向滚动容器之外** → 横向滚文本时行号不会跟着跑掉；</li>
 *   <li>纵向同步靠 {@code setOnScrollChangeListener} 把 EditText 的
 *       {@code scrollY} 抄给行号栏 —— 只搬一个整数，不重算文本；</li>
 *   <li>行号本身是一整块文本（{@link LineNumbers#render}），
 *       **不是一个行一个控件** —— 2500 行不会造出 2500 个 View；</li>
 *   <li>文本变化后**防抖 180ms** 再重算行号，避免每敲一个字就重建整块文本；</li>
 *   <li>超过 {@link LineNumbers#DEFAULT_MAX_LINES} 行时行号自动关闭并在信息行说明
 *       —— 大文件不为装饰性信息付渲染代价。</li>
 * </ul>
 *
 * <h3>行号绝不会进入文件</h3>
 * 行号是**另一个 View** 的内容，与 {@code EditText} 的文本在数据结构上无关；
 * 保存只取 {@code EditText} 的文本。这一条由结构保证，不是靠过滤。
 *
 * <h3>候选解析规则不变</h3>
 * 本对话框只回传**原始文本**，清洗与拆分仍由调用方的
 * {@code DictActivity.splitClean} 负责 —— 需求明确要求保留既有解析规则。
 */
public final class DictEditorDialog {

    /** 保存回调：回传编辑器里的**原始文本**，由调用方按既有规则清洗。 */
    public interface Save {
        void onSave(String rawText);
    }

    private DictEditorDialog() {}

    public static void show(final Activity act, final String name,
                            final List<String> lines, final Save onSave) {
        final float dp = act.getResources().getDisplayMetrics().density;
        final StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        final String original = sb.toString();

        final NetDialog dlg = new NetDialog(act, L.s("编辑 ", "Edit ") + name, null);

        // ── 信息行：原有条数 / 当前行数 / 光标位置 / 修改状态 ──
        final TextView info = new TextView(act);
        info.setTextSize(AmberTheme.FS_SMALL);
        info.setTextColor(AmberTheme.TEXT_DIM);
        dlg.addView(info);

        // ── 行号栏 ──
        final TextView gutter = new TextView(act);
        gutter.setTypeface(android.graphics.Typeface.MONOSPACE);
        gutter.setTextSize(AmberTheme.FS_LOG);
        gutter.setTextColor(AmberTheme.TEXT_FAINT);
        gutter.setGravity(Gravity.END);
        gutter.setPadding((int) (2 * dp), (int) (8 * dp), (int) (6 * dp), (int) (8 * dp));
        gutter.setLineSpacing(0f, 1f);

        // ── 正文 ──
        final SelEdit area = new SelEdit(act);
        area.setTextSize(AmberTheme.FS_LOG);
        area.setTextColor(AmberTheme.TEXT);
        area.setTypeface(android.graphics.Typeface.MONOSPACE);
        area.setGravity(Gravity.START);
        area.setPadding((int) (6 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        area.setLineSpacing(0f, 1f);
        area.setText(original);
        area.setSelection(0);
        area.setMovementMethod(new ScrollingMovementMethod());
        area.setBackgroundColor(AmberTheme.CARD_3);

        final HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(true);
        hsv.addView(area, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setBackgroundColor(AmberTheme.CARD_3);
        body.addView(gutter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(hsv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (310 * dp));
        blp.topMargin = (int) (6 * dp);
        blp.bottomMargin = (int) (4 * dp);
        body.setLayoutParams(blp);
        dlg.addView(body);

        // ── 工具行：定位行 / 查找 / 下一个 / 全选 ──
        final LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(0, (int) (2 * dp), 0, (int) (2 * dp));
        final int[] lastFind = {-1};
        final String[] lastKw = {""};
        tools.addView(tool(act, dp, L.s("定位行", "Go to line"), v -> askGoToLine(act, area)));
        tools.addView(tool(act, dp, L.s("查找", "Find"), v -> askFind(act, area, lastFind, lastKw)));
        tools.addView(tool(act, dp, L.s("下一个", "Next"), v -> findNext(area, lastFind, lastKw)));
        tools.addView(tool(act, dp, L.s("全选", "Select all"), v -> area.selectAll()));
        dlg.addView(tools);

        dlg.addHint(L.s("行号只是显示，不会写进文件。保存会整份写回（"
                        + lines.size() + " 条原内容）。",
                "Line numbers are display-only and never written to the file. "
                        + "Saving writes the whole file back (" + lines.size() + " entries)."));

        // ── 光标 / 行号 / 信息刷新 ──
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String txt = area.getText().toString();
            int ln = LineNumbers.countLines(txt);
            gutter.setText(LineNumbers.render(ln, LineNumbers.DEFAULT_MAX_LINES));
            gutter.scrollTo(0, area.getScrollY());
            updateInfo(info, txt, ln, area.getSelectionStart(), original);
        };
        area.setOnScrollChangeListener((v, sx, sy, ox, oy) -> gutter.scrollTo(0, sy));
        area.setOnSel(() -> {
            String txt = area.getText().toString();
            updateInfo(info, txt, LineNumbers.countLines(txt),
                    area.getSelectionStart(), original);
        });
        area.addTextChangedListener(new TextWatcher() {
            Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> refresh[0].run();
                h.postDelayed(pending, 180);     // 防抖：不为每个按键重建行号
            }
        });
        refresh[0].run();

        // ── 取消 / 保存（有改动时二次确认，绝不静默丢弃）──
        dlg.setNegative(L.s("取消", "Cancel"), d -> { });
        dlg.setPositive(L.s("保存", "Save"), d -> {
            final String now = area.getText().toString();
            if (now.equals(original)) {
                dlg.dismiss();
                return;
            }
            new NetDialog(act, L.s("保存修改？", "Save changes?"),
                    L.s("内容已改动。保存会整份写回这本字典。",
                            "Contents changed. Saving writes the whole file back."))
                    .setNegative(L.s("继续编辑", "Keep editing"), dd -> { })
                    .setPositive(L.s("保存", "Save"), dd -> {
                        dd.dismiss();
                        dlg.dismiss();
                        onSave.onSave(now);
                    })
                    .show();
        });
        dlg.show();
    }

    /** 带选中回调的 EditText —— 原生没有公开的 selection 监听。 */
    private static final class SelEdit extends EditText {
        private Runnable onSel;
        SelEdit(Activity a) { super(a); }
        void setOnSel(Runnable r) { onSel = r; }
        @Override protected void onSelectionChanged(int s, int e) {
            super.onSelectionChanged(s, e);
            if (onSel != null) onSel.run();
        }
    }

    private static void updateInfo(TextView info, String txt, int lines,
                                   int sel, String original) {
        int line = LineNumbers.lineOf(txt, sel);
        int col = LineNumbers.columnOf(txt, sel);
        boolean dirty = !txt.equals(original);
        info.setText(L.s("第 " + line + " 行 · 第 " + col + " 列 · 共 " + lines + " 行 · "
                        + (dirty ? "已修改" : "未修改"),
                "line " + line + ", col " + col + ", " + lines
                        + (dirty ? " · modified" : " · unchanged")));
        info.setTextColor(dirty ? AmberTheme.ACCENT : AmberTheme.TEXT_DIM);
    }

    private static Button tool(Activity act, float dp, String label,
                               View.OnClickListener l) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(AmberTheme.FS_SMALL);
        b.setTextColor(AmberTheme.ACCENT);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setPadding((int) (10 * dp), (int) (6 * dp), (int) (10 * dp), (int) (6 * dp));
        b.setOnClickListener(l);
        return b;
    }

    /** 定位到指定行（1 起）。 */
    private static void askGoToLine(final Activity act, final EditText area) {
        final EditText et = new EditText(act);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        NetDialog d = new NetDialog(act, L.s("定位到行", "Go to line"),
                L.s("输入行号（1 起）", "Line number (1-based)"));
        d.addView(et);
        d.setNegative(L.s("取消", "Cancel"), dd -> { });
        d.setPositive(L.s("定位", "Go"), dd -> {
                    int want;
                    try {
                        want = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception ex) {
                        return;
                    }
                    String txt = area.getText().toString();
                    int total = LineNumbers.countLines(txt);
                    if (want < 1 || want > total) {
                        Toast.makeText(act, L.s("行号超出范围（共 " + total + " 行）",
                                        "out of range (file has " + total + " lines)"),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int off = LineNumbers.offsetOfLine(txt, want);
                    area.requestFocus();
                    Selection.setSelection(area.getText(), off);
                });
        d.show();
    }

    /** 查找文本；找到就选中并滚动过去。 */
    private static void askFind(final Activity act, final EditText area,
                                final int[] lastFind, final String[] lastKw) {
        final EditText et = new EditText(act);
        NetDialog fd = new NetDialog(act, L.s("查找", "Find"), null);
        fd.addView(et);
        fd.setNegative(L.s("取消", "Cancel"), dd -> { });
        fd.setPositive(L.s("查找", "Find"), dd -> {
            lastKw[0] = et.getText().toString();
            lastFind[0] = -1;
            findNext(area, lastFind, lastKw);
        });
        fd.show();
    }

    private static void findNext(EditText area, int[] lastFind, String[] lastKw) {
        String kw = lastKw[0];
        if (kw == null || kw.isEmpty()) return;
        String txt = area.getText().toString();
        int from = lastFind[0] < 0 ? 0 : lastFind[0] + kw.length();
        int at = txt.indexOf(kw, Math.max(0, from));
        if (at < 0 && from > 0) at = txt.indexOf(kw, 0);     // 回环
        if (at < 0) {
            Toast.makeText(area.getContext(), L.s("没有找到", "not found"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        lastFind[0] = at;
        area.requestFocus();
        Selection.setSelection(area.getText(), at, at + kw.length());
    }
}
