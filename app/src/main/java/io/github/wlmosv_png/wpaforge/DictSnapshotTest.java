package io.github.wlmosv_png.wpaforge;

import io.github.wlmosv_png.wpaforge.audit.Candidate;
import io.github.wlmosv_png.wpaforge.audit.DictSnapshot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 字典任务快照：条数口径、两个指纹、脱节检测、编解码往返。
 *
 * <h3>要钉死的事情</h3>
 * <ul>
 *   <li>条数只由候选列表本身决定，**不接受自报**；</li>
 *   <li>内容指纹对顺序敏感、对无关内容不敏感；两份不同内容必须不同；</li>
 *   <li>{@code matches()} 是启动前的最后一道闸，改过一份就必须判 false；</li>
 *   <li>编解码往返后所有字段与指纹都不变（应用重开后靠它回答"上次用的哪本"）。</li>
 * </ul>
 */
public class DictSnapshotTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    private static void eq(String name, Object got, Object want) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   got=" + got + "  want=" + want);
        }
    }

    private static List<Candidate> list(String... pws) {
        List<Candidate> d = new ArrayList<Candidate>();
        for (String p : pws) d.add(new Candidate(p, Candidate.Origin.USER_DICT));
        return d;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 条数口径：只认候选列表本身 ==");
        List<Candidate> a = list("alpha", "bravo", "charlie");
        DictSnapshot sa = DictSnapshot.of("A", DictSnapshot.SRC_PROFILE, "", a);
        eq("count = 列表长度", sa.count, 3L);
        ok("自洽", sa.consistent());
        ok("present", sa.present());
        ok("未落盘 -> onDisk=false", !sa.onDisk());
        eq("未落盘 -> fileId 为空", sa.fileId(), "");

        System.out.println("== 空字典 ==");
        DictSnapshot sn = DictSnapshot.none();
        ok("none 是空的", sn.empty());
        ok("none 自洽", sn.consistent());
        eq("none 的内容指纹为空", sn.contentSha256, "");
        DictSnapshot s0 = DictSnapshot.of("空", DictSnapshot.SRC_FILE, "", new ArrayList<Candidate>());
        ok("空列表 -> empty", s0.empty());
        eq("空列表 -> count 0", s0.count, 0L);
        ok("空列表自洽", s0.consistent());
        ok("空快照 matches 空列表", s0.matches(new ArrayList<Candidate>()));

        System.out.println("== 内容指纹 ==");
        List<Candidate> b = list("alpha", "bravo", "charlie");
        DictSnapshot sb = DictSnapshot.of("B", DictSnapshot.SRC_PROFILE, "", b);
        eq("同内容同指纹", sb.contentSha256, sa.contentSha256);
        eq("指纹长度 64", sa.contentSha256.length(), 64);
        eq("显示指纹 12 位大写", sa.fingerprint().length(), 12);
        eq("显示指纹是大写十六进制", sa.fingerprint(), sa.fingerprint().toUpperCase());

        List<Candidate> c = list("charlie", "bravo", "alpha");
        DictSnapshot sc = DictSnapshot.of("C", DictSnapshot.SRC_PROFILE, "", c);
        ok("顺序不同 -> 指纹不同（顺序就是执行顺序）",
                !sc.contentSha256.equals(sa.contentSha256));
        List<Candidate> d = list("alpha", "bravo", "charlie2");
        ok("内容不同 -> 指纹不同",
                !DictSnapshot.of("D", "", "", d).contentSha256.equals(sa.contentSha256));
        ok("名字不同不影响指纹",
                DictSnapshot.of("X", DictSnapshot.SRC_FILE, "/tmp/x", a).contentSha256
                        .equals(sa.contentSha256));

        System.out.println("== matches()：脱节检测的最后一道闸 ==");
        ok("同列表 matches", sa.matches(a));
        ok("同内容新列表 matches", sa.matches(list("alpha", "bravo", "charlie")));
        ok("多一条就不 matches", !sa.matches(list("alpha", "bravo", "charlie", "delta")));
        ok("少一条就不 matches", !sa.matches(list("alpha", "bravo")));
        ok("内容改了就不 matches", !sa.matches(list("alpha", "bravo", "CHARLIE")));
        ok("null 不 matches", !sa.matches(null));
        List<Candidate> tampered = list("alpha", "bravo", "charlie");
        tampered.add(new Candidate("delta", Candidate.Origin.USER_DICT));
        ok("同一个列表被追加后也不再 matches", !sa.matches(tampered));

        System.out.println("== 文件指纹 ==");
        File dir = new File(System.getProperty("java.io.tmpdir"), "wpf-dictsnap-" + System.nanoTime());
        ok("建目录", dir.mkdirs());
        File f = new File(dir, "d.txt");
        writeLn(f, "alpha\nbravo\ncharlie\n");
        DictSnapshot sf = DictSnapshot.of("F", DictSnapshot.SRC_FILE, f.getAbsolutePath(),
                list("alpha", "bravo", "charlie"));
        eq("文件指纹长度 64", sf.fileSha256.length(), 64);
        ok("有文件 -> onDisk", sf.onDisk());
        eq("文件 id 12 位", sf.fileId().length(), 12);
        ok("路径不存在时文件指纹为空",
                DictSnapshot.sha256OfFile("/definitely/not/here.txt").isEmpty());
        ok("空路径 -> 空指纹", DictSnapshot.sha256OfFile("").isEmpty());

        writeLn(f, "alpha\nbravo\ncharlie\nDELTA\n");
        String after = DictSnapshot.sha256OfFile(f.getAbsolutePath());
        ok("文件被改动 -> 文件指纹改变", !after.equals(sf.fileSha256));
        ok("文件改动了，但内容指纹仍对应原始候选（两个指纹各管一件事）",
                sf.matches(list("alpha", "bravo", "charlie")));

        System.out.println("== 编解码往返（应用重开后靠它）==");
        DictSnapshot back = DictSnapshot.decode(sf.encode());
        eq("名字", back.name, sf.name);
        eq("来源", back.source, sf.source);
        eq("路径", back.filePath, sf.filePath);
        eq("文件指纹", back.fileSha256, sf.fileSha256);
        eq("内容指纹", back.contentSha256, sf.contentSha256);
        eq("条数", back.count, sf.count);
        eq("时间", back.createdAt, sf.createdAt);
        ok("往返后自洽", back.consistent());
        ok("往返后 matches 仍然成立",
                back.matches(list("alpha", "bravo", "charlie")));

        System.out.println("== 编解码的脏数据一律退回 none（不抛）==");
        ok("空串 -> none", DictSnapshot.decode("").empty());
        ok("null -> none", DictSnapshot.decode(null).empty());
        ok("乱码 -> none", DictSnapshot.decode("hello world").empty());
        ok("旧格式 -> none", DictSnapshot.decode("v1|a|b").empty());

        System.out.println("== 字段里的分隔符与换行必须被转义 ==");
        List<Candidate> e = list("p|1", "p2");
        DictSnapshot se = DictSnapshot.of("na|me\nx", DictSnapshot.SRC_PROFILE, "p|ath", e);
        ok("含分隔符也自洽", se.consistent());
        DictSnapshot se2 = DictSnapshot.decode(se.encode());
        eq("含分隔符的名字往返", se2.name, se.name);
        eq("含分隔符的路径往返", se2.filePath, se.filePath);
        eq("含分隔符的内容指纹往返", se2.contentSha256, se.contentSha256);
        ok("含分隔符往返后 matches", se2.matches(e));

        System.out.println("== 不自洽的快照必须被 consistent() 挡下 ==");
        ok("count>0 但没内容指纹 -> 不自洽",
                !new DictSnapshot("n", "s", "", "", "", 5, 1).consistent());
        ok("有文件指纹但没路径 -> 不自洽",
                !new DictSnapshot("n", "s", "", "ab" + pad(62), hash64(), 5, 1).consistent());
        ok("指纹长度不对 -> 不自洽",
                !new DictSnapshot("n", "s", "", "", "short", 5, 1).consistent());
        ok("count=0 有内容指纹 -> 不自洽",
                !new DictSnapshot("n", "s", "", "", hash64(), 0, 1).consistent());

        System.out.println();
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static String pad(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('a');
        return sb.toString();
    }

    private static String hash64() {
        return pad(64);
    }

    private static void writeLn(File f, String body) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(body);
        } finally {
            w.close();
        }
    }
}
