package io.github.wlmosv_png.wpaforge.audit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * WPA/WPA2-PSK 的密钥派生与 MIC 比对。
 *
 * 全部按 IEEE 802.11i 公开规范实现：
 *   PMK  = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 256 bit)
 *   PTK  = PRF-512(PMK, "Pairwise key expansion",
 *                  min(AA,SPA) || max(AA,SPA) || min(ANonce,SNonce) || max(ANonce,SNonce))
 *   MIC  = HMAC-SHA1(KCK, EAPOL frame with MIC zeroed)[0..16)
 *
 * 这套算法公开、无需联网、纯计算。本类不做任何包注入或网络交互。
 */
public final class WpaCore {

    private WpaCore() {}

    /** PBKDF2-HMAC-SHA1，纯手写避免依赖 javax.crypto 的 SecretKeyFactory 实现差异 */
    public static byte[] pbkdf2HmacSha1(byte[] password, byte[] salt, int iterations, int dkLen) {
        final int hLen = 20; // SHA1 输出
        int blocks = (dkLen + hLen - 1) / hLen;
        byte[] dk = new byte[blocks * hLen];
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);

        for (int i = 1; i <= blocks; i++) {
            block[salt.length]     = (byte) (i >>> 24);
            block[salt.length + 1] = (byte) (i >>> 16);
            block[salt.length + 2] = (byte) (i >>> 8);
            block[salt.length + 3] = (byte) i;

            byte[] u = hmacSha1(password, block);
            byte[] t = Arrays.copyOf(u, hLen);

            for (int iter = 1; iter < iterations; iter++) {
                u = hmacSha1(password, u);
                for (int k = 0; k < hLen; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, dk, (i - 1) * hLen, hLen);
        }
        return Arrays.copyOf(dk, dkLen);
    }

    public static byte[] hmacSha1(byte[] key, byte[] data) {
        final int blockSize = 64;
        byte[] k = key;
        if (k.length > blockSize) {
            k = sha1(k);
        }
        byte[] kPad = new byte[blockSize];
        System.arraycopy(k, 0, kPad, 0, k.length);

        byte[] iPad = new byte[blockSize];
        byte[] oPad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            iPad[i] = (byte) (kPad[i] ^ 0x36);
            oPad[i] = (byte) (kPad[i] ^ 0x5c);
        }
        byte[] inner = new byte[blockSize + data.length];
        System.arraycopy(iPad, 0, inner, 0, blockSize);
        System.arraycopy(data, 0, inner, blockSize, data.length);
        byte[] innerHash = sha1(inner);

