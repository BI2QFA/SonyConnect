package io.github.bi2qfa.sonyconnect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 相机端配对表 + 配对窗口 + 本机设备码（纯 Java，零 Android 依赖，桌面可测）。
 *
 * 三块职责：
 * <ol>
 *   <li><b>配对表</b>：记录「哪台手机被允许接入」——主键是手机的 8 字节设备码
 *       （16 位 hex），值是配对协商出的 32 字节长期共享密钥。手机端
 *       {@code data/PairingStore.kt} 是它的镜像（那边主键用相机 16 字节 GUID 的
 *       hex，各侧都用自己在协议里拿得到的天然标识）。</li>
 *   <li><b>配对窗口</b>：6 位数字配对码 + 180s 有效期 + 5 次失败失效。
 *       码只在窗口开启期间有效；{@link #openWindow} 每次调用都换新码。</li>
 *   <li><b>本机设备码</b>：8 字节，随机生成一次后落盘固定。
 *       ★ 绝不读取任何真机信息（不读序列号、不读型号）——它只用于后端辨认，
 *       不参与任何密钥派生，所以没有任何理由与硬件绑定成派生关系。</li>
 * </ol>
 *
 * 落盘格式（都在构造参数给的目录里）：
 * <ul>
 *   <li>{@code pairings.json}：**JSONL**，每行一个扁平 JSON 对象。
 *       之所以不用手机端那种「单条 JSON 数组」：相机端 {@link SJson} 的编码与
 *       解析都不支持嵌套对象（{@code member(String)} 一律带引号、解析器遇到
 *       嵌套对象直接抛错），数组套对象在这边写不出来。
 *       字段名与手机端 {@code PairingStore.kt} 逐字对齐：
 *       {@code id / name / model / serial / key / suite / enc / pairedAt /
 *       lastSeenAt / lastIp / lastPort / proto}。</li>
 *   <li>{@code device_id.hex}：本机 8 字节设备码的 16 位 hex 文本。</li>
 * </ul>
 *
 * 线程安全：所有公开方法都是 {@code synchronized}。PtpIpServer 的多个连接线程
 * 会并发查表（allowInitiator）、UI 线程会并发开关窗口。
 */
public class PairingStore {

    /** 配对窗口有效期（毫秒）。与手机端输入配对码的节奏匹配：够从容，又不至于忘记。 */
    public static final long WINDOW_MS = 180000L;

    /** 窗口内允许的失败次数；用满即关窗（要重新进配对模式换码）。 */
    public static final int MAX_FAILURES = 5;

    /** 配对码位数（纯数字，左补零）。 */
    public static final int CODE_LEN = 6;

    /** 本机设备码长度（字节）。 */
    public static final int DEVICE_ID_LEN = 8;


    private static final String FILE_TABLE = "pairings.json";
    private static final String FILE_DEVICE_ID = "device_id.hex";

    /**
     * 一台已配对手机。字段与手机端 {@code PairedCamera} 对称，但**指向相反**：
     * 这边的 name/model/serial 描述的是**手机**。
     * <p>手机端那份描述的是相机（型号 + SN 要显示在手机端的已配对列表里）；
     * 这份描述的是手机（设备名要显示在相机端的配对页上）。
     */
    public static final class Paired {
        /** 手机 8 字节设备码的 16 位 hex（小写）——主键。 */
        public String peerDeviceId = "";
        /** 手机设备名，如 {@code Xiaomi 15}；取自 PAIR_BEGIN 载荷。 */
        public String peerName = "";
        /** 手机型号；当前协议不带，留空（显示用 peerName 即可）。 */
        public String peerModel = "";
        /** 手机序列号；当前协议不带，留空。 */
        public String peerSerial = "";
        /** 32 字节长期共享密钥。**不过网**，由配对码在两端各自本地派生。 */
        public long pairedAt;
        public long lastSeenAt;
        public String lastIp = "";
        public int lastPort;
        public int protoVersion;

        public Paired copy() {
            Paired p = new Paired();
            p.peerDeviceId = peerDeviceId;
            p.peerName = peerName;
            p.peerModel = peerModel;
            p.peerSerial = peerSerial;
            p.pairedAt = pairedAt;
            p.lastSeenAt = lastSeenAt;
            p.lastIp = lastIp;
            p.lastPort = lastPort;
            p.protoVersion = protoVersion;
            return p;
        }
    }

    private final File dir;
    private final File tableFile;
    private final File deviceIdFile;

    private final List<Paired> table = new ArrayList<Paired>();

    private byte[] deviceId;
    private String deviceIdHex;

    private boolean windowOpen;
    private String windowCode;
    private long windowExpiresAt;
    private int windowFailures;


    private String lastError;

    public PairingStore(File dir) {
        this.dir = dir;
        this.tableFile = new File(dir, FILE_TABLE);
        this.deviceIdFile = new File(dir, FILE_DEVICE_ID);
        loadTable();
        ensureDeviceId();
    }

    /** 最近一次落盘/读盘失败原因；成功时为 null。供 UI 诊断用。 */
    public synchronized String lastError() {
        return lastError;
    }

    // ============================================================
    // 本机设备码
    // ============================================================

    /**
     * 本机 8 字节设备码。首次调用时若本地没有就随机生成并落盘，此后永不变。
     * <p>随机源用 {@link Rand#bytes}，不掺任何真机信息。
     */
    public synchronized byte[] deviceId() {
        ensureDeviceId();
        return (byte[]) deviceId.clone();
    }

    /** 本机设备码的 16 位 hex（小写）。 */
    public synchronized String deviceIdHex() {
        ensureDeviceId();
        return deviceIdHex;
    }

    private void ensureDeviceId() {
        if (deviceId != null) {
            return;
        }
        String hex = readTextFile(deviceIdFile);
        if (hex != null) {
            hex = hex.trim().toLowerCase(Locale.US);
            if (hex.length() == DEVICE_ID_LEN * 2) {
                try {
                    byte[] b = PtpCodec.unhex(hex);
                    if (b != null && b.length == DEVICE_ID_LEN) {
                        deviceId = b;
                        deviceIdHex = hex;
                        return;
                    }
                } catch (Throwable ignored) {
                    // 文件被写坏 → 落到下面重新生成
                }
            }
        }
        byte[] fresh = Rand.bytes(DEVICE_ID_LEN);
        String freshHex = PtpCodec.hex(fresh);
        if (writeTextFile(deviceIdFile, freshHex)) {
            deviceId = fresh;
            deviceIdHex = freshHex;
        } else {
            // 落盘失败也不能没有码：本次会话用临时码（下次启动会再生成一个）
            deviceId = fresh;
            deviceIdHex = freshHex;
        }
    }

    // ============================================================
    // 配对表查询
    // ============================================================

    /** 全表（按 lastSeenAt 倒序，最近用的在前）。 */
    public synchronized List<Paired> all() {
        return new ArrayList<Paired>(table);
    }

    public synchronized int size() {
        return table.size();
    }

    /** 按手机设备码（16 位 hex，大小写不敏感）查。 */
    public synchronized Paired find(String peerDeviceIdHex) {
        if (peerDeviceIdHex == null) {
            return null;
        }
        String key = peerDeviceIdHex.trim().toLowerCase(Locale.US);
        for (int i = 0; i < table.size(); i++) {
            Paired p = table.get(i);
            if (p.peerDeviceId.equals(key)) {
                return p.copy();
            }
        }
        return null;
    }

    /** 按手机 8 字节设备码查。 */
    public synchronized Paired find(byte[] guid8) {
        if (guid8 == null) {
            return null;
        }
        return find(PtpCodec.hex(shortId(guid8)));
    }

    public synchronized boolean contains(byte[] guid8) {
        return find(guid8) != null;
    }

    /** 只取前 8 字节（16 字节 GUID 的高 8 字节是补零，设备码是低 8 字节）。 */
    static byte[] shortId(byte[] b) {
        byte[] o = new byte[DEVICE_ID_LEN];
        if (b != null) {
            System.arraycopy(b, 0, o, 0, Math.min(DEVICE_ID_LEN, b.length));
        }
        return o;
    }

    // ============================================================
    // 配对表变更
    // ============================================================

    /**
     * 新增或覆盖一条配对。已存在则保留原 {@code pairedAt}（首次配对时间），
     * 只更新密钥与可见信息 —— 与手机端 {@code upsert} 语义一致。
     */
    public synchronized void upsert(Paired p) {
        if (p == null || p.peerDeviceId == null || p.peerDeviceId.length() == 0) {
            return;
        }
        Paired in = p.copy();
        in.peerDeviceId = in.peerDeviceId.toLowerCase(Locale.US);
        if (in.peerDeviceId.length() == 0) {
            return;
        }
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).peerDeviceId.equals(in.peerDeviceId)) {
                if (in.pairedAt == 0L) {
                    in.pairedAt = table.get(i).pairedAt;
                }
                table.set(i, in);
                sortAndPersist();
                return;
            }
        }
        if (in.pairedAt == 0L) {
            in.pairedAt = System.currentTimeMillis();
        }
        table.add(in);
        sortAndPersist();
    }

    /** 解除配对。返回是否真的删掉了一条。 */
    public synchronized boolean remove(String peerDeviceIdHex) {
        if (peerDeviceIdHex == null) {
            return false;
        }
        String key = peerDeviceIdHex.trim().toLowerCase(Locale.US);
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).peerDeviceId.equals(key)) {
                table.remove(i);
                sortAndPersist();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean remove(byte[] guid8) {
        return guid8 != null && remove(PtpCodec.hex(shortId(guid8)));
    }

    public synchronized void clear() {
        table.clear();
        sortAndPersist();
    }


    private void sortAndPersist() {
        Collections.sort(table, new Comparator<Paired>() {
            public int compare(Paired a, Paired b) {
                if (a.lastSeenAt == b.lastSeenAt) {
                    return a.peerDeviceId.compareTo(b.peerDeviceId);
                }
                return a.lastSeenAt > b.lastSeenAt ? -1 : 1;
            }
        });
        persistTable();
    }

    // ============================================================
    // 配对窗口
    // ============================================================

    /** 开窗并生成新码（重开即换码，作废旧码）。返回 6 位码。 */
    public synchronized String openWindow(long now) {
        windowCode = randomCode();
        windowExpiresAt = now + WINDOW_MS;
        windowFailures = 0;
        windowOpen = true;
        return windowCode;
    }

    /** 关窗（离开配对页、超时、失败用尽、配对成功都会走这里）。 */
    public synchronized void closeWindow() {
        if (!windowOpen) {
            return;
        }
        windowOpen = false;
        windowCode = null;
        windowExpiresAt = 0;
        windowFailures = 0;
    }

    /** 窗口是否仍有效（过期即自动关窗）。 */
    public synchronized boolean isOpen(long now) {
        if (!windowOpen) {
            return false;
        }
        if (now >= windowExpiresAt) {
            closeWindow();
            return false;
        }
        return true;
    }

    /** 当前 6 位码；窗口关或已过期返回 null。 */
    public synchronized String code(long now) {
        return isOpen(now) ? windowCode : null;
    }

    /** 剩余有效毫秒数；窗口关返回 0。 */
    public synchronized long remainingMs(long now) {
        if (!isOpen(now)) {
            return 0L;
        }
        long left = windowExpiresAt - now;
        return left < 0L ? 0L : left;
    }

    /** 已失败次数（诊断用）。 */
    public synchronized int failures() {
        return windowFailures;
    }

    /** 本会话累计已用掉的配对角（配对成功后由调用方清零）。 */
    public synchronized void noteFailure(long now) {
        if (!isOpen(now)) {
            return;
        }
        windowFailures++;
        if (windowFailures >= MAX_FAILURES) {
            closeWindow();
        }
    }

    private static String randomCode() {
        byte[] r = Rand.bytes(4);
        long v = ((long) (r[0] & 0xFF) << 24)
                | ((long) (r[1] & 0xFF) << 16)
                | ((long) (r[2] & 0xFF) << 8)
                | (long) (r[3] & 0xFF);
        long n = v % 1000000L;
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < CODE_LEN; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    // ============================================================
    // 落盘
    // ============================================================

    private void loadTable() {
        table.clear();
        String text = readTextFile(tableFile);
        if (text == null || text.length() == 0) {
            return;
        }
        List<Paired> out = new ArrayList<Paired>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                if (i > start) {
                    Paired p = decodeLine(text.substring(start, i));
                    if (p != null) {
                        out.add(p);
                    }
                }
                start = i + 1;
            }
        }
        table.addAll(out);
        Collections.sort(table, new Comparator<Paired>() {
            public int compare(Paired a, Paired b) {
                if (a.lastSeenAt == b.lastSeenAt) {
                    return a.peerDeviceId.compareTo(b.peerDeviceId);
                }
                return a.lastSeenAt > b.lastSeenAt ? -1 : 1;
            }
        });
    }

    private String encodeLine(Paired p) {
        StringBuilder sb = SJson.startObj();
        SJson.member(sb, "id", p.peerDeviceId);
        SJson.member(sb, "name", p.peerName == null ? "" : p.peerName);
        SJson.member(sb, "model", p.peerModel == null ? "" : p.peerModel);
        SJson.member(sb, "serial", p.peerSerial == null ? "" : p.peerSerial);
        SJson.member(sb, "pairedAt", p.pairedAt);
        SJson.member(sb, "lastSeenAt", p.lastSeenAt);
        SJson.member(sb, "lastIp", p.lastIp == null ? "" : p.lastIp);
        SJson.member(sb, "lastPort", (long) p.lastPort);
        SJson.member(sb, "proto", (long) p.protoVersion);
        return SJson.endObj(sb);
    }

    private Paired decodeLine(String line) {
        Map<String, Object> o;
        try {
            o = SJson.parse(line);
        } catch (Throwable t) {
            return null;
        }
        String id = SJson.asString(o, "id");
        if (id == null || id.length() == 0) {
            return null;
        }
        Paired p = new Paired();
        p.peerDeviceId = id.trim().toLowerCase(Locale.US);
        p.peerName = str(o, "name");
        p.peerModel = str(o, "model");
        p.peerSerial = str(o, "serial");
        p.pairedAt = SJson.asLong(o, "pairedAt", 0L);
        p.lastSeenAt = SJson.asLong(o, "lastSeenAt", 0L);
        p.lastIp = str(o, "lastIp");
        p.lastPort = (int) SJson.asLong(o, "lastPort", 0L);
        p.protoVersion = (int) SJson.asLong(o, "proto", 0L);
        return p;
    }

    private static String str(Map<String, Object> o, String key) {
        String s = SJson.asString(o, key);
        return s == null ? "" : s;
    }

    private void persistTable() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < table.size(); i++) {
            sb.append(encodeLine(table.get(i))).append('\n');
        }
        if (writeTextFile(tableFile, sb.toString())) {
            lastError = null;
        }
    }

    // ============================================================
    // 文本文件读写（UTF-8；先写临时文件再改名，避免掉电留半截表）
    // ============================================================

    private String readTextFile(File f) {
        if (!f.isFile()) {
            return null;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            lastError = f.getName() + " 读取失败: " + t;
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean writeTextFile(File f, String text) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            lastError = "目录不可用: " + dir;
            return false;
        }
        File tmp = new File(dir, f.getName() + ".tmp");
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(text);
            w.flush();
            w.close();
            w = null;
            // 先直接改名：POSIX（相机内部存储）上这是原子替换，不会出现半截表。
            // Windows（桌面回归）覆盖已存在文件会失败，再退到「删旧再改名」。
            if (tmp.renameTo(f)) {
                return true;
            }
            if (f.exists() && !f.delete()) {
            }
            if (tmp.renameTo(f)) {
                return true;
            }
            lastError = f.getName() + " 改名失败";
            return false;
        } catch (Throwable t) {
            lastError = f.getName() + " 写入失败: " + t;
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
