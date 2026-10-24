package io.github.wlmosv_png.wpaforge.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 候选滚动显示（破解页「当前候选」那一行的动效）。
 *
 * <h3>为什么要有它</h3>
 * 候选大约每 1~5 秒换一条。原来只是 {@code setText}：文字直接替换，
 * 眼睛看到的是「闪一下」，长时间盯着会觉得很毛躁（用户要求"顶上来 + 旧的消散"）。
 *
 * <p>现在换文字时：
 * <ul>
 *   <li>新候选从**下方**滑入并渐显（"顶上来"）；</li>
 *   <li>旧候选同时向上滑出并渐隐（"消散"）；</li>
 *   <li>容器裁剪子视图，所以滑动过程不会溢出到卡片外。</li>
 * </ul>
 *
 * <p>纯外观：只负责"怎么显示"，不知道候选从哪来、也不参与任何判定。
 * 关闭动画（无障碍里"移除动画"时）会退化成直接替换。
 */
public class CandidateRoll extends FrameLayout {

    private static final long DURATION_MS = 260L;

    private final TextView shown;    // 当前显示的那条
    private final TextView incoming; // 正在滑入的那条
    private String value = "";

    public CandidateRoll(Context c) { this(c, null); }

    public CandidateRoll(Context c, AttributeSet a) {
        super(c, a);
        setClipChildren(true);

        shown = makeText();
        incoming = makeText();
        incoming.setVisibility(GONE);
        addView(shown);
        addView(incoming);
    }

    private TextView makeText() {
        TextView t = new TextView(getContext());
        t.setTextSize(22f);
        t.setTextColor(AmberTheme.TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    /** 当前显示的候选（未显示过时返回空串）。 */
    public String candidate() {
        return value;
    }

    /** 立刻替换，不做动画（首次显示、清空时用）。 */
    public void setCandidateInstant(String s) {
        value = s == null ? "" : s;
        incoming.animate().cancel();
        incoming.setVisibility(GONE);
        shown.animate().cancel();
        shown.setTranslationY(0f);
        shown.setAlpha(1f);
        shown.setText(value);
    }

    /** 滚入一条新候选：新的从下方顶上来，旧的向上消散。 */
    public void setCandidate(String s) {
        final String next = s == null ? "" : s;
        if (next.equals(value)) return;
        if (animationsDisabled() || value.isEmpty() || getHeight() == 0) {
            setCandidateInstant(next);
            return;
        }

        // incoming 负责"新"，shown 负责"旧"；动画结束后把结果并回 shown，两个视图轮流用
        incoming.setText(next);
        incoming.setVisibility(VISIBLE);
        incoming.setTranslationY(getHeight() * 0.9f);
        incoming.setAlpha(0f);

        shown.animate().cancel();
        incoming.animate().cancel();
        incoming.animate().translationY(0f).alpha(1f).setDuration(DURATION_MS).start();
        shown.animate().translationY(-getHeight() * 0.9f).alpha(0f)
                .setDuration(DURATION_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        // 旧的已经看不见了：把最新的内容并到 shown 上，隐藏 incoming，
                        // 这样下一轮还能用同一对视图（避免每换一条就新建 View）
                        shown.setText(next);
                        shown.setTranslationY(0f);
                        shown.setAlpha(1f);
                        incoming.setVisibility(GONE);
                        incoming.setTranslationY(0f);
                        incoming.setAlpha(1f);
                        shown.animate().setListener(null);
                    }
                })
                .start();
        value = next;
    }

    private boolean animationsDisabled() {
        try {
            return !android.animation.ValueAnimator.areAnimatorsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
}