        byte[] outer = new byte[blockSize + innerHash.length];
        System.arraycopy(oPad, 0, outer, 0, blockSize);
        System.arraycopy(innerHash, 0, outer, blockSize, innerHash.length);
        return sha1(outer);
    }

    /** 手写 SHA-1（RFC 3174），避免平台差异 */
    public static byte[] sha1(byte[] input) {
        long bitLen = (long) input.length * 8;
        int padLen = 64 - ((input.length + 9) % 64);
        if (padLen == 64) padLen = 0;
        byte[] msg = new byte[input.length + 1 + padLen + 8];
        System.arraycopy(input, 0, msg, 0, input.length);
        msg[input.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            msg[msg.length - 1 - i] = (byte) (bitLen >>> (8 * i));
        }

        int h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE,
            h3 = 0x10325476, h4 = 0xC3D2E1F0;

        int[] w = new int[80];
        for (int off = 0; off < msg.length; off += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((msg[off + i * 4] & 0xFF) << 24)
                     | ((msg[off + i * 4 + 1] & 0xFF) << 16)
                     | ((msg[off + i * 4 + 2] & 0xFF) << 8)
                     | (msg[off + i * 4 + 3] & 0xFF);
            }
            for (int i = 16; i < 80; i++) {
                int v = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
                w[i] = Integer.rotateLeft(v, 1);
            }
            int a = h0, b = h1, c = h2, d = h3, e = h4;
            for (int i = 0; i < 80; i++) {
                int f, k;
                if (i < 20)      { f = (b & c) | ((~b) & d);   k = 0x5A827999; }
                else if (i < 40) { f = b ^ c ^ d;              k = 0x6ED9EBA1; }
                else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
                else             { f = b ^ c ^ d;              k = 0xCA62C1D6; }
                int tmp = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d; d = c; c = Integer.rotateLeft(b, 30);
                b = a; a = tmp;
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e;
        }
        byte[] out = new byte[20];
        int[] h = {h0, h1, h2, h3, h4};
        for (int i = 0; i < 5; i++) {
            out[i * 4]     = (byte) (h[i] >>> 24);
            out[i * 4 + 1] = (byte) (h[i] >>> 16);
            out[i * 4 + 2] = (byte) (h[i] >>> 8);
            out[i * 4 + 3] = (byte) h[i];
        }
        return out;
    }

    /** 口令 -> PMK（32 字节） */
    public static byte[] derivePmk(String passphrase, String ssid) {
        byte[] pw   = passphrase.getBytes(StandardCharsets.UTF_8);
        byte[] salt = ssid.getBytes(StandardCharsets.UTF_8);
        return pbkdf2HmacSha1(pw, salt, 4096, 32);
    }

    /**
     * PMK + 四次握手材料 -> PTK（64 字节）。
     * @param pmk      32 字节
     * @param apMac    6 字节（Authenticator / AA）
     * @param staMac   6 字节（Supplicant / SPA）
     * @param aNonce   32 字节
     * @param sNonce   32 字节
     */
    public static byte[] derivePtk(byte[] pmk, byte[] apMac, byte[] staMac,
                                   byte[] aNonce, byte[] sNonce) {
        byte[] data = new byte[76];
        // 地址按字典序取 min || max
        if (cmpBytes(apMac, staMac) < 0) {
            System.arraycopy(apMac, 0, data, 0, 6);
            System.arraycopy(staMac, 0, data, 6, 6);
        } else {
            System.arraycopy(staMac, 0, data, 0, 6);
            System.arraycopy(apMac, 0, data, 6, 6);
        }
        // Nonce 同理
        if (cmpBytes(aNonce, sNonce) < 0) {
            System.arraycopy(aNonce, 0, data, 12, 32);
            System.arraycopy(sNonce, 0, data, 44, 32);
        } else {
            System.arraycopy(sNonce, 0, data, 12, 32);
            System.arraycopy(aNonce, 0, data, 44, 32);
        }
        byte[] label = "Pairwise key expansion".getBytes(StandardCharsets.US_ASCII);
        return prf512(pmk, label, data);
    }

    /** PRF-512：标准 802.11i 基于 HMAC-SHA1 的伪随机函数 */
    private static byte[] prf512(byte[] key, byte[] label, byte[] data) {
        byte[] result = new byte[64];
        byte[] labelData = new byte[label.length + 1 + data.length];
        System.arraycopy(label, 0, labelData, 0, label.length);
        labelData[label.length] = 0x00;
        System.arraycopy(data, 0, labelData, label.length + 1, data.length);

        int produced = 0;
        int counter = 0;
        while (produced < 64) {
            byte[] input = new byte[labelData.length + 1];
            System.arraycopy(labelData, 0, input, 0, labelData.length);
            input[input.length - 1] = (byte) counter;

            byte[] hash = hmacSha1(key, input);
            int need = Math.min(hash.length, 64 - produced);
            System.arraycopy(hash, 0, result, produced, need);
            produced += need;
            counter++;
        }
        return result;
    }

    /**
     * 校验候选口令：给定握手的 KCK 与 EAPOL MIC 帧，
     * 算出候选 MIC 并比对。
     *
     * @param ptk        64 字节 PTK（KCK = ptk[0..16)，KEK = ptk[16..32)）
     * @param eapolFrame EAPOL 帧原始字节（MIC 字段已置零）
     * @param micOffset  MIC 在帧中的偏移
     * @param expectedMic 抓到的原始 MIC（16 字节）
     */
    public static boolean verifyMic(byte[] ptk, byte[] eapolFrame, int micOffset,
                                    byte[] expectedMic) {
        byte[] kck = Arrays.copyOfRange(ptk, 0, 16);
        byte[] frame = eapolFrame.clone();
        for (int i = 0; i < 16; i++) frame[micOffset + i] = 0;
        byte[] mic = hmacSha1(kck, frame);
        byte[] calc = Arrays.copyOf(mic, 16);
        return constantTimeEquals(calc, expectedMic);
    }

    /**
     * 单条候选的完整校验链：口令 -> PMK -> PTK -> MIC。
     * 握手材料不全时返回 false。
     */
    public static boolean checkCandidate(String passphrase, String ssid,
                                         byte[] apMac, byte[] staMac,
                                         byte[] aNonce, byte[] sNonce,
                                         byte[] eapolFrame, int micOffset,
                                         byte[] expectedMic) {
        try {
            byte[] pmk = derivePmk(passphrase, ssid);
            byte[] ptk = derivePtk(pmk, apMac, staMac, aNonce, sNonce);
            return verifyMic(ptk, eapolFrame, micOffset, expectedMic);
        } catch (Exception e) {
            return false;
        }
    }

    private static int cmpBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (x != 0) return x;
        }
        return a.length - b.length;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    /** 十六进制串 -> 字节，失败返回 null */
    /**
     * PMKID = HMAC-SHA1-128(PMK, "PMK Name" || AP_MAC || STA_MAC)
     *
     * <p>公式来自 IEEE 802.11 的 PMKID 定义（也是 hashcat/hcxtools 用的那条）。
     * 相比握手校验，这条路径**只需 PBKDF2 + 一次 HMAC** —— 不用派生 PTK，
     * 也不用算 MIC，所以单位时间能试的候选数接近两倍。
     *
     * @return 16 字节 PMKID；参数不合法返回 null
     */
    public static byte[] computePmkid(String passphrase, String ssid,
                                      byte[] apMac, byte[] staMac) {
        if (passphrase == null || ssid == null) return null;
        if (apMac == null || apMac.length != 6) return null;
        if (staMac == null || staMac.length != 6) return null;
        byte[] pmk = derivePmk(passphrase, ssid);
        if (pmk == null) return null;
        byte[] data = new byte[8 + 6 + 6];
        byte[] name = new byte[]{0x50, 0x4D, 0x4B, 0x20, 0x4E, 0x61, 0x6D, 0x65}; // "PMK Name"
        System.arraycopy(name, 0, data, 0, 8);
        System.arraycopy(apMac, 0, data, 8, 6);
        System.arraycopy(staMac, 0, data, 14, 6);
        byte[] mac = hmacSha1(pmk, data);
        if (mac == null || mac.length < 16) return null;
        byte[] out = new byte[16];
        System.arraycopy(mac, 0, out, 0, 16);
        return out;
    }

    /** 候选密码的 PMKID 是否与材料一致。 */
    public static boolean checkPmkid(String passphrase, String ssid,
                                     byte[] apMac, byte[] staMac, byte[] expected) {
        if (expected == null || expected.length != 16) return false;
        byte[] got = computePmkid(passphrase, ssid, apMac, staMac);
        if (got == null) return false;
        int diff = 0;
        for (int i = 0; i < 16; i++) diff |= got[i] ^ expected[i];
        return diff == 0;
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String h = hex.replace(":", "").replace("-", "").replace(" ", "");
        if (h.length() % 2 != 0) return null;
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(i * 2), 16);
            int lo = Character.digit(h.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
