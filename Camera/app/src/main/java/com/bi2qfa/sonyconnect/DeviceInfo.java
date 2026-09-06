package com.bi2qfa.sonyconnect;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.sony.scalar.sysutil.ScalarProperties;

import java.io.File;

public class DeviceInfo {

    private static final Object lock = new Object();
    private static String cachedModel;
    private static String cachedSerial;
    private static String cachedFirmware;

    private DeviceInfo() {
    }

    public static String getModel(Context c, File root) {
        String m = scalar("model.name");
        if (m != null) return m;
        Exif e = exifFallback(root);
        return e == null ? null : e.model;
    }

    public static String getSerial(Context c, File root) {
        return scalar("model.serial.code");
    }

    public static String getFirmwareVersion() {
        String v = scalarFirmware();
        return v != null ? v : null;
    }

    public static String getLens(Context c, File root) {
        return lensViaCameraEx();
    }

    private static final Object lensLock = new Object();
    private static String cachedLens;
    private static long lensAt;
    private static final long LENS_TTL_MS = 2500;

    private static String lensViaCameraEx() {
        synchronized (lensLock) {
            long now = System.currentTimeMillis();
            if (cachedLens != null && now - lensAt < LENS_TTL_MS) return cachedLens;
            Object cam = null;
            String name = null;
            try {
                Class<?> ex = Class.forName("com.sony.scalar.hardware.CameraEx");
                Class<?> options = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
                java.lang.reflect.Method open = ex.getMethod("open", int.class, options);
                cam = open.invoke(null, Integer.valueOf(0), null);
                if (cam != null) {
                    Object info = cam.getClass().getMethod("getLensInfo").invoke(cam);
                    if (info != null) {
                        java.lang.reflect.Field f = info.getClass().getField("LensName");
                        Object v = f.get(info);
                        if (v instanceof String && ((String) v).length() > 0) name = (String) v;
                    }
                }
            } catch (Throwable t) {
                name = null;
            } finally {
                if (cam != null) {
                    try {
                        cam.getClass().getMethod("release").invoke(cam);
                    } catch (Throwable t) {
                    }
                }
            }
            lensAt = now;
            cachedLens = name;
            return name;
        }
    }

    public static int getBatteryPct(Context c) {
        try {
            Intent b = c.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return -1;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) return level * 100 / scale;
        } catch (Throwable t) {
        }
        return -1;
    }

    public static int getBatteryRemainMin(Context c) {
        return -1;
    }

    public static String infoSource(Context c) {
        return "ScalarProperties/EXIF（纯 Java 单进程）";
    }

    private static String scalar(String key) {
        synchronized (lock) {
            String cached = "model.name".equals(key) ? cachedModel
                    : "model.serial.code".equals(key) ? cachedSerial : null;
            if (cached != null) return cached;
        }
        String v = null;
        try {
            v = ScalarProperties.getString(key);
            if (v != null && v.length() == 0) v = null;
        } catch (Throwable t) {
            v = null;
        }
        if (v != null) {
            synchronized (lock) {
                if ("model.name".equals(key)) cachedModel = v;
                else if ("model.serial.code".equals(key)) cachedSerial = v;
            }
        }
        return v;
    }

    private static String scalarFirmware() {
        synchronized (lock) {
            if (cachedFirmware != null) return cachedFirmware;
        }
        String v = null;
        try {
            v = ScalarProperties.getFirmwareVersion();
            if (v != null && v.length() == 0) v = null;
        } catch (Throwable t) {
            v = null;
        }
        if (v != null) {
            synchronized (lock) {
                cachedFirmware = v;
            }
        }
        return v;
    }

    static void clearDiag(Context c) {
        synchronized (lock) {
            cachedModel = null;
            cachedSerial = null;
            cachedFirmware = null;
        }
    }

    public static final class Exif {
        public String model;
        public String lens;
    }

    private static File sLastExifFile;
    private static long sLastExifMtime;
    private static Exif sLastExif;

    public static Exif exifFallback(File rootDir) {
        try {
            File[] newest = new File[1];
            long[] newestM = {0};
            int[] budget = {4000};
            scan(rootDir, 0, newest, newestM, budget);
            File f = newest[0];
            if (f == null) return null;
            if (f.equals(sLastExifFile) && f.lastModified() == sLastExifMtime && sLastExif != null) {
                return sLastExif;
            }
            ThumbnailExtractor.ExifInfo info = ThumbnailExtractor.readInfo(f);
            if (info == null) return null;
            Exif e = new Exif();
            e.model = info.model;
            e.lens = info.lens;
            sLastExifFile = f;
            sLastExifMtime = f.lastModified();
            sLastExif = e;
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void scan(File dir, int depth, File[] newest, long[] newestM, int[] budget) {
        if (depth > 5 || budget[0] <= 0 || dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (budget[0]-- <= 0) return;
            if (f.isDirectory()) {
                scan(f, depth + 1, newest, newestM, budget);
            } else if (ThumbnailExtractor.supports(f)) {
                long m = f.lastModified();
                if (m > newestM[0]) {
                    newestM[0] = m;
                    newest[0] = f;
                }
            }
        }
    }
}
