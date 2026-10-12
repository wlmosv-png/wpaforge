package io.github.wlmosv_png.wpaforge.scan;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网扫描快照存储。
 *
 * <h3>为什么需要「快照 + 对比」</h3>
 * 一次扫描的结果看完就没了,而共享网(宿舍 / 办公)真正的价值在于
 * **「今天多了谁」** —— 平时在的设备不用管,新冒出来的才要看。
 * 所以每次扫描存一份快照,下次扫完自动和上次比:
 *
 *   + 新出现    ← 重点标注(可能是陌生设备)
 *   - 消失了
 *   = 一直在    ← 折叠,不占视线
 *
 * <h3>存储</h3>
 * 存成 JSON,放应用私有目录(不需要外部权限)。
 * 只保留最近 N 份,避免无限增长。
 */
public final class ProbeHistory {

    private static final String FILE = "lan_probe_history.json";
    private static final int MAX_SNAPSHOTS = 20;

    /** 一份快照 */
    public static final class Snapshot {
        public final long timeMs;
        /** 按 IP 索引的设备(MAC 与厂商) */
        public final Map<String, Dev> devices;

        public Snapshot(long t, Map<String, Dev> d) {
            this.timeMs = t;
            this.devices = d == null ? new LinkedHashMap<>() : d;
        }
    }

    /** 一台设备的持久身份 */
    public static final class Dev {
        public final String ip;
        public final String mac;
        public final String vendor;

        public Dev(String ip, String mac, String vendor) {
            this.ip = ip == null ? "" : ip;
            this.mac = mac == null ? "" : mac.toUpperCase();
            this.vendor = vendor == null ? "" : vendor;
        }

        /**
         * 稳定标识:优先 MAC（IP 会变,DHCP 重发就换),没 MAC 才用 IP。
         * 这样「IP 变了但设备没变」不会被误报成新设备。
         */
        public String key() {
            return mac.isEmpty() ? ("ip:" + ip) : ("mac:" + mac);
        }
    }

    /** 对比结果 */
    public static final class Diff {
        public final List<Dev> added = new ArrayList<>();     // 本次新出现
        public final List<Dev> removed = new ArrayList<>();   // 本次消失
        public final List<Dev> kept = new ArrayList<>();      // 一直在

        public boolean hasBaseline = false;   // 有没有可比的上一份
    }

    private ProbeHistory() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 读取全部快照(旧 -> 新) */
    public static List<Snapshot> loadAll(Context ctx) {
        List<Snapshot> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                long t = o.optLong("t");
                JSONArray ds = o.optJSONArray("devices");
                Map<String, Dev> m = new LinkedHashMap<>();
                if (ds != null) {
                    for (int j = 0; j < ds.length(); j++) {
                        JSONObject d = ds.getJSONObject(j);
                        Dev dev = new Dev(d.optString("ip"), d.optString("mac"), d.optString("vendor"));
                        m.put(dev.key(), dev);
                    }
                }
                out.add(new Snapshot(t, m));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 最近一份快照,没有返回 null */
    public static Snapshot latest(Context ctx) {
        List<Snapshot> all = loadAll(ctx);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * 保存本次快照(追加到尾部),并裁到 MAX_SNAPSHOTS。
     * 返回保存前的「上一份」,供调用方做对比 —— 省一次读盘。
     */
    public static Snapshot save(Context ctx, List<LanProbe.Host> hosts) {
        List<Snapshot> all = loadAll(ctx);
        Snapshot prev = all.isEmpty() ? null : all.get(all.size() - 1);

        Map<String, Dev> m = new LinkedHashMap<>();
        for (LanProbe.Host h : hosts) {
            Dev d = new Dev(h.ip, h.mac, h.vendor);
            m.put(d.key(), d);
        }
        all.add(new Snapshot(System.currentTimeMillis(), m));
        while (all.size() > MAX_SNAPSHOTS) all.remove(0);

        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file(ctx)), StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONArray();
            for (Snapshot s : all) {
                JSONObject o = new JSONObject();
                o.put("t", s.timeMs);
                JSONArray ds = new JSONArray();
                for (Dev d : s.devices.values()) {
                    JSONObject jd = new JSONObject();
                    jd.put("ip", d.ip);
                    jd.put("mac", d.mac);
                    jd.put("vendor", d.vendor);
                    ds.put(jd);
                }
                o.put("devices", ds);
                arr.put(o);
            }
            w.write(arr.toString());
        } catch (Throwable ignored) {
        }
        return prev;
    }

    /**
     * 对比本次结果与上一份快照。
     * 没有上一份时 hasBaseline=false(首次扫描,全部算「已知」不报警)。
     */
    public static Diff diff(Snapshot prev, List<LanProbe.Host> now) {
        Diff d = new Diff();
        Map<String, Dev> cur = new LinkedHashMap<>();
        for (LanProbe.Host h : now) {
            Dev dev = new Dev(h.ip, h.mac, h.vendor);
            cur.put(dev.key(), dev);
        }
        if (prev == null) {
            // 首次:全部归入 kept,不做加减,避免第一次就满屏"新设备"
            d.kept.addAll(cur.values());
            return d;
        }
        d.hasBaseline = true;
        for (Map.Entry<String, Dev> e : cur.entrySet()) {
            if (prev.devices.containsKey(e.getKey())) d.kept.add(e.getValue());
            else d.added.add(e.getValue());
        }
        for (Map.Entry<String, Dev> e : prev.devices.entrySet()) {
            if (!cur.containsKey(e.getKey())) d.removed.add(e.getValue());
        }
        return d;
    }

    /** 清空历史 */
    public static void clear(Context ctx) {
        try { file(ctx).delete(); } catch (Throwable ignored) {}
    }
}
