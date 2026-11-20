package io.github.wlmosv_png.wpaforge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.wlmosv_png.wpaforge.cap.PublicWifiProbe;
import io.github.wlmosv_png.wpaforge.cap.PublicWifiVerdict;

/**
 * **Android 公共 Wi-Fi API 可行性验证器（独立界面）**（2026-11-20 第四阶段）。
 *
 * <h3>它是什么</h3>
 * 一个**只做一件事**的验证页面：输入 SSID + 一条口令 → 点一次 → 看结果。
 * 用于回答「这台设备的 Android 公共 API 到底能不能完成一次真实连接」，
 * **不是**破解引擎，也**不是**批量尝试器。
 *
 * <h3>刻意不做的事（任务书硬约束）</h3>
 * <ul>
 *   <li>**不循环**：一次点击只发一条凭据，绝不后台连续尝试；<\/li>
 *   <li>**不绕过确认框**：系统要用户点允许就正常弹；<\/li>
 *   <li>**不拿 onAvailable 当成功**：最终结论走
 *       {@link PublicWifiVerdict} 的七态；<\/li>
 *   <li>**不写已试集合**：公开 API 证明不了哪条口令是错的。<\/li>
 * </ul>
 *
 * <h3>输出什么</h3>
 * 请求 ID、回调顺序、Network handle、SSID/BSSID、地址、耗时、最终七态 ——
 * 每一条都是真实回调里记下来的，没有推断。
 *
 * <p>入口：设置页「公开 API 验证」按钮（或 adb 启动本 Activity）。
 */
public class PublicApiProbeActivity extends Activity {

    /** adb 启动：{@code am start -n io.github.wlmosv_png.wpaforge/.PublicApiProbeActivity} */
    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_SECURITY = "security";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView log;
    private Button runBtn;
    private EditText ssidIn;
    private EditText passIn;
    private EditText secIn;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(L.s("公开 API 验证", "Public API probe"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad, pad, pad);

        // 说明
        TextView hint = new TextView(this);
        hint.setText(L.s(
                "这个页面只做一次验证：输入目标 SSID 与一条口令，点「验证一次」。\n"
                        + "系统会弹出「连接到该网络？」确认框，请按真实情况点允许或拒绝。\n"
                        + "不会后台循环尝试，也不会写入「已试密码」。",
                "This page verifies exactly once: enter the target SSID and one passphrase, "
                        + "then tap Verify. The system will show a connect prompt; answer it "
                        + "honestly. No background looping, no tried-set writes."));
        hint.setTextSize(io.github.wlmosv_png.wpaforge.ui.AmberTheme.FS_MONO);
        root.addView(hint);

        ssidIn = field(L.s("SSID（目标网络名）", "SSID (target)"),
                b == null ? "" : b.getString(EXTRA_SSID, ""));
        passIn = field(L.s("口令（只验证这一条）", "Passphrase (one only)"),
                b == null ? "" : b.getString(EXTRA_PASSWORD, ""));
        passIn.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secIn = field(L.s("加密方式（wpa2 / wpa3 / open）", "Security (wpa2 / wpa3 / open)"),
                b == null ? "wpa2" : b.getString(EXTRA_SECURITY, "wpa2"));

        runBtn = new Button(this);
        runBtn.setText(L.s("验证一次", "Verify once"));
        runBtn.setOnClickListener(v -> runOnce());
        root.addView(runBtn);

        log = new TextView(this);
        log.setTextSize(io.github.wlmosv_png.wpaforge.ui.AmberTheme.FS_LOG);
        log.setTextIsSelectable(true);
        log.setText(L.s("（尚未开始）", "(not started)"));
        ScrollView sc = new ScrollView(this);
        sc.addView(log);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // 支持 adb 直接带参数启动（便于脚本化取证）
        if (b != null && b.getString(EXTRA_SSID) != null
                && b.getString(EXTRA_PASSWORD) != null) {
            ui.postDelayed(this::runOnce, 500L);
        }
    }

