package io.github.wlmosv_png.wpaforge.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据链 —— 让每个结论都能回答「你凭什么这么说」。
 *
 * <h3>为什么需要</h3>
 * 工具里到处都是判断：这台设备厂商是水星、这个端口开着、这个密码正确。
 * 但界面上只呈现**结论**，不呈现**依据**。用户没法验证，也没法判断可信度。
 *
 * 而 WPAForge 恰恰在「准确」上投入很多（假阳性加固、canary 校验、
 * 判定链四条件同源），这些工作如果不可见，等于白做。
 *
 * 所以把依据摊开：每个结论挂一串依据，每条依据标明来源与是否决定性。
 * 用户可以展开看，也可以据此反驳。
 *
 * <h3>设计原则</h3>
 * · 依据必须是**可复现的事实**（读到的原始值、执行的命令），不是二次推断
 * · 明确区分「决定性证据」与「辅助证据」—— 前者能单独支撑结论
 * · 不做花哨的置信度打分：那是伪精确，用户看不懂也不该信
 */
public final class Evidence {

    /** 单条依据 */
    public static final class Item {
        /** 来源标签，如 "OUI 表" / "cmd wifi status" / "TCP connect" */
        public final String source;
        /** 具体内容，如 "28:93:7D → 四川天邑康和" */
        public final String detail;
        /** 是否决定性证据（能单独支撑结论） */
        public final boolean decisive;

        public Item(String source, String detail, boolean decisive) {
            this.source = source == null ? "" : source;
            this.detail = detail == null ? "" : detail;
            this.decisive = decisive;
        }

        /** 终端/报告里一行 */
        public String line() {
            return (decisive ? "● " : "○ ") + source + "  " + detail;
        }
    }

    /** 一条结论 + 它的依据 */
    public static final class Claim {
        public final String statement;          // 结论本身
        public final List<Item> items = new ArrayList<>();

        public Claim(String statement) {
            this.statement = statement == null ? "" : statement;
        }

        public Claim add(String source, String detail, boolean decisive) {
            items.add(new Item(source, detail, decisive));
            return this;
        }

        /** 直接追加一个已构造好的依据（快捷工厂的返回值） */
        public Claim add(Item it) {
            if (it != null) items.add(it);
            return this;
        }

        /** 有几条决定性证据 */
        public int decisiveCount() {
            int n = 0;
            for (Item i : items) if (i.decisive) n++;
            return n;
        }

        /**
         * 结论是否成立。
         *
         * 判定规则很简单也很刻意：**至少要有一条决定性证据**。
         * 没有决定性证据的结论不该被展示成事实 —— 宁可标成「推测」。
         */
        public boolean supported() {
            return decisiveCount() > 0;
        }

        /** 可信度标签（用于界面角标） */
        public String confidenceLabel() {
            if (!supported()) return "推测";
            return decisiveCount() >= 2 ? "确认" : "判定";
        }
    }

    private Evidence() {}

    /* ============ 常见依据的构造快捷方式 ============ */

    public static Item ouiHit(String mac, String vendor) {
        return new Item("OUI 表", mac + " → " + vendor, true);
    }

    public static Item ouiMissing(String mac) {
        return new Item("OUI 表", mac + " 无匹配（15913 条中未收录）", false);
    }

    public static Item randomizedMac(String mac) {
        return new Item("MAC 位判定", mac + " 首字节 bit1=1 → 本地管理位（随机化地址）", true);
    }

    public static Item pingReply(String ip, String rttHint) {
        return new Item("ICMP", ip + " 有回应" + (rttHint == null ? "" : "（" + rttHint + "）"), true);
    }

    public static Item arpEntry(String ip, String mac) {
        return new Item("/proc/net/arp", ip + " → " + mac + "（内核邻居表）", true);
    }

    public static Item tcpOpen(String ip, int port) {
        return new Item("TCP connect", ip + ":" + port + " 三次握手成功", true);
    }

    public static Item banner(String ip, int port, String text) {
        return new Item("服务响应", ip + ":" + port + " 回 `" + text + "`", true);
    }

    public static Item canaryFailed(String target) {
        return new Item("canary 探测",
            target + " 也报 open —— 说明有东西对任意目标代答，端口结论不可信", true);
    }

    /**
     * 生成整份证据文本（给「导出报告」和「长按查看」用）。
     */
    public static String render(List<Claim> claims) {
        StringBuilder sb = new StringBuilder();
        for (Claim c : claims) {
            sb.append("[").append(c.confidenceLabel()).append("] ").append(c.statement).append('\n');
            for (Item i : c.items) {
                sb.append("    ").append(i.line()).append('\n');
            }
        }
        return sb.toString();
    }
}
