package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;



















public class PtpCameraHandler implements PtpIpServer.Handler, PtpIpServer.PairingHandler {

    
    public interface Platform {
        
        int batteryPct();

        String model();

        String serial();

        String firmware();

        
        String lens();

        
        String mode();

        
        String ssid();

        

        
        String region();

        
        String apiVersion();

        
        String androidVersion();

        
        int androidSdk();

        
        long sdTotalBytes();

        
        long sdUsedBytes();
    }

    private final File rootDir;
    private final ThumbPrefetcher prefetcher;
    private final PairingStore pairing;
    private final Platform platform;

    
    private static final int MEMO_MAX_BYTES = 64 * 1024;
    private final Object memoLock = new Object();
    private String memoKey;
    private byte[] memoBytes;

    public PtpCameraHandler(File rootDir,
                            ThumbPrefetcher prefetcher,
                            PairingStore pairing,
                            Platform platform) {
        this.rootDir = rootDir;
        this.prefetcher = prefetcher;
        this.pairing = pairing;
        this.platform = platform;
    }

    





    public interface PairingEvents {
        
        void onPaired(String peerDeviceName);

        






        void onPairingAttemptFailed(int used, int max, boolean windowClosed);
    }

    private volatile PairingEvents pairingEvents;

    public void setPairingEvents(PairingEvents e) {
        this.pairingEvents = e;
    }

    
    
    

    public byte[] guid() {
        
        
        return pairing == null ? Rand.bytes(PairingStore.DEVICE_ID_LEN)
                : pairing.deviceId();
    }

    public String deviceName() {
        String model = safe(platform == null ? null : platform.model());
        String serial = safe(platform == null ? null : platform.serial());
        if (model.length() > 0 && serial.length() > 0) {
            
            
            
            
            return model + " SN:" + serial;
        }
        if (model.length() > 0) {
            return model;
        }
        return "SonyConnect Camera";
    }

    


















    public byte[] deviceInfo() {
        StringBuilder sb = SJson.startObj();
        SJson.member(sb, "name", deviceName());
        SJson.member(sb, "model", safe(platform == null ? null : platform.model()));
        SJson.member(sb, "serial", safe(platform == null ? null : platform.serial()));
        SJson.member(sb, "firmware", safe(platform == null ? null : platform.firmware()));
        SJson.member(sb, "region", safe(platform == null ? null : platform.region()));
        SJson.member(sb, "apiVersion", safe(platform == null ? null : platform.apiVersion()));
        SJson.member(sb, "androidVersion",
                safe(platform == null ? null : platform.androidVersion()));
        SJson.member(sb, "androidSdk", platform == null ? -1 : platform.androidSdk());
        SJson.member(sb, "sdTotal", platform == null ? -1L : platform.sdTotalBytes());
        SJson.member(sb, "sdUsed", platform == null ? -1L : platform.sdUsedBytes());
        SJson.member(sb, "mode", safe(platform == null ? null : platform.mode()));
        SJson.member(sb, "ssid", safe(platform == null ? null : platform.ssid()));
        return utf8(SJson.endObj(sb));
    }

    public int batteryPct() {
        return platform == null ? -1 : platform.batteryPct();
    }

    public boolean hasLens() {
        return lensName().length() > 0;
    }

    
    public String lensName() {
        return safe(platform == null ? null : platform.lens());
    }

    




    public boolean allowInitiator(byte[] guid8) {
        return pairing != null && pairing.contains(guid8);
    }

    
    public boolean isPairingMode() {
        return pairing != null && pairing.isOpen(now());
    }

    
    public boolean hasPairedInitiator() {
        return pairing != null && pairing.size() > 0;
    }

