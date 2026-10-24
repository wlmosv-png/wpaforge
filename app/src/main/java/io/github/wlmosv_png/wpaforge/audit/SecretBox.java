package io.github.wlmosv_png.wpaforge.audit;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感串的封装（Android Keystore，AES-256-GCM）—— 用来保存“恢复所必需的原口令”。
 *
 * <h3>为什么必须加密而不是明文</h3>
 * 恢复记录里要存目标网络**原来的口令**，否则任务跑完就无法把配置还原回去。
 * 但把它明文写进应用私有目录，等于凭空制造一个新的明文口令落点 ——
 * 与“日志/历史/队列不落明文”的既定决策直接冲突。所以：
 * <ul>
 *   <li>密钥由 Android Keystore 生成并保管，**不出硬件/TEE 边界**，
 *       应用自身也无法导出；</li>
 *   <li>落盘只有 IV + 密文，换台设备 / 改了应用签名都解不开；</li>
 *   <li>解不出时**绝不退回明文**，而是返回 {@code null} 并让上层如实报告
 *       “原口令无法还原”（见 {@link RestorePlan#NO_SECRET}）。</li>
 * </ul>
 *
 * <h3>默认实现为什么不做事</h3>
 * 默认 {@link Impl} 两个方向都返回 {@code null} —— 意思是“本进程没有可用的
 * 加密通道”。这是**最保守**的默认值：宁可恢复不了（可见地失败），
 * 也不允许任何调用方在没装加密通道时把明文写下去。
 * 生产由 {@code App} / {@code CrackService} 在启动时 {@link #install} 真实现。
 *
 * <p>本类在桌面 JVM 上可加载：默认实现不引用任何 Android 类。
 */
public final class SecretBox {

    /** 可替换的加密实现（测试用恒等/可逆桩，生产用 Keystore）。 */
    public interface Impl {
        /** 加密；不可用或失败返回 {@code null}（**绝不允许退回明文**）。 */
        String seal(String plain);
        /** 解密；失败返回 {@code null}。 */
        String open(String sealed);
    }

    /** 默认：没有加密通道 → 什么都不存。 */
    private static final Impl NONE = new Impl() {
        @Override public String seal(String plain) { return null; }
        @Override public String open(String sealed) { return null; }
    };

    private static volatile Impl impl = NONE;

    private SecretBox() {}

    /** 安装加密实现（幂等）。传 {@code null} 等于装回 NONE。 */
    public static void install(Impl i) {
        impl = (i == null) ? NONE : i;
    }

    /** 当前是否有可用的加密通道。 */
    public static boolean available() {
        return impl != NONE;
    }

    /**
     * 加密。
     *
     * @return 形如 {@code v1:<b64(iv)>:<b64(ct)>}；失败/不可用返回 {@code null}
     */
    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try { return impl.seal(plain); } catch (Throwable t) { return null; }
    }

    /** 解密；失败返回 {@code null}。 */
    public static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return null;
        try { return impl.open(sealed); } catch (Throwable t) { return null; }
    }

    /**
     * 加解密往返自检（P2-8）。
     *
     * <p>为什么需要它：{@code seal()} 的可用性可以从「落盘密文非空」间接看出，
     * 但 {@code open()} 从来没有在真机上被执行过 —— 而**恢复原配置**恰恰依赖它。
     * 这里用应用自己生成的**合成串**（不是任何真实口令）走一遍
     * seal → open，只回一个布尔值：调用方（启动自检）把结论写进日志，
     * 不打印密钥、不打印密文、不落盘。
     *
     * @return true = 加解密通道可用且往返一致
     */
    public static boolean roundTrip(String probe) {
        if (probe == null || probe.isEmpty()) return false;
        try {
            String sealed = seal(probe);
            if (sealed == null || sealed.contains(probe)) return false;
            return probe.equals(open(sealed));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自检用的合成探针（**不是**任何真实口令，也不落盘）。 */
    public static String selfTestProbe() {
        return "wpaforge-selftest-" + Long.toHexString(System.nanoTime());
    }

    /** 测试用：可逆的“假加密”（带前缀，明确不是真密文，避免误当成安全实现）。 */
    public static Impl reversibleStub() {
        return new Impl() {
            @Override public String seal(String plain) {
                if (plain == null) return null;
                return "stub:" + Base64.getEncoder().encodeToString(
                        plain.getBytes(StandardCharsets.UTF_8));
            }
            @Override public String open(String sealed) {
                if (sealed == null || !sealed.startsWith("stub:")) return null;
                try {
                    return new String(Base64.getDecoder().decode(
                            sealed.substring("stub:".length())), StandardCharsets.UTF_8);
                } catch (Throwable t) { return null; }
            }
        };
    }

    // ------------------------------------------------------------ Keystore 实现

    private static final String ALIAS = "wpaforge.rec.secret.v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    /**
     * 构造设备侧实现。任何一步失败都返回 {@link #NONE} 等价的实现
     * （即“不可用”），不会抛异常打断调用方。
     */
    public static Impl device(Context ctx) {
        final SecretKey key = loadOrCreateKey();
        if (key == null) return NONE;
        return new Impl() {
            @Override public String seal(String plain) {
                try {
                    Cipher c = Cipher.getInstance(TRANSFORM);
                    c.init(Cipher.ENCRYPT_MODE, key);
                    byte[] iv = c.getIV();
                    byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                    Base64.Encoder e = Base64.getEncoder();
                    return "v1:" + e.encodeToString(iv) + ":" + e.encodeToString(ct);
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override public String open(String sealed) {
                if (sealed == null || !sealed.startsWith("v1:")) return null;
                String[] p = sealed.split(":", -1);
                if (p.length != 3) return null;
                try {
                    Base64.Decoder d = Base64.getDecoder();
                    byte[] iv = d.decode(p[1]);
                    byte[] ct = d.decode(p[2]);
                    if (iv.length != IV_LEN) return null;
                    Cipher c = Cipher.getInstance(TRANSFORM);
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                    return new String(c.doFinal(ct), StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    return null;
                }
            }
        };
    }

    private static SecretKey loadOrCreateKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            java.security.Key existing = ks.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator kg = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                            | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return kg.generateKey();
        } catch (Throwable t) {
            return null;
        }
    }
}
