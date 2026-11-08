package io.github.wlmosv_png.wpaforge.wps;

/**
 * WPS 专项统计的落盘层（原子写 + 备份 + 坏数据容错）。
 *
 * <h3>为什么单独一个文件而不是塞进台账</h3>
 * WpsLedger 是**任务级**历史；统计是需要随每次尝试实时累加的**会话级汇总**。
 * 两者语义不同，分开落盘避免互相污染 —— 统计可以清零重建，台账不能。
 *
 * <h3>原子写（与台账同模式）</h3>
 * <pre>
 *   1) 全量写 file.tmp
 *   2) 现有 file → file.bak
 *   3) file.tmp → renameTo(file)
 * </pre>
 * 读：主文件 → 备份 → 空统计；坏数据按 {@code decode} 的「绝不抛、返回空统计」处理。
 *
 * <p>纯逻辑 + {@link java.io.File}，可离线测试（用临时目录）。
 */
public final class WpsStatsStore {

    private final java.io.File file;

    /** 传 null 时安全降级（{@link #load} 返回空统计）。 */
    public WpsStatsStore(java.io.File file) {
        this.file = file;
    }

    /** 读统计；无文件/坏数据 → 空统计（绝不抛）。 */
    public WpsStats load() {
        if (file == null || !file.isFile()) {
            java.io.File bak = bakFile();
            if (bak != null && bak.isFile()) {
                return safeDecode(bak);
            }
            return new WpsStats();
        }
        WpsStats s = safeDecode(file);
        if (s.empty()) {
            java.io.File bak = bakFile();
            if (bak != null && bak.isFile()) {
                WpsStats bs = safeDecode(bak);
                if (!bs.empty()) return bs;
            }
        }
        return s;
    }

    /** 原子保存整份统计。 */
    public boolean save(WpsStats s) {
        if (file == null || s == null) return false;
        try {
            java.io.File tmp = new java.io.File(file.getPath() + ".tmp");
            java.io.File bak = bakFile();
            java.io.File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;

            // 1) 写 tmp
            String enc = s.encode();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            try {
                fos.write(enc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }

            // 2) 现有 → bak
            if (file.isFile()) {
                java.nio.file.Files.copy(file.toPath(), bak.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // 3) tmp → file
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- internals ----------------

    private java.io.File bakFile() {
        return file == null ? null : new java.io.File(file.getPath() + ".bak");
    }

    private static WpsStats safeDecode(java.io.File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            WpsStats s = WpsStats.decode(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            return s == null ? new WpsStats() : s;
        } catch (Throwable t) {
            return new WpsStats();
        }
    }
}