    private EditText field(String hintText, String value) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setText(value);
        e.setTextSize(io.github.wlmosv_png.wpaforge.ui.AmberTheme.FS_ACTION);
        e.setSingleLine(true);
        e.setGravity(Gravity.START);
        return e;
    }

    /**
     * **执行一次验证**（这是本页唯一会碰网络的动作）。
     *
     * <p>刻意做成「一次点击 = 一次请求」：没有循环、没有队列、没有重试。
     */
    private void runOnce() {
        if (running) {
            append(L.s("上一次还没结束，请等它返回。", "previous run still in flight."));
            return;
        }
        final String ssid = ssidIn.getText().toString().trim();
        final String pass = passIn.getText().toString();
        final String sec = secIn.getText().toString().trim();
        if (ssid.isEmpty()) {
            append(L.s("SSID 不能为空。", "SSID must not be empty."));
            return;
        }
        if (pass.isEmpty()) {
            append(L.s("口令不能为空（本页只验证你填的这一条）。",
                    "Passphrase must not be empty (this page verifies only what you type)."));
            return;
        }

        running = true;
        runBtn.setEnabled(false);
        log.setText("");
        append(L.s("开始验证：SSID=", "start: SSID=") + ssid
                + L.s(" 口令长度=", " passLen=") + pass.length()
                + L.s(" 加密=", " sec=") + sec);
        append(L.s("（系统马上会弹确认框，请按真实情况点）",
                "(the system prompt will appear; answer it honestly)"));
        append("");

        new Thread(() -> {
            PublicWifiProbe.Record r = PublicWifiProbe.probeOnce(
                    PublicApiProbeActivity.this, ssid, "", sec, pass,
                    new PublicWifiProbe.Listener() {
                        @Override
                        public void onEvent(String event) {
                            ui.post(() -> append("  · " + event));
                        }

                        @Override
                        public void onDone(PublicWifiProbe.Record rec) {
                            // 由下面的主线程统一输出，避免重复
                        }
                    });
            ui.post(() -> report(r));
        }, "public-api-probe").start();
    }

    /** 把完整证据链打到界面上（也写进启动日志文件，便于取回）。 */
    private void report(PublicWifiProbe.Record r) {
        running = false;
        runBtn.setEnabled(true);

        append("");
        append("──── " + L.s("结果", "result") + " ────");
        append(L.s("请求 ID: ", "requestId: ") + r.requestId);
        append(L.s("回调顺序: ", "callback order: ")
                + (r.callbackOrder.isEmpty() ? "<none>" : r.callbackOrder));
        append(L.s("耗时: ", "elapsed: ") + r.elapsedMs() + "ms");
        append("");
        append(L.s("原始事实", "raw facts") + ":");
        append("  requestAccepted = " + r.requestAccepted);
        append("  sawAvailable    = " + r.sawAvailable);
        append("  sawCapabilities = " + r.sawCapabilities);
        append("  sawLinkProps    = " + r.sawLinkProperties);
        append("  sawUnavailable  = " + r.sawUnavailable);
        append("  sawLost         = " + r.sawLost);
        append("  lostBeforeAvail = " + r.sawLostBeforeAvailable);
        append("  droppedInStable = " + r.droppedWithinStability);
        append("  networkHandle   = " + r.networkHandle);
        append("  networkPreexist = " + r.networkPreexisting);
        append("  preConnected    = " + r.preConnectedToTarget);
        append("");
        append(L.s("连接证据", "connection evidence") + ":");
        append("  hasTransportInfo = " + r.hasTransportInfo);
        append("  transportSsid    = " + (r.transportSsid.isEmpty()
                ? "<none>" : r.transportSsid)
                + L.s("（与目标一致: ", " (matches: ") + r.transportSsidMatches + "）");
        append("  transportBssid   = " + (r.transportBssid.isEmpty()
                ? "<none>" : r.transportBssid));
        append("  hasValidAddress  = " + r.hasValidAddress
                + (r.addressBrief.isEmpty() ? "" : " (" + r.addressBrief + ")"));
        append("");
        append(L.s("资源释放: ", "released: ") + r.released);
        append("");
        append("──── " + L.s("判定", "verdict") + " ────");
        append(L.s("状态: ", "state: ") + r.state.label(L.isZh())
                + "  (" + r.state.key + ")");
        append(L.s("允许 DIRECT: ", "allows DIRECT: ") + r.state.allowsDirect());
        append(L.s("证据等级: ", "grade: ") + PublicWifiVerdict.gradeOf(r.state));
        append(L.s("写已试集合: ", "writes tried-set: ")
                + PublicWifiVerdict.allowsTriedSet(r.state));
        append("");
        append(L.s("解释: ", "explain: ") + PublicWifiVerdict.explain(r.state, L.isZh()));
        if (!r.reason.isEmpty()) {
            append(L.s("原因码: ", "reason: ") + r.reason);
        }
        if (!r.detail.isEmpty()) {
            append(L.s("详情: ", "detail: ") + r.detail);
        }
        append("");
        append("──── " + L.s("一行摘要（可复制给开发者）", "one-line summary") + " ────");
        append(r.brief(L.isZh()));
    }

    private void append(String line) {
        log.append(line + "\n");
    }
}
