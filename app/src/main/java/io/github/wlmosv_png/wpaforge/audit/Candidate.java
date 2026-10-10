package io.github.wlmosv_png.wpaforge.audit;

/**
 * 一条候选口令。带来源标记，方便审计报告里说明"命中来自哪条规则"。
 */
public final class Candidate {

    public enum Origin {
        DIGITS,        // 纯数字规律
        LETTERS,       // 纯字母
        ALNUM,         // 字母数字混排
        YEAR_SUFFIX,   // 词 + 年份
        PHONE_SEGMENT, // 手机号段
        VENDOR_RULE,   // 厂商默认规则推导
        USER_DICT      // 用户自备词表
    }

    public final String password;
    public final Origin origin;

    public Candidate(String password, Origin origin) {
        this.password = password;
        this.origin = origin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Candidate)) return false;
        Candidate c = (Candidate) o;
        return password.equals(c.password);
    }

    @Override
    public int hashCode() {
        return password.hashCode();
    }

    @Override
    public String toString() {
        return password + "  <" + origin + ">";
    }
}
