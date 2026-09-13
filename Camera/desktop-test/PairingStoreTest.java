package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;













public class PairingStoreTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static File freshDir(String tag) {
        File d = new File(System.getProperty("java.io.tmpdir"),
                "sonyconnect-pair-" + tag + "-" + System.nanoTime());
        d.mkdirs();
        return d;
    }

    
    private static byte[] bytes(byte seed, int n) {
        byte[] k = new byte[n];
        for (int i = 0; i < n; i++) {
            k[i] = (byte) (seed + i);
        }
        return k;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 配对表 / 配对窗口 / 设备码 边界 ===");

        deviceIdChecks();
        windowChecks();
        legacyRecordChecks();
        tableChecks();
        corruptDataChecks();
        codecEdgeChecks();

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    
    
    

    private static void deviceIdChecks() {
        File dir = freshDir("dev");
        PairingStore a = new PairingStore(dir);
        String hex1 = a.deviceIdHex();

        check("设备码为 8 字节（16 位 hex）", hex1 != null && hex1.length() == 16);
        check("设备码只含小写 hex",
                hex1 != null && hex1.matches("[0-9a-f]{16}"));

        PairingStore b = new PairingStore(dir);
        check("同目录新实例读到同一设备码", hex1.equals(b.deviceIdHex()));
        check("设备码文件已落盘", new File(dir, "device_id.hex").isFile());

        byte[] c1 = a.deviceId();
        check("deviceId() 返回 8 字节", c1.length == 8);
        check("deviceId() 每次返回独立副本（内部状态改不动）",
                !PtpCodec.eq(c1, a.deviceId()) || !(c1 == a.deviceId()));

        PairingStore other = new PairingStore(freshDir("dev2"));
        check("不同目录生成不同设备码", !hex1.equals(other.deviceIdHex()));

        File bad = freshDir("dev3");
        writeText(new File(bad, "device_id.hex"), "这显然不是 hex");
        PairingStore rec = new PairingStore(bad);
        check("设备码文件非法时重新生成而非崩溃",
                rec.deviceIdHex() != null && rec.deviceIdHex().matches("[0-9a-f]{16}"));
    }

    
    
    

    private static void windowChecks() {
        PairingStore s = new PairingStore(freshDir("win"));
        long t0 = 1000000L;

        check("初始状态窗口是关的", !s.isOpen(t0) && s.code(t0) == null);

        String code = s.openWindow(t0);
        check("开窗返回 6 位数字码", code != null && code.matches("[0-9]{6}"));
        check("开窗后 isOpen 为真", s.isOpen(t0));
        check("开窗后 code() 与返回值一致", code.equals(s.code(t0)));
        check("剩余时间不超过窗口上限",
                s.remainingMs(t0) == PairingStore.WINDOW_MS);

        
        String code2 = s.openWindow(t0);
        check("重新开窗会换新码", code2 != null && code2.matches("[0-9]{6}"));

        
        long after = t0 + PairingStore.WINDOW_MS + 1;
        check("窗口过期后 isOpen 为假", !s.isOpen(after));
        check("窗口过期后 code() 返回 null", s.code(after) == null);
        check("窗口过期后剩余时间为 0", s.remainingMs(after) == 0L);

        
        PairingStore f = new PairingStore(freshDir("win2"));
        long n = 500000L;
        f.openWindow(n);
        for (int i = 1; i < PairingStore.MAX_FAILURES; i++) {
            f.noteFailure(n);
        }
        check("失败未用尽时窗口仍开", f.isOpen(n) && f.failures() == PairingStore.MAX_FAILURES - 1);
        f.noteFailure(n);
        check("失败用尽（" + PairingStore.MAX_FAILURES + " 次）后窗口关闭", !f.isOpen(n));

        
        PairingStore c = new PairingStore(freshDir("win3"));
        c.openWindow(n);
        c.closeWindow();
        check("显式关窗后 isOpen 为假", !c.isOpen(n));
    }

    
    
    

    






    private static void legacyRecordChecks() {
        File dir = freshDir("legacy");
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"0a1b2c3d4e5f6071\",\"name\":\"Xiaomi 15\",\"model\":\"ILCE-6300\",")
                .append("\"serial\":\"05186914\",\"key\":\"")
                .append("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
                .append("\",\"suite\":\"AES128-CBC-HMAC256\",\"enc\":1,")
                .append("\"pairedAt\":111,\"lastSeenAt\":222,\"lastIp\":\"192.168.122.1\",")
                .append("\"lastPort\":15740,\"proto\":65536}\n");
        writeText(new File(dir, "pairings.json"), sb.toString());

        PairingStore s = new PairingStore(dir);
        check("旧格式记录不会因为多余字段被丢弃", s.size() == 1);
        PairingStore.Paired p = s.find("0a1b2c3d4e5f6071");
        check("旧格式记录的设备名/型号/SN 都在",
                p != null && "Xiaomi 15".equals(p.peerName)
                        && "ILCE-6300".equals(p.peerModel)
                        && "05186914".equals(p.peerSerial));
        check("旧格式记录的其余字段都在",
                p != null && p.pairedAt == 111L && p.lastSeenAt == 222L
                        && "192.168.122.1".equals(p.lastIp)
                        && p.lastPort == 15740 && p.protoVersion == 65536);
        check("旧格式记录可被按设备码命中",
                s.contains(PtpCodec.unhex("0a1b2c3d4e5f6071")));

        
        s.touch(PtpCodec.unhex("0a1b2c3d4e5f6071"), "192.168.122.2", 15741, 65536);
        String text = readText(new File(dir, "pairings.json"));
        check("重新落盘后不再写出 key/suite/enc",
                text != null && text.indexOf("\"key\"") < 0
                        && text.indexOf("\"suite\"") < 0
                        && text.indexOf("\"enc\"") < 0);
        check("重新落盘后仍能读回（touch 生效）",
                new PairingStore(dir).find("0a1b2c3d4e5f6071") != null
                        && "192.168.122.2".equals(
                                new PairingStore(dir).find("0a1b2c3d4e5f6071").lastIp));
    }

    
    
    

    private static void tableChecks() {
        File dir = freshDir("tbl");
        PairingStore s = new PairingStore(dir);
        long now = 1700000000000L;

        byte[] devA = new byte[8];
        byte[] devB = new byte[8];
        for (int i = 0; i < 8; i++) {
            devA[i] = (byte) (0x11 + i);
            devB[i] = (byte) (0x21 + i);
        }
        String hexA = PtpCodec.hex(devA);

        check("空表：contains 为假", !s.contains(devA));
        check("空表：size 为 0", s.size() == 0);

        PairingStore.Paired p = new PairingStore.Paired();
        p.peerDeviceId = hexA;
        p.peerName = "Xiaomi 15";
        p.peerModel = "ILCE-6300";
        p.peerSerial = "05186914";
        p.pairedAt = now;
        p.lastSeenAt = now;
        s.upsert(p);

        check("upsert 后 size 为 1", s.size() == 1);
        check("contains(byte[8]) 命中", s.contains(devA));
        check("未配对的设备不命中", !s.contains(devB));
        check("find(hex) 命中", s.find(hexA) != null);
        check("find 大小写不敏感", s.find(hexA.toUpperCase()) != null);
        check("find 记录了手机设备名", "Xiaomi 15".equals(s.find(hexA).peerName));
        check("find 记录了相机型号/SN（手机端配对列表要显示它）",
                "ILCE-6300".equals(s.find(hexA).peerModel)
                        && "05186914".equals(s.find(hexA).peerSerial));

        
        PairingStore re = new PairingStore(dir);
        check("配对表跨实例持久化（size）", re.size() == 1);
        check("配对表跨实例持久化（设备名一致）",
                re.find(hexA) != null && "Xiaomi 15".equals(re.find(hexA).peerName));
        check("配对表跨实例持久化（型号/SN 一致）",
                re.find(hexA) != null && "ILCE-6300".equals(re.find(hexA).peerModel)
                        && "05186914".equals(re.find(hexA).peerSerial));

        
        PairingStore.Paired upd = re.find(hexA);
        upd.pairedAt = 0L;
        upd.lastSeenAt = now + 5000;
        upd.peerName = "Xiaomi 15 Pro";
        re.upsert(upd);
        check("upsert 保留原 pairedAt", re.find(hexA).pairedAt == now);
        check("upsert 更新了其它字段", "Xiaomi 15 Pro".equals(re.find(hexA).peerName));
        check("upsert 覆盖而非追加", re.size() == 1);

        
        re.touch(devA, "192.168.122.9", 15740, (1 << 16));
        PairingStore.Paired touched = re.find(hexA);
        check("touch 更新 lastIp/port/proto",
                "192.168.122.9".equals(touched.lastIp)
                        && touched.lastPort == 15740
                        && touched.protoVersion == (1 << 16));
        check("touch 不动名称", "Xiaomi 15 Pro".equals(touched.peerName));
        check("touch 未配对的设备是空操作（不新增记录）", re.size() == 1);

        
        int before = re.size();
        PairingStore.Paired noId = new PairingStore.Paired();
        noId.peerDeviceId = "";
        re.upsert(noId);
        check("空设备码的条目被拒绝", re.size() == before && !re.contains(devB));

        
        check("remove 首次返回 true", re.remove(hexA));
        check("remove 后表为空", re.size() == 0 && !re.contains(devA));
        check("remove 重复调用返回 false", !re.remove(hexA));

        
        re.upsert(p);
        re.clear();
        check("clear 清空整表", re.size() == 0);
    }

    
    
    

    private static void corruptDataChecks() {
        File dir = freshDir("corrupt");
        PairingStore s = new PairingStore(dir);

        byte[] dev = new byte[8];
        for (int i = 0; i < 8; i++) {
            dev[i] = (byte) (0x31 + i);
        }
        PairingStore.Paired good = new PairingStore.Paired();
        good.peerDeviceId = PtpCodec.hex(dev);
        good.peerName = "Good";
        s.upsert(good);

        
        StringBuilder sb = new StringBuilder();
        sb.append("这不是 JSON\n");
        sb.append("{\"name\":\"没有 id\"}\n");
        sb.append("{\"id\":\"\"}\n");
        writeText(new File(dir, "pairings.json"), sb.toString());

        PairingStore re = new PairingStore(dir);
        check("文件被脏行占满时表为空但不抛异常", re.size() == 0);

        
        StringBuilder mixed = new StringBuilder();
        mixed.append("垃圾行\n");
        mixed.append("{\"id\":\"").append(PtpCodec.hex(dev))
                .append("\",\"name\":\"Good\"}\n");
        mixed.append("{\"id\":\"\"}\n");
        mixed.append("{\"name\":\"没有 id\"}\n");
        writeText(new File(dir, "pairings.json"), mixed.toString());

        PairingStore re2 = new PairingStore(dir);
        check("脏行混排时好行仍被保留", re2.size() == 1 && re2.contains(dev));
        check("脏行混排时无 id 的坏行被丢弃", re2.find("") == null);
    }

    
    
    

    private static void codecEdgeChecks() {
        byte[] seq = bytes((byte) 0x01, 16);
        check("hex(null) 返回空串而不是 NPE", "".equals(PtpCodec.hex(null)));
        check("hex/unhex 往返", PtpCodec.eq(seq, PtpCodec.unhex(PtpCodec.hex(seq))));
        check("hex 全零 8 字节 = 16 个 0", "0000000000000000".equals(PtpCodec.hex(new byte[8])));
        check("utf8 解码越界自动收缩",
                "".equals(PtpIpServer.utf8(new byte[]{1, 2, 3}, 5, 9)));
        check("utf8 编解码中文往返",
                "ILCE-6300 相机".equals(PtpIpServer.utf8(
                        PtpIpServer.utf8("ILCE-6300 相机"), 0, 64)));
    }

    private static void writeText(File f, String text) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
        } catch (Exception e) {
            System.out.println("[WARN] 测试写文件失败: " + e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readText(File f) {
        java.io.InputStreamReader r = null;
        try {
            r = new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
