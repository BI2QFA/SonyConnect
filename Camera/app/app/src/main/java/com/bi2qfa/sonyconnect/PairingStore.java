package com.bi2qfa.sonyconnect;

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
































public class PairingStore {

    
    public static final long WINDOW_MS = 180000L;

    
    public static final int MAX_FAILURES = 5;

    
    public static final int CODE_LEN = 6;

    
    public static final int DEVICE_ID_LEN = 8;


    private static final String FILE_TABLE = "pairings.json";
    private static final String FILE_DEVICE_ID = "device_id.hex";

    





    public static final class Paired {
        
        public String peerDeviceId = "";
        
        public String peerName = "";
        
        public String peerModel = "";
        
        public String peerSerial = "";
        
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

    
    public synchronized String lastError() {
        return lastError;
    }

    
    
    

    



    public synchronized byte[] deviceId() {
        ensureDeviceId();
        return (byte[]) deviceId.clone();
    }

    
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
                    
                }
            }
        }
        byte[] fresh = Rand.bytes(DEVICE_ID_LEN);
        String freshHex = PtpCodec.hex(fresh);
        if (writeTextFile(deviceIdFile, freshHex)) {
            deviceId = fresh;
            deviceIdHex = freshHex;
        } else {
            
            deviceId = fresh;
            deviceIdHex = freshHex;
        }
    }

    
    
    

    
    public synchronized List<Paired> all() {
        return new ArrayList<Paired>(table);
    }

    public synchronized int size() {
        return table.size();
    }

    
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

    
    public synchronized Paired find(byte[] guid8) {
        if (guid8 == null) {
            return null;
        }
        return find(PtpCodec.hex(shortId(guid8)));
    }

    public synchronized boolean contains(byte[] guid8) {
        return find(guid8) != null;
    }

    
    static byte[] shortId(byte[] b) {
        byte[] o = new byte[DEVICE_ID_LEN];
        if (b != null) {
            System.arraycopy(b, 0, o, 0, Math.min(DEVICE_ID_LEN, b.length));
        }
        return o;
    }

    
    
    

    



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

    
    public synchronized void touch(byte[] guid8, String ip, int port, int protoVersion) {
        Paired cur = find(guid8);
        if (cur == null) {
            return;
        }
        cur.lastSeenAt = System.currentTimeMillis();
        if (ip != null) {
            cur.lastIp = ip;
        }
        if (port > 0) {
            cur.lastPort = port;
        }
        if (protoVersion != 0) {
            cur.protoVersion = protoVersion;
        }
        upsert(cur);
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

    
    
    

    
    public synchronized String openWindow(long now) {
        windowCode = randomCode();
        windowExpiresAt = now + WINDOW_MS;
        windowFailures = 0;
        windowOpen = true;
        return windowCode;
    }

    
    public synchronized void closeWindow() {
        if (!windowOpen) {
            return;
        }
        windowOpen = false;
        windowCode = null;
        windowExpiresAt = 0;
        windowFailures = 0;
    }

    
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

    
    public synchronized String code(long now) {
        return isOpen(now) ? windowCode : null;
    }

    
    public synchronized long remainingMs(long now) {
        if (!isOpen(now)) {
            return 0L;
        }
        long left = windowExpiresAt - now;
        return left < 0L ? 0L : left;
    }

    
    public synchronized int failures() {
        return windowFailures;
    }

    
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
