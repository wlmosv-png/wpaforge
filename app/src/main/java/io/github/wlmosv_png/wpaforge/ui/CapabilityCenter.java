package io.github.wlmosv_png.wpaforge.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import io.github.wlmosv_png.wpaforge.L;
import io.github.wlmosv_png.wpaforge.cap.Capability;

/**
 * **权限与能力中心**的渲染器（任务书第六部分）。
 *
 * <h3>为什么单独成类</h3>
 * 设置页原来只有三个模式按钮 + 一行状态，用户看不出
 * 「这台设备到底能做什么、缺什么、怎么补」。本类把它拆成三层：
 * <ol>
 *   <li><b>当前环境</b>：模式 / 实际通道 / Root / Shizuku / Android / ROM / 检测时刻；</li>
 *   <li><b>权限状态</b>：Root / Shizuku / 悬浮窗 / 通知 / 后台，每项带状态与处理入口；</li>
 *   <li><b>功能能力</b>：逐项列出，含检测依据与影响范围。</li>
 * </ol>
 *
 * <h3>硬约束</h3>
 * <ul>
 *   <li>所有内容必须由**真实检测结果**生成，不允许写死「可用」；</li>
 *   <li>没有证据就显示 UNKNOWN，不得显示成不可用；</li>
 *   <li>不能因为某项高级能力不可用，就把其他可用功能一起标成不可用。</li>
 * </ul>
 */
public final class CapabilityCenter {

    private CapabilityCenter() {
    }

    /** 一个「权限行」的数据。 */
    public static final class PermRow {
        public final String name;
        public final String state;
        /** true = 可用（绿）；false = 需要处理（琥珀/红）；null = 未知（灰）。 */
        public final Boolean ok;
        public final String detail;
        /** 可点的处理入口说明（空 = 无入口）。 */
        public final String action;
        public final Runnable onAction;

        public PermRow(String name, String state, Boolean ok, String detail,
                       String action, Runnable onAction) {
            this.name = name;
            this.state = state;
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
            this.action = action == null ? "" : action;
            this.onAction = onAction;
        }
    }

    /** 把 {@link Capability} 的状态映射成配色。 */
    public static int stateColor(String state) {
        if (Capability.STATE_AVAILABLE.equals(state)) return AmberTheme.OK;
        if (Capability.STATE_DEGRADED.equals(state)) return AmberTheme.ACCENT_MID;
        if (Capability.STATE_NEEDS_GRANT.equals(state)) return AmberTheme.ACCENT;
        if (Capability.STATE_UNSUPPORTED.equals(state)) return AmberTheme.DANGER;
        // UNKNOWN：灰色 —— 不能染成红色（那会被读成「不支持」）
        return AmberTheme.TEXT_FAINT;
    }

    /** 状态的本地化短名。 */
    public static String stateLabel(String state, boolean zh) {
        if (Capability.STATE_AVAILABLE.equals(state)) return zh ? "可用" : "available";
        if (Capability.STATE_DEGRADED.equals(state)) return zh ? "降级可用" : "degraded";
        if (Capability.STATE_NEEDS_GRANT.equals(state)) return zh ? "待授权" : "needs grant";
        if (Capability.STATE_UNSUPPORTED.equals(state)) return zh ? "不支持" : "unsupported";
        return zh ? "未知" : "unknown";
    }

