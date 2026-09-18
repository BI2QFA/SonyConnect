import com.bi2qfa.sonyconnect.FtpServer;
import com.bi2qfa.sonyconnect.ThumbnailExtractor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;

/**
 * 桌面真验证（JDK8）：用真样本 DSC01781.ARW / DSC01779.JPG 跑完整链路。
 * 1) ThumbnailExtractor 提取的 4 段 JPEG 边界/长度断言（长度为交接文档实测值）
 * 2) EXIF 信息兜底读取
 * 3) FtpServer 虚拟路径 RETR/REST/SIZE、逃逸拦截、REST 无残留
 */
public class TestMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File arwSrc = new File("C:/Users/93849/Desktop/DSC01781.ARW");
        File jpgSrc = new File("C:/Users/93849/Desktop/DSC01779.JPG");
        if (!arwSrc.isFile() || !jpgSrc.isFile()) {
            System.out.println("[SKIP] 真样本缺失");
            System.exit(2);
        }

        // ===== 1. 提取器单元断言 =====
        byte[] arwSmall = ThumbnailExtractor.extractSmall(arwSrc);
        check("ARW 小图存在", arwSmall != null);
        check("ARW 小图 SOI", arwSmall != null && (arwSmall[0] & 0xFF) == 0xFF && (arwSmall[1] & 0xFF) == 0xD8);
        check("ARW 小图 EOI", arwSmall != null && arwSmall.length >= 2
                && (arwSmall[arwSmall.length - 2] & 0xFF) == 0xFF && (arwSmall[arwSmall.length - 1] & 0xFF) == 0xD9);
        check("ARW 小图长度=6349", arwSmall != null && arwSmall.length == 6349);

        byte[] arwPreview = ThumbnailExtractor.extractPreview(arwSrc);
        check("ARW 大预览长度=425597", arwPreview != null && arwPreview.length == 425597);

        byte[] jpgSmall = ThumbnailExtractor.extractSmall(jpgSrc);
        check("JPG 小图长度=7479", jpgSmall != null && jpgSmall.length == 7479);

        byte[] jpgPreview = ThumbnailExtractor.extractPreview(jpgSrc);
        check("JPG 大预览长度=375099", jpgPreview != null && jpgPreview.length == 375099);

        ThumbnailExtractor.ExifInfo ai = ThumbnailExtractor.readInfo(arwSrc);
        check("ARW EXIF make=SONY", ai != null && "SONY".equals(ai.make));
        check("ARW EXIF model=ILCE-6300", ai != null && "ILCE-6300".equals(ai.model));
        check("ARW EXIF 镜头名", ai != null && ai.lens != null && ai.lens.contains("OSS"));
        System.out.println("       （ARW 镜头=" + ai.lens
                + "，bodySerial=" + (ai.bodySerial == null ? "无（本机不写 0xA431）" : ai.bodySerial) + "）");

        ThumbnailExtractor.ExifInfo ji = ThumbnailExtractor.readInfo(jpgSrc);
        check("JPG EXIF model=ILCE-6300", ji != null && "ILCE-6300".equals(ji.model));
        check("JPG EXIF 镜头名", ji != null && ji.lens != null && ji.lens.contains("OSS"));

        // ===== 2. FTP 集成 =====
        File root = Files.createTempDirectory("sc-test-root").toFile();
        File dcim = new File(root, "DCIM/100MSDCF");
        dcim.mkdirs();
        File arwIn = new File(dcim, "DSC01781.ARW");
        File jpgIn = new File(dcim, "DSC01779.JPG");
        copy(arwSrc, arwIn);
        copy(jpgSrc, jpgIn);

        FtpServer server = new FtpServer(root, 2121, null);
        server.start();
        try {
            MiniFtp ftp = new MiniFtp("127.0.0.1", 2121);
            ftp.login();
            ftp.binary();

            byte[] viaFtp = ftp.retr("/.sonyconnect/th/DCIM/100MSDCF/DSC01781.ARW", 0);
            check("FTP 虚拟 RETR == 直接提取", java.util.Arrays.equals(viaFtp, arwSmall));

            byte[] viaFtpRest = ftp.retr("/.sonyconnect/th/DCIM/100MSDCF/DSC01781.ARW", 100);
            byte[] expectedRest = java.util.Arrays.copyOfRange(arwSmall, 100, arwSmall.length);
            check("FTP 虚拟 RETR REST100 == payload[100:]", java.util.Arrays.equals(viaFtpRest, expectedRest));

            long size = ftp.size("/.sonyconnect/th/DCIM/100MSDCF/DSC01779.JPG");
            check("SIZE 虚拟 == JPG 小图长度", size == jpgSmall.length);

            byte[] pv = ftp.retr("/.sonyconnect/pv/DCIM/100MSDCF/DSC01781.ARW", 0);
            check("FTP 虚拟大预览 == 直接提取", java.util.Arrays.equals(pv, arwPreview));

            byte[] full = ftp.retr("/DCIM/100MSDCF/DSC01781.ARW", 0);
            check("真实文件整传长度", full != null && full.length == arwIn.length());
            check("REST 偏移不残留（整传正确）", full != null && full[0] == arwSrcLength0(arwSrc));

            String esc = ftp.retrRaw("/.sonyconnect/th/../../Windows/win.ini");
            check("虚拟路径逃逸被拒(550)", esc.startsWith("550"));

            String miss = ftp.retrRaw("/.sonyconnect/th/DCIM/100MSDCF/NOPE.ARW");
            check("不存在的媒体被拒(550)", miss.startsWith("550"));

            String mp4 = ftp.retrRaw("/.sonyconnect/th/DCIM/100MSDCF");
            check("目录/不支持类型被拒(550)", mp4.startsWith("550"));

            ftp.quit();
        } finally {
            server.stop();
        }

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static byte arwSrcLength0(File f) throws Exception {
        InputStream in = Files.newInputStream(f.toPath());
        try {
            return (byte) in.read();
        } finally {
            in.close();
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) passed++;
        else failed++;
    }

    private static void copy(File src, File dst) throws Exception {
        FileOutputStream out = new FileOutputStream(dst);
        try {
            InputStream in = Files.newInputStream(src.toPath());
            try {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
            }
        } finally {
            out.close();
        }
    }

    /** 迷你 FTP 客户端：只实现本次测试需要的命令（PASV/TYPE/RETR/REST/SIZE/QUIT） */
    private static class MiniFtp {
        private final Socket ctrl;
        private final InputStream in;
        private final OutputStream out;
        private final StringBuilder resp = new StringBuilder();

        MiniFtp(String host, int port) throws Exception {
            ctrl = new Socket();
            ctrl.connect(new InetSocketAddress(InetAddress.getByName(host), port), 3000);
            in = ctrl.getInputStream();
            out = ctrl.getOutputStream();
            readReply(); // banner
        }

        private String readReply() throws Exception {
            resp.setLength(0);
            while (true) {
                int b = in.read();
                if (b < 0) break;
                resp.append((char) b);
                if (b == '\n') {
                    String s = resp.toString();
                    if (s.length() >= 4 && s.charAt(3) == ' ') return s;
                    if (s.length() >= 4 && s.charAt(3) == '-') continue; // 多行，简化处理
                }
            }
            return resp.toString();
        }

        private String cmd(String c) throws Exception {
            out.write((c + "\r\n").getBytes("UTF-8"));
            out.flush();
            return readReply();
        }

        void login() throws Exception {
            String r = cmd("USER anon");
            if (!r.startsWith("331")) throw new IllegalStateException("USER: " + r);
            r = cmd("PASS x");
            if (!r.startsWith("230")) throw new IllegalStateException("PASS: " + r);
        }

        void binary() throws Exception {
            String r = cmd("TYPE I");
            if (!r.startsWith("200")) throw new IllegalStateException("TYPE: " + r);
        }

        private int pasvPort() throws Exception {
            String r = cmd("PASV");
            int a = r.indexOf('(');
            int b = r.indexOf(')');
            String[] parts = r.substring(a + 1, b).split(",");
            int hi = Integer.parseInt(parts[4].trim());
            int lo = Integer.parseInt(parts[5].trim());
            return hi * 256 + lo;
        }

        /** RETR 带可选 REST；返回收到的字节（数据连接 EOF 后等最终回复） */
        byte[] retr(String path, long rest) throws Exception {
            if (rest > 0) {
                String r = cmd("REST " + rest);
                if (!r.startsWith("350")) throw new IllegalStateException("REST: " + r);
            }
            int p = pasvPort();
            String r = cmd("RETR " + path);
            if (!r.startsWith("150")) throw new IllegalStateException("RETR reply: " + r);
            Socket data = new Socket(InetAddress.getByName("127.0.0.1"), p);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream din = data.getInputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = din.read(buf)) > 0) bos.write(buf, 0, n);
            data.close();
            r = readReply(); // 226
            if (!r.startsWith("226")) throw new IllegalStateException("after RETR: " + r);
            return bos.toByteArray();
        }

        /** RETR 只取首行回复（负路径用），丢弃数据连接 */
        String retrRaw(String path) throws Exception {
            int p = pasvPort();
            String r = cmd("RETR " + path);
            if (r.startsWith("150")) {
                Socket data = new Socket(InetAddress.getByName("127.0.0.1"), p);
                InputStream din = data.getInputStream();
                byte[] buf = new byte[4096];
                while (din.read(buf) > 0) { /* 排干 */ }
                data.close();
                readReply();
            }
            return r;
        }

        long size(String path) throws Exception {
            String r = cmd("SIZE " + path);
            if (!r.startsWith("213")) throw new IllegalStateException("SIZE: " + r);
            return Long.parseLong(r.substring(4).trim());
        }

        void quit() throws Exception {
            cmd("QUIT");
            ctrl.close();
        }
    }
}