    public byte[] readObject(String path, int kind, long offset, int length) {
        File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return null;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            return readRange(f, offset, length);
        }
        byte[] whole = mediaBytes(f, path, kind);
        if (whole == null) {
            return null;
        }
        return slice(whole, offset, length);
    }

    












    public PtpIpServer.Handler.ObjectReader openObject(String path, int kind) {
        final File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return null;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            final RandomAccessFile raf;
            try {
                raf = new RandomAccessFile(f, "r");
            } catch (Throwable t) {
                return null;
            }
            final long size = f.length();
            return new PtpIpServer.Handler.ObjectReader() {
                public int readInto(long offset, byte[] dst, int off, int length) {
                    if (offset < 0 || offset > size || length <= 0) {
                        return 0;
                    }
                    int len = (int) Math.min((long) length, size - offset);
                    if (len <= 0) {
                        return 0;
                    }
                    try {
                        raf.seek(offset);
                        int got = 0;
                        while (got < len) {
                            int r = raf.read(dst, off + got, len - got);
                            if (r < 0) {
                                break;
                            }
                            got += r;
                        }
                        return got;
                    } catch (Throwable t) {
                        return -1;
                    }
                }

                public void close() {
                    try {
                        raf.close();
                    } catch (Throwable ignored) {
                    }
                }
            };
        }
        final byte[] whole = mediaBytes(f, path, kind);
        if (whole == null) {
            return null;
        }
        return new PtpIpServer.Handler.ObjectReader() {
            public int readInto(long offset, byte[] dst, int off, int length) {
                if (offset < 0 || offset >= whole.length || length <= 0) {
                    return 0;
                }
                int n = (int) Math.min((long) length, whole.length - offset);
                System.arraycopy(whole, (int) offset, dst, off, n);
                return n;
            }

            public void close() {
                
            }
        };
    }

    public long objectSize(String path, int kind) {
        File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return -1L;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            return f.length();
        }
        byte[] b = mediaBytes(f, path, kind);
        return b == null ? -1L : b.length;
    }

    public long objectMtime(String path) {
        File f = fileOf(path);
        if (f == null || !f.exists()) {
            return -1L;
        }
        return f.lastModified();
    }

    







    public byte[] listDir(String path) {
        File dir = fileOf(path);
        if (dir == null || !dir.isDirectory()) {
            AppLog.w("File", "列目录失败（不存在/不是目录）：" + path);
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        AppLog.i("File", "列目录 " + path + " → " + files.length + " 项");
        List<File> ordered = new ArrayList<File>(files.length);
        for (int i = 0; i < files.length; i++) {
            if (files[i] != null) {
                ordered.add(files[i]);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append(SJson.str("dir")).append(':').append(SJson.str(path));
        sb.append(',').append(SJson.str("entries")).append(":[");
        int n = 0;
        for (int i = 0; i < ordered.size(); i++) {
            File f = ordered.get(i);
            String name = f.getName();
            if (name == null || name.length() == 0) {
                continue;
            }
            boolean isDir = f.isDirectory();
            if (n > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append(SJson.str("name")).append(':').append(SJson.str(name));
            sb.append(',').append(SJson.str("dir")).append(':').append(isDir ? "true" : "false");
            sb.append(',').append(SJson.str("size")).append(':').append(isDir ? 0L : f.length());
            sb.append(',').append(SJson.str("mtime")).append(':').append(f.lastModified());
            sb.append('}');
            n++;
        }
        sb.append("]}");
        return utf8(sb.toString());
    }

    public void onThumbControl(int op, String[] paths) {
        if (prefetcher == null) {
            return;
        }
        switch (op) {
            case PtpCodec.OP_THUMB_QUEUE_BEGIN: {
                List<String> rel = new ArrayList<String>();
                if (paths != null) {
                    for (int i = 0; i < paths.length; i++) {
                        String r = relOf(paths[i]);
                        if (r != null && r.length() > 0) {
                            rel.add(r);
                        }
                    }
                }
                prefetcher.begin(rel);
                break;
            }
            case PtpCodec.OP_THUMB_QUEUE_PAUSE:
                prefetcher.pause();
                break;
            case PtpCodec.OP_THUMB_QUEUE_RESUME:
                prefetcher.resume();
                break;
            case PtpCodec.OP_THUMB_QUEUE_CANCEL:
                prefetcher.cancel();
                break;
            default:
                break;
        }
    }

    public boolean recEnter() {
        RecSession.get().configure(rootDir, null);
        return RecSession.get().enter();
    }

    public void recLeave() {
        RecSession.get().leave();
    }

    public byte[] recState() {
        return RecSession.get().stateJson();
    }

    public int recLvStart() {
        return RecSession.get().startLiveview();
    }

    public void recLvStop() {
        RecSession.get().stopLiveview();
    }

    public String recShoot() {
        return RecSession.get().shoot();
    }

    public boolean recAf(boolean on) {
        return RecSession.get().halfPress(on);
    }

    public boolean recZoom(int dir, int speed) {
        return RecSession.get().zoom(dir, speed);
    }

    public boolean recSetProp(String key, String value) {
        return RecSession.get().setProp(key, value);
    }

    public boolean recTouchAf(float x, float y) {
        return RecSession.get().touchAf(x, y);
    }

    public boolean recMovie(boolean start) {
        return RecSession.get().movie(start);
    }

    public String recError() {
        return RecSession.get().lastError();
    }

    
    
    

    public boolean isDevicePaired(byte[] guid8) {
        return pairing != null && pairing.contains(guid8);
    }

    public boolean isPairingOpen() {
        return pairing != null && pairing.isOpen(now());
    }

    public String pairingCode() {
        return pairing == null ? null : pairing.code(now());
    }

    






    public void onPaired(byte[] guid8, String peerDeviceName) {
        if (pairing == null) {
            return;
        }
        PairingStore.Paired p = new PairingStore.Paired();
        p.peerDeviceId = PtpCodec.hex(guid8);
        p.peerName = safe(peerDeviceName);
        p.pairedAt = now();
        p.lastSeenAt = now();
        pairing.upsert(p);
        pairing.closeWindow();
        PairingEvents pe = pairingEvents;
        if (pe != null) {
            try {
                pe.onPaired(p.peerName);
            } catch (Throwable t) {
                
            }
        }
    }

    






    public void onPairedDeviceSeen(byte[] guid8, String peerDeviceName) {
        if (pairing == null || guid8 == null) {
            return;
        }
        PairingStore.Paired p = pairing.find(guid8);
        if (p == null) {
            return;
        }
        String name = safe(peerDeviceName);
        if (name.length() == 0 || name.equals(p.peerName)) {
            return;
        }
        p.peerName = name;
        p.lastSeenAt = now();
        pairing.upsert(p);
    }

    
    public void onPairingFailed() {
        if (pairing == null) {
            return;
        }
        
        
        int used = pairing.failures() + 1;
        pairing.noteFailure(now());
        boolean closed = !pairing.isOpen(now());
        PairingEvents pe = pairingEvents;
        if (pe != null) {
            try {
                pe.onPairingAttemptFailed(used, PairingStore.MAX_FAILURES, closed);
            } catch (Throwable t) {
                
            }
        }
    }

    public void onPairingAborted() {
    }

    
    public void onUnpair(byte[] guid8) {
        if (pairing == null || guid8 == null) {
            return;
        }
        boolean removed = pairing.remove(guid8);
    }

    
    
    

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    



    static String relOf(String absPath) {
        if (absPath == null || absPath.length() == 0 || absPath.charAt(0) != '/') {
            return null;
        }
        String rel = absPath.substring(1);
        return rel.length() == 0 ? null : rel;
    }

    





    File fileOf(String path) {
        if (rootDir == null || !PtpIpServer.isPathSafe(path)) {
            return null;
        }
        String rel = path.substring(1);
        File f = rel.length() == 0 ? rootDir : new File(rootDir, rel);
        String abs = f.getAbsolutePath();
        String rootAbs = rootDir.getAbsolutePath();
        if (!abs.equals(rootAbs) && !abs.startsWith(rootAbs + File.separator)) {
            return null;
        }
        return f;
    }

    
    private byte[] mediaBytes(File f, String absPath, int kind) {
        if (!ThumbnailExtractor.supports(f)) {
            return null;
        }
        String rel = relOf(absPath);
        if (kind == PtpCodec.KIND_THUMB && prefetcher != null && rel != null) {
            byte[] hit = prefetcher.lookup(rel);
            if (hit != null) {
                return hit;
            }
        }
        
        
        
        
        String key = (rel == null ? absPath : rel);
        synchronized (memoLock) {
            if (key.equals(memoKey) && memoBytes != null) {
                return memoBytes;
            }
        }
        byte[] out;
        try {
            out = kind == PtpCodec.KIND_PREVIEW
                    ? ThumbnailExtractor.extractPreview(f)
                    : ThumbnailExtractor.extractSmall(f);
        } catch (Throwable t) {
            out = null;
        }
        if (out != null && out.length <= MEMO_MAX_BYTES) {
            synchronized (memoLock) {
                memoKey = key;
                memoBytes = out;
            }
        }
        return out;
    }

    private static byte[] slice(byte[] src, long offset, int length) {
        if (src == null || offset < 0 || offset > src.length) {
            return null;
        }
        int start = (int) offset;
        int max = src.length - start;
        int len = (length < 0 || length > max) ? max : length;
        byte[] out = new byte[len];
        System.arraycopy(src, start, out, 0, len);
        return out;
    }

    private static byte[] readRange(File f, long offset, int length) {
        long size = f.length();
        if (offset < 0 || offset > size) {
            return null;
        }
        long max = size - offset;
        int len = (length < 0 || (long) length > max) ? (int) max : length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.seek(offset);
            byte[] out = new byte[len];
            raf.readFully(out);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