    /**
     * 渲染「当前环境」卡片。
     *
     * @param rows 已算好的行（键值对，例如「Shizuku 服务 / 已运行」）
     */
    public static View environmentCard(Context ctx, float density, List<PermRow> rows) {
        LinearLayout card = AmberTheme.card(ctx, density, AmberTheme.RADIUS_CARD);
        TextView title = new TextView(ctx);
        title.setText(L.s("当前执行环境", "Current environment"));
        title.setTextSize(AmberTheme.FS_TITLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(AmberTheme.TEXT);
        card.addView(title);
        addRows(ctx, card, density, rows);
        return card;
    }

    /** 渲染「权限状态」卡片。 */
    public static View permissionCard(Context ctx, float density, List<PermRow> rows) {
        LinearLayout card = AmberTheme.card(ctx, density, AmberTheme.RADIUS_CARD);
        TextView title = new TextView(ctx);
        title.setText(L.s("权限状态", "Permissions"));
        title.setTextSize(AmberTheme.FS_TITLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(AmberTheme.TEXT);
        card.addView(title);
        addRows(ctx, card, density, rows);
        return card;
    }

    /** 渲染「功能能力」卡片（直接吃 Capability 列表）。 */
    public static View capabilityCard(Context ctx, float density, List<Capability> caps) {
        LinearLayout card = AmberTheme.card(ctx, density, AmberTheme.RADIUS_CARD);
        TextView title = new TextView(ctx);
        title.setText(L.s("功能能力", "Capabilities"));
        title.setTextSize(AmberTheme.FS_TITLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(AmberTheme.TEXT);
        card.addView(title);

        if (caps == null || caps.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText(L.s("还没有检测结果。点上面的状态行重新检测。",
                    "No results yet. Tap the status line above to re-check."));
            empty.setTextSize(AmberTheme.FS_LABEL);
            empty.setTextColor(AmberTheme.TEXT_FAINT);
            empty.setPadding(0, Math.round(8 * density), 0, 0);
            card.addView(empty);
            return card;
        }

        boolean zh = L.isZh();
        for (Capability c : caps) {
            card.addView(capabilityRow(ctx, density, c, zh));
        }
        return card;
    }

    /** 单个能力行：名字 + 状态徽标 + 原因 + 依据 + 影响。 */
    private static View capabilityRow(Context ctx, float density, Capability c, boolean zh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Math.round(10 * density), 0, 0);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView name = new TextView(ctx);
        name.setText(Capability.name(c.id, zh));
        name.setTextSize(AmberTheme.FS_BODY);
        name.setTextColor(AmberTheme.TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(name, lp);

        TextView badge = new TextView(ctx);
        badge.setText(stateLabel(c.state, zh));
        badge.setTextSize(AmberTheme.FS_LABEL);
        badge.setTextColor(stateColor(c.state));
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(badge);

        row.addView(head);

        String reason = zh ? c.reasonZh : c.reasonEn;
        if (!reason.isEmpty()) {
            TextView r = new TextView(ctx);
            r.setText(reason);
            r.setTextSize(AmberTheme.FS_LABEL);
            r.setTextColor(AmberTheme.TEXT_DIM);
            r.setLineSpacing(Math.round(2 * density), 1f);
            row.addView(r);
        }

        // 检测依据（任务书：每项要能说清依据）
        if (!c.basis.isEmpty() || !c.channel.isEmpty()) {
            TextView b = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            sb.append(L.s("依据: ", "basis: "));
            if (!c.channel.isEmpty()) sb.append(L.s("通道=", "channel=")).append(c.channel);
            if (!c.basis.isEmpty()) {
                if (!c.channel.isEmpty()) sb.append(" · ");
                sb.append(c.basis);
            }
            b.setText(sb.toString());
            b.setTextSize(AmberTheme.FS_LABEL);
            b.setTextColor(AmberTheme.TEXT_FAINT);
            row.addView(b);
        }

        // 替代路径（不可用时必须有）
        String alt = zh ? c.alternativeZh : c.alternativeEn;
        if (!alt.isEmpty()) {
            TextView a = new TextView(ctx);
            a.setText(L.s("替代: ", "alternative: ") + alt);
            a.setTextSize(AmberTheme.FS_LABEL);
            a.setTextColor(AmberTheme.ACCENT_MID);
            a.setLineSpacing(Math.round(2 * density), 1f);
            row.addView(a);
        }
        return row;
    }

    /** 通用行渲染：名字 / 状态 / 详情 / 可点操作。 */
    private static void addRows(Context ctx, LinearLayout card, float density,
                                List<PermRow> rows) {
        if (rows == null) return;
        for (PermRow r : rows) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Math.round(10 * density), 0, 0);

            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);

            TextView name = new TextView(ctx);
            name.setText(r.name);
            name.setTextSize(AmberTheme.FS_BODY);
            name.setTextColor(AmberTheme.TEXT);
            head.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView st = new TextView(ctx);
            st.setText(r.state);
            st.setTextSize(AmberTheme.FS_LABEL);
            st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            // ok 三态：true 绿 / false 琥珀或红 / null 灰（未知）
            st.setTextColor(r.ok == null ? AmberTheme.TEXT_FAINT
                    : (r.ok ? AmberTheme.OK : AmberTheme.ACCENT_MID));
            head.addView(st);
            row.addView(head);

            if (!r.detail.isEmpty()) {
                TextView d = new TextView(ctx);
                d.setText(r.detail);
                d.setTextSize(AmberTheme.FS_LABEL);
                d.setTextColor(AmberTheme.TEXT_DIM);
                d.setLineSpacing(Math.round(2 * density), 1f);
                row.addView(d);
            }

            if (!r.action.isEmpty() && r.onAction != null) {
                TextView a = new TextView(ctx);
                a.setText(r.action);
                a.setTextSize(AmberTheme.FS_LABEL);
                a.setTextColor(AmberTheme.ACCENT);
                a.setPadding(0, Math.round(6 * density), 0, Math.round(4 * density));
                a.setOnClickListener(v -> {
                    try { r.onAction.run(); } catch (Throwable ignored) {}
                });
                row.addView(a);
            }
            card.addView(row);
        }
    }
}
