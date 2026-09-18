package com.bi2qfa.sonyconnect;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RecSession {

    public static final int ZOOM_TELE = 0;
    public static final int ZOOM_WIDE = 1;
    public static final int ZOOM_STOP = -1;

    public static final int EV_FOCUS = 1;
    public static final int EV_PROP = 2;
    public static final int EV_SHOT = 3;
    public static final int EV_MOVIE = 4;
    public static final int EV_ERROR = 5;

    public interface Listener {
        void onRecEvent(int kind, int a, int b);
    }

    private static final RecSession INSTANCE = new RecSession();

    public static RecSession get() {
        return INSTANCE;
    }

    private RecSession() {
    }

    private final Object lock = new Object();
    private File rootDir;
    private Listener listener;
    private boolean active;
    private String lastError = "";
    private String lens = "";
    private String focusStatus = "idle";
    private boolean recording;
    private int recSeconds;
    private String lastShotPath = "";

    private Class<?> cameraExClass;
    private Object cameraEx;
    private Camera camera;
    private Object mediaRecorder;

    private LiveviewServer liveview;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<byte[]>();
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private int previewW;
    private int previewH;

    private final Object shotLock = new Object();
    private CountDownLatch shotLatch;
    private String shotPath;
    private byte[] shotJpeg;

    public void configure(File root, Listener l) {
        synchronized (lock) {
            if (root != null) {
                this.rootDir = root;
            }
            if (l != null) {
                this.listener = l;
            }
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    public String lastError() {
        synchronized (lock) {
            return lastError == null ? "" : lastError;
        }
    }

    public String lensName() {
        synchronized (lock) {
            return lens == null ? "" : lens;
        }
    }

    public boolean enter() {
        synchronized (lock) {
            if (active) {
                return true;
            }
            lastError = "";
            try {
                cameraExClass = Class.forName("com.sony.scalar.hardware.CameraEx");
                Class<?> optionsCl = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
                Object options = optionsCl.getConstructor().newInstance();
                invokeSilent(options, "setPreview", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
                invokeSilent(options, "setInheritSetting", new Class[]{boolean.class}, new Object[]{Boolean.TRUE});
                invokeSilent(options, "setRecordingMode", new Class[]{int.class}, new Object[]{Integer.valueOf(0)});
                Method open = cameraExClass.getMethod("open", int.class, optionsCl);
                cameraEx = open.invoke(null, Integer.valueOf(0), options);
                if (cameraEx == null) {
                    lastError = "CameraEx.open 返回 null";
                    return false;
                }
                Object camObj = invoke(cameraEx, "getNormalCamera", null, null);
                if (camObj instanceof Camera) {
                    camera = (Camera) camObj;
                }
                bindListeners();
                readLens();
                startPreviewPipeline();
                active = true;
                focusStatus = "idle";
                recording = false;
                recSeconds = 0;
                AppLog.i("Rec", "遥控会话已打开");
                return true;
            } catch (Throwable t) {
                lastError = shortErr(t);
                AppLog.w("Rec", "enter 失败: " + lastError);
                releaseQuiet();
                return false;
            }
        }
    }

    public void leave() {
        synchronized (lock) {
            if (!active && cameraEx == null) {
                return;
            }
            try {
                stopLiveviewLocked();
            } catch (Throwable t) {
            }
            try {
                movieLocked(false);
            } catch (Throwable t) {
            }
            releaseQuiet();
            active = false;
            focusStatus = "idle";
            AppLog.i("Rec", "遥控会话已关闭");
        }
    }

    public int startLiveview() {
        synchronized (lock) {
            if (!active) {
                lastError = "未进入遥控";
                return -1;
            }
            if (liveview != null && liveview.isRunning()) {
                return liveview.port();
            }
            try {
                liveview = new LiveviewServer(latestJpeg);
                liveview.start();
                AppLog.i("Rec", "liveview 端口=" + liveview.port());
                return liveview.port();
            } catch (Throwable t) {
                lastError = shortErr(t);
                liveview = null;
                return -1;
            }
        }
    }

    public void stopLiveview() {
        synchronized (lock) {
            stopLiveviewLocked();
        }
    }

    public String shoot() {
        synchronized (lock) {
            if (!active || cameraEx == null) {
                lastError = "未进入遥控";
                return null;
            }
            if (recording) {
                lastError = "正在录像";
                return null;
            }
        }
        synchronized (shotLock) {
            shotPath = null;
            shotJpeg = null;
            shotLatch = new CountDownLatch(1);
        }
        try {
            boolean ok = false;
            synchronized (lock) {
                ok = invokeSilent(cameraEx, "burstableTakePicture", null, null);
                if (!ok && camera != null) {
                    try {
                        camera.takePicture(null, null, new Camera.PictureCallback() {
                            public void onPictureTaken(byte[] data, Camera c) {
                                onJpeg(data);
                                restartPreviewQuiet();
                            }
                        });
                        ok = true;
                    } catch (Throwable t) {
                        lastError = shortErr(t);
                    }
                }
            }
            if (!ok) {
                return null;
            }
            CountDownLatch latch;
            synchronized (shotLock) {
                latch = shotLatch;
            }
            latch.await(20, TimeUnit.SECONDS);
            synchronized (shotLock) {
                if (shotPath != null && shotPath.length() > 0) {
                    lastShotPath = shotPath;
                    emit(EV_SHOT, 1, 0);
                    return shotPath;
                }
                if (shotJpeg != null && shotJpeg.length > 0) {
                    File dumped = dumpJpeg(shotJpeg);
                    if (dumped != null) {
                        lastShotPath = dumped.getAbsolutePath();
                        emit(EV_SHOT, 1, 0);
                        return lastShotPath;
                    }
                }
            }
            lastError = "拍照超时";
            return null;
        } catch (InterruptedException e) {
            lastError = "拍照中断";
            return null;
        } catch (Throwable t) {
            lastError = shortErr(t);
            return null;
        }
    }

    public boolean halfPress(boolean on) {
        synchronized (lock) {
            if (!active || cameraEx == null) {
                lastError = "未进入遥控";
                return false;
            }
            if (on) {
                focusStatus = "working";
                boolean ok = invokeSilent(cameraEx, "executeAutoFocusStartTrigger",
                        new Class[]{boolean.class, String.class},
                        new Object[]{Boolean.TRUE, null});
                if (!ok && camera != null) {
                    try {
                        camera.autoFocus(new Camera.AutoFocusCallback() {
                            public void onAutoFocus(boolean success, Camera c) {
                                focusStatus = success ? "lock" : "idle";
                                emit(EV_FOCUS, success ? 1 : 0, 0);
                            }
                        });
                        ok = true;
                    } catch (Throwable t) {
                        lastError = shortErr(t);
                    }
                }
                invokeSilent(cameraEx, "startDirectShutter", null, null);
                return ok;
            }
            focusStatus = "idle";
            if (camera != null) {
                try {
                    camera.cancelAutoFocus();
                } catch (Throwable t) {
                }
            }
            invokeSilent(cameraEx, "executeAutoFocusStartTrigger",
                    new Class[]{boolean.class, String.class},
                    new Object[]{Boolean.FALSE, null});
            Class<?> cb = load("com.sony.scalar.hardware.CameraEx$DirectShutterStoppedCallback");
            invokeSilent(cameraEx, "stopDirectShutter", new Class[]{cb}, new Object[]{null});
            emit(EV_FOCUS, 0, 0);
            return true;
        }
    }

    public boolean zoom(int dir, int speed) {
        synchronized (lock) {
            if (!active || cameraEx == null) {
                lastError = "未进入遥控";
                return false;
            }
            if (dir == ZOOM_STOP) {
                return invokeSilent(cameraEx, "stopZoom", null, null);
            }
            int d = dir == ZOOM_WIDE ? 1 : 0;
            int sp = speed <= 0 ? 1 : speed;
            return invokeSilent(cameraEx, "startZoom",
                    new Class[]{int.class, int.class},
                    new Object[]{Integer.valueOf(d), Integer.valueOf(sp)});
        }
    }

    public boolean touchAf(float nx, float ny) {
        synchronized (lock) {
            if (!active || cameraEx == null) {
                lastError = "未进入遥控";
                return false;
            }
            if (nx < 0 || ny < 0) {
                invokeSilent(cameraEx, "stopTrackingFocus", null, null);
                return true;
            }
            int w = previewW > 0 ? previewW : 640;
            int h = previewH > 0 ? previewH : 480;
            int x = (int) (nx * w);
            int y = (int) (ny * h);
            if (x < 0) x = 0;
            if (y < 0) y = 0;
            if (x >= w) x = w - 1;
            if (y >= h) y = h - 1;
            return invokeSilent(cameraEx, "startTrackingFocus",
                    new Class[]{int.class, int.class},
                    new Object[]{Integer.valueOf(x), Integer.valueOf(y)});
        }
    }

    public boolean movie(boolean start) {
        synchronized (lock) {
            return movieLocked(start);
        }
    }

    public boolean setProp(String key, String value) {
        synchronized (lock) {
            if (!active) {
                lastError = "未进入遥控";
                return false;
            }
            if (key == null || value == null) {
                return false;
            }
            try {
                if ("fnumber".equals(key)) {
                    if ("+".equals(value)) {
                        boolean ok = invokeSilent(cameraEx, "incrementAperture", null, null);
                        if (ok) emit(EV_PROP, 0, 0);
                        return ok;
                    }
                    if ("-".equals(value)) {
                        boolean ok = invokeSilent(cameraEx, "decrementAperture", null, null);
                        if (ok) emit(EV_PROP, 0, 0);
                        return ok;
                    }
                    return stepToward("getAperture", "incrementAperture", "decrementAperture",
                            parseAperture(value));
                }
                if ("shutter".equals(key)) {
                    if ("+".equals(value)) {
                        boolean ok = invokeSilent(cameraEx, "incrementShutterSpeed", null, null);
                        if (ok) emit(EV_PROP, 0, 0);
                        return ok;
                    }
                    if ("-".equals(value)) {
                        boolean ok = invokeSilent(cameraEx, "decrementShutterSpeed", null, null);
                        if (ok) emit(EV_PROP, 0, 0);
                        return ok;
                    }
                    return stepShutter(value);
                }
                Camera.Parameters p = camera == null ? null : camera.getParameters();
                Object mod = modifier(p);
                boolean ok = false;
                if ("iso".equals(key)) {
                    ok = invokeSilent(mod, "setISOSensitivity",
                            new Class[]{int.class}, new Object[]{Integer.valueOf(parseInt(value, 0))});
                } else if ("focusMode".equals(key)) {
                    ok = invokeSilent(mod, "setAutoFocusMode",
                            new Class[]{String.class}, new Object[]{value});
                    if (!ok && p != null) {
                        p.setFocusMode(value);
                        ok = true;
                    }
                } else if ("selfTimer".equals(key)) {
                    ok = invokeSilent(mod, "setSelfTimer",
                            new Class[]{int.class}, new Object[]{Integer.valueOf(parseInt(value, 0))});
                } else if ("expComp".equals(key) && p != null) {
                    p.setExposureCompensation(parseInt(value, 0));
                    ok = true;
                } else if ("wb".equals(key) && p != null) {
                    p.setWhiteBalance(value);
                    ok = true;
                } else if ("flash".equals(key) && p != null) {
                    p.setFlashMode(value);
                    ok = true;
                } else if ("expMode".equals(key) && p != null) {
                    try {
                        p.setSceneMode(value);
                        ok = true;
                    } catch (Throwable t) {
                    }
                }
                if (ok && camera != null && p != null) {
                    camera.setParameters(p);
                }
                if (ok) {
                    emit(EV_PROP, 0, 0);
                }
                return ok;
            } catch (Throwable t) {
                lastError = shortErr(t);
                return false;
            }
        }
    }

    public byte[] stateJson() {
        synchronized (lock) {
            StringBuilder sb = SJson.startObj();
            SJson.member(sb, "active", active);
            SJson.member(sb, "error", lastError());
            SJson.member(sb, "lens", lensName());
            SJson.member(sb, "focus", focusStatus);
            SJson.member(sb, "recording", recording);
            SJson.member(sb, "recSeconds", recSeconds);
            SJson.member(sb, "lastShot", lastShotPath);
            int lv = (liveview != null && liveview.isRunning()) ? liveview.port() : 0;
            SJson.member(sb, "lvPort", lv);
            Camera.Parameters p = null;
            Object mod = null;
            if (active && camera != null) {
                try {
                    p = camera.getParameters();
                    mod = modifier(p);
                } catch (Throwable t) {
                }
            }
            putIso(sb, mod);
            putAperture(sb, mod);
            putShutter(sb, mod);
            putModString(sb, mod, "focusMode", "getAutoFocusMode", "getSupportedAutoFocusModes");
            if (p != null) {
                SJson.member(sb, "wb", safe(p.getWhiteBalance()));
                putList(sb, "wbAvail", p.getSupportedWhiteBalance());
                SJson.member(sb, "flash", safe(p.getFlashMode()));
                putList(sb, "flashAvail", p.getSupportedFlashModes());
                SJson.member(sb, "expComp", String.valueOf(p.getExposureCompensation()));
                SJson.member(sb, "expCompMin", p.getMinExposureCompensation());
                SJson.member(sb, "expCompMax", p.getMaxExposureCompensation());
                SJson.member(sb, "expMode", safe(p.getSceneMode()));
                putList(sb, "expModeAvail", p.getSupportedSceneModes());
            }
            if (mod != null) {
                Object st = invoke(mod, "getSelfTimer", null, null);
                SJson.member(sb, "selfTimer", st == null ? "0" : String.valueOf(st));
            }
            return utf8(SJson.endObj(sb));
        }
    }

    private void startPreviewPipeline() {
        if (camera == null) {
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = pickPreview(p.getSupportedPreviewSizes());
            if (best != null) {
                p.setPreviewSize(best.width, best.height);
                previewW = best.width;
                previewH = best.height;
            } else {
                Camera.Size cur = p.getPreviewSize();
                if (cur != null) {
                    previewW = cur.width;
                    previewH = cur.height;
                }
            }
            List<Integer> formats = p.getSupportedPreviewFormats();
            if (formats != null && formats.contains(Integer.valueOf(ImageFormat.NV21))) {
                p.setPreviewFormat(ImageFormat.NV21);
            }
            camera.setParameters(p);
        } catch (Throwable t) {
            AppLog.w("Rec", "预览参数: " + shortErr(t));
        }
        if (Build.VERSION.SDK_INT >= 11) {
            try {
                Class<?> stCl = Class.forName("android.graphics.SurfaceTexture");
                Object st = stCl.getConstructor(int.class).newInstance(Integer.valueOf(0));
                Method setTex = Camera.class.getMethod("setPreviewTexture", stCl);
                setTex.invoke(camera, st);
            } catch (Throwable t) {
            }
        }
        try {
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera cam) {
                    enqueueJpeg(data);
                }
            });
        } catch (Throwable t) {
            AppLog.w("Rec", "setPreviewCallback: " + shortErr(t));
        }
        try {
            camera.startPreview();
        } catch (Throwable t) {
            AppLog.w("Rec", "startPreview: " + shortErr(t));
        }
    }

    private Camera.Size pickPreview(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < sizes.size(); i++) {
            Camera.Size s = sizes.get(i);
            int score = Math.abs(s.width - 640) + Math.abs(s.height - 480);
            if (s.width * s.height > 640 * 480) {
                score += 1000;
            }
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void enqueueJpeg(byte[] yuv) {
        if (yuv == null || previewW <= 0 || previewH <= 0) {
            return;
        }
        if (liveview == null || !liveview.isRunning()) {
            return;
        }
        if (!encoding.compareAndSet(false, true)) {
            return;
        }
        final byte[] src = yuv;
        final int w = previewW;
        final int h = previewH;
        new Thread(new Runnable() {
            public void run() {
                try {
                    YuvImage img = new YuvImage(src, ImageFormat.NV21, w, h, null);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
                    img.compressToJpeg(new Rect(0, 0, w, h), 55, bos);
                    latestJpeg.set(bos.toByteArray());
                } catch (Throwable t) {
                } finally {
                    encoding.set(false);
                }
            }
        }, "rec-jpeg").start();
    }

    private void bindListeners() {
        installProxy("com.sony.scalar.hardware.CameraEx$JpegListener", "setJpegListener",
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("onPictureTaken".equals(m.getName()) && a != null && a.length > 0
                                && a[0] instanceof byte[]) {
                            onJpeg((byte[]) a[0]);
                        }
                        return null;
                    }
                });
        installProxy("com.sony.scalar.hardware.CameraEx$StoreImageCompleteListener",
                "setStoreImageCompleteListener",
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("onDone".equals(m.getName()) && a != null && a.length > 1) {
                            onStored(a[1]);
                        }
                        return null;
                    }
                });
        installProxy("com.sony.scalar.hardware.CameraEx$AutoFocusDoneListener",
                "setAutoFocusDoneListener",
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("onDone".equals(m.getName()) && a != null && a.length > 0
                                && a[0] instanceof Integer) {
                            int st = ((Integer) a[0]).intValue();
                            if (st == 1 || st == 2) {
                                focusStatus = "lock";
                            } else if (st == 3 || st == 4) {
                                focusStatus = "working";
                            } else {
                                focusStatus = "idle";
                            }
                            emit(EV_FOCUS, st, 0);
                        }
                        return null;
                    }
                });
        installProxy("com.sony.scalar.hardware.CameraEx$ShutterListener", "setShutterListener",
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        return null;
                    }
                });
    }

    private void installProxy(String ifaceName, String setter, InvocationHandler h) {
        Class<?> iface = load(ifaceName);
        if (iface == null || cameraEx == null) {
            return;
        }
        try {
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface}, h);
            Method m = cameraExClass.getMethod(setter, iface);
            m.invoke(cameraEx, proxy);
        } catch (Throwable t) {
            AppLog.w("Rec", setter + " 失败: " + shortErr(t));
        }
    }

    private void onJpeg(byte[] data) {
        synchronized (shotLock) {
            shotJpeg = data;
            if (shotLatch != null) {
                shotLatch.countDown();
            }
        }
    }

    private void onStored(Object info) {
        if (info == null) {
            return;
        }
        String dir = fieldStr(info, "DirectoryName");
        String name = fieldStr(info, "FileName");
        File f = resolveShot(dir, name);
        synchronized (shotLock) {
            if (f != null) {
                shotPath = f.getAbsolutePath();
            }
            if (shotLatch != null) {
                shotLatch.countDown();
            }
        }
    }

    private File resolveShot(String dir, String name) {
        if (name != null && name.length() > 0) {
            File abs = new File(name);
            if (abs.isAbsolute() && abs.isFile()) {
                return abs;
            }
            if (dir != null && dir.length() > 0) {
                File a = new File(dir, name);
                if (a.isFile()) {
                    return a;
                }
                if (rootDir != null) {
                    File b = new File(new File(rootDir, dir), name);
                    if (b.isFile()) {
                        return b;
                    }
                    File c = new File(new File(new File(rootDir, "DCIM"), dir), name);
                    if (c.isFile()) {
                        return c;
                    }
                }
            }
            File found = findNamed(rootDir, name, 6, new int[]{4000});
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private File findNamed(File dir, String name, int depth, int[] budget) {
        if (dir == null || depth < 0 || budget[0] <= 0 || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (int i = 0; i < files.length; i++) {
            if (budget[0]-- <= 0) {
                return null;
            }
            File f = files[i];
            if (f.isDirectory()) {
                File hit = findNamed(f, name, depth - 1, budget);
                if (hit != null) {
                    return hit;
                }
            } else if (name.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    private File dumpJpeg(byte[] jpeg) {
        try {
            File dir = rootDir != null ? new File(rootDir, "DCIM/SonyConnect") : null;
            if (dir == null) {
                return null;
            }
            dir.mkdirs();
            File f = new File(dir, "REC" + System.currentTimeMillis() + ".JPG");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            try {
                fos.write(jpeg);
            } finally {
                fos.close();
            }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private void readLens() {
        try {
            Object info = invoke(cameraEx, "getLensInfo", null, null);
            lens = fieldStr(info, "LensName");
            if (lens == null) {
                lens = "";
            }
        } catch (Throwable t) {
            lens = "";
        }
    }

    private Object modifier(Camera.Parameters p) {
        if (cameraEx == null || p == null) {
            return null;
        }
        return invoke(cameraEx, "createParametersModifier",
                new Class[]{Camera.Parameters.class}, new Object[]{p});
    }

    private void putIso(StringBuilder sb, Object mod) {
        Object v = invoke(mod, "getISOSensitivity", null, null);
        SJson.member(sb, "iso", v == null ? "" : String.valueOf(v));
        Object list = invoke(mod, "getSupportedISOSensitivities", null, null);
        putAnyList(sb, "isoAvail", list);
    }

    private void putAperture(StringBuilder sb, Object mod) {
        Object v = invoke(mod, "getAperture", null, null);
        SJson.member(sb, "fnumber", formatAperture(v));
    }

    private void putShutter(StringBuilder sb, Object mod) {
        Object v = invoke(mod, "getShutterSpeed", null, null);
        SJson.member(sb, "shutter", formatShutter(v));
    }

    private void putModString(StringBuilder sb, Object mod, String key, String get, String avail) {
        Object v = invoke(mod, get, null, null);
        SJson.member(sb, key, v == null ? "" : String.valueOf(v));
        putAnyList(sb, key + "Avail", invoke(mod, avail, null, null));
    }

    private void putList(StringBuilder sb, String key, List list) {
        if (sb.length() > 1) {
            sb.append(',');
        }
        sb.append(SJson.str(key)).append(':');
        if (list == null) {
            sb.append("[]");
            return;
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        sb.append(SJson.strArray(out));
    }

    private void putAnyList(StringBuilder sb, String key, Object listObj) {
        if (listObj instanceof List) {
            putList(sb, key, (List) listObj);
            return;
        }
        if (sb.length() > 1) {
            sb.append(',');
        }
        sb.append(SJson.str(key)).append(':').append("[]");
    }

    private static String formatAperture(Object v) {
        if (!(v instanceof Number)) {
            return v == null ? "" : String.valueOf(v);
        }
        int n = ((Number) v).intValue();
        if (n <= 0) {
            return "";
        }
        double f = n >= 80 ? n / 100.0 : n / 10.0;
        if (Math.abs(f - Math.round(f)) < 0.05) {
            return String.valueOf((int) Math.round(f));
        }
        return String.valueOf(Math.round(f * 10) / 10.0);
    }

    private static String formatShutter(Object v) {
        if (v instanceof Pair) {
            Pair pr = (Pair) v;
            Object a = pr.first;
            Object b = pr.second;
            if (a instanceof Number && b instanceof Number) {
                int n = ((Number) a).intValue();
                int d = ((Number) b).intValue();
                if (d <= 1) {
                    return String.valueOf(n);
                }
                return n + "/" + d;
            }
        }
        return v == null ? "" : String.valueOf(v);
    }

    private int parseAperture(String s) {
        try {
            double f = Double.parseDouble(s);
            return (int) Math.round(f * 100);
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean stepToward(String getter, String inc, String dec, int target) {
        if (target <= 0 || cameraEx == null) {
            return false;
        }
        for (int i = 0; i < 80; i++) {
            Camera.Parameters p = camera == null ? null : camera.getParameters();
            Object mod = modifier(p);
            Object cur = invoke(mod, getter, null, null);
            int now = cur instanceof Number ? ((Number) cur).intValue() : 0;
            if (now == 0) {
                return false;
            }
            if (Math.abs(now - target) <= 2) {
                return true;
            }
            if (now < target) {
                invokeSilent(cameraEx, inc, null, null);
            } else {
                invokeSilent(cameraEx, dec, null, null);
            }
        }
        return true;
    }

    private boolean stepShutter(String value) {
        double target = parseShutterSec(value);
        if (target <= 0 || cameraEx == null) {
            return false;
        }
        for (int i = 0; i < 80; i++) {
            Camera.Parameters p = camera == null ? null : camera.getParameters();
            Object mod = modifier(p);
            double now = parseShutterSec(formatShutter(invoke(mod, "getShutterSpeed", null, null)));
            if (now <= 0) {
                return false;
            }
            double ratio = now / target;
            if (ratio > 0.92 && ratio < 1.08) {
                return true;
            }
            if (now < target) {
                invokeSilent(cameraEx, "incrementShutterSpeed", null, null);
            } else {
                invokeSilent(cameraEx, "decrementShutterSpeed", null, null);
            }
        }
        return true;
    }

    private static double parseShutterSec(String s) {
        if (s == null || s.length() == 0) {
            return 0;
        }
        try {
            int slash = s.indexOf('/');
            if (slash > 0) {
                double n = Double.parseDouble(s.substring(0, slash));
                double d = Double.parseDouble(s.substring(slash + 1));
                if (d == 0) {
                    return 0;
                }
                return n / d;
            }
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean movieLocked(boolean start) {
        if (!active) {
            lastError = "未进入遥控";
            return false;
        }
        if (start) {
            if (recording) {
                return true;
            }
            try {
                Class<?> mrCl = Class.forName("com.sony.scalar.media.MediaRecorder");
                mediaRecorder = mrCl.getConstructor().newInstance();
                invokeSilent(mediaRecorder, "setCamera", new Class[]{cameraExClass},
                        new Object[]{cameraEx});
                int vs = constInt("com.sony.scalar.media.MediaRecorder$VideoSource", "CAMERA", 1);
                int as = constInt("com.sony.scalar.media.MediaRecorder$AudioSource", "CAMCORDER", 5);
                invokeSilent(mediaRecorder, "setVideoSource", new Class[]{int.class},
                        new Object[]{Integer.valueOf(vs)});
                invokeSilent(mediaRecorder, "setAudioSource", new Class[]{int.class},
                        new Object[]{Integer.valueOf(as)});
                invoke(mediaRecorder, "prepare", null, null);
                invoke(mediaRecorder, "start", null, null);
                recording = true;
                recSeconds = 0;
                emit(EV_MOVIE, 1, 0);
                return true;
            } catch (Throwable t) {
                lastError = shortErr(t);
                releaseRecorder();
                recording = false;
                return false;
            }
        }
        if (!recording && mediaRecorder == null) {
            return true;
        }
        try {
            invokeSilent(mediaRecorder, "stop", null, null);
        } catch (Throwable t) {
        }
        releaseRecorder();
        recording = false;
        emit(EV_MOVIE, 0, recSeconds);
        return true;
    }

    private void releaseRecorder() {
        if (mediaRecorder == null) {
            return;
        }
        invokeSilent(mediaRecorder, "reset", null, null);
        invokeSilent(mediaRecorder, "release", null, null);
        mediaRecorder = null;
    }

    private void stopLiveviewLocked() {
        if (liveview != null) {
            liveview.stop();
            liveview = null;
        }
        latestJpeg.set(null);
    }

    private void restartPreviewQuiet() {
        if (camera == null) {
            return;
        }
        try {
            camera.startPreview();
        } catch (Throwable t) {
        }
    }

    private void releaseQuiet() {
        stopLiveviewLocked();
        releaseRecorder();
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
            } catch (Throwable t) {
            }
            try {
                camera.stopPreview();
            } catch (Throwable t) {
            }
            camera = null;
        }
        if (cameraEx != null) {
            invokeSilent(cameraEx, "release", null, null);
            cameraEx = null;
        }
    }

    private void emit(int kind, int a, int b) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onRecEvent(kind, a, b);
            } catch (Throwable t) {
            }
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int constInt(String cls, String field, int dft) {
        try {
            Field f = Class.forName(cls).getField(field);
            return f.getInt(null);
        } catch (Throwable t) {
            return dft;
        }
    }

    private static Object invoke(Object target, String name, Class[] types, Object[] args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = types == null
                    ? target.getClass().getMethod(name)
                    : target.getClass().getMethod(name, types);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean invokeSilent(Object target, String name, Class[] types, Object[] args) {
        if (target == null) {
            return false;
        }
        try {
            Method m = types == null
                    ? target.getClass().getMethod(name)
                    : target.getClass().getMethod(name, types);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fieldStr(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Field f = obj.getClass().getField(name);
            Object v = f.get(obj);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseInt(String s, int dft) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return dft;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String shortErr(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        String n = c.getClass().getSimpleName();
        if (m == null || m.length() == 0) {
            return n;
        }
        return n + ": " + m;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    static final class LiveviewServer {
        private final AtomicReference<byte[]> latest;
        private ServerSocket server;
        private Thread acceptThread;
        private Thread pumpThread;
        private final List<OutputStream> clients = Collections.synchronizedList(new ArrayList<OutputStream>());
        private final AtomicInteger seq = new AtomicInteger();
        private volatile boolean running;

        LiveviewServer(AtomicReference<byte[]> latest) {
            this.latest = latest;
        }

        int port() {
            return server == null ? 0 : server.getLocalPort();
        }

        boolean isRunning() {
            return running;
        }

        void start() throws IOException {
            server = new ServerSocket(0);
            running = true;
            acceptThread = new Thread(new Runnable() {
                public void run() {
                    acceptLoop();
                }
            }, "rec-lv-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            pumpThread = new Thread(new Runnable() {
                public void run() {
                    pumpLoop();
                }
            }, "rec-lv-pump");
            pumpThread.setDaemon(true);
            pumpThread.start();
        }

        void stop() {
            running = false;
            try {
                if (server != null) {
                    server.close();
                }
            } catch (Throwable t) {
            }
            synchronized (clients) {
                for (int i = 0; i < clients.size(); i++) {
                    try {
                        clients.get(i).close();
                    } catch (Throwable t) {
                    }
                }
                clients.clear();
            }
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket s = server.accept();
                    try {
                        s.setTcpNoDelay(true);
                    } catch (Throwable t) {
                    }
                    clients.add(s.getOutputStream());
                } catch (Throwable t) {
                    if (!running) {
                        return;
                    }
                }
            }
        }

        private void pumpLoop() {
            byte[] last = null;
            while (running) {
                byte[] jpeg = latest.get();
                if (jpeg != null && jpeg != last && jpeg.length > 0) {
                    last = jpeg;
                    broadcast(jpeg);
                }
                try {
                    Thread.sleep(70);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private void broadcast(byte[] jpeg) {
            byte[] frame = wrapSonyLiveview(seq.incrementAndGet() & 0xFFFF, jpeg);
            List<OutputStream> dead = null;
            synchronized (clients) {
                for (int i = 0; i < clients.size(); i++) {
                    OutputStream out = clients.get(i);
                    try {
                        out.write(frame);
                        out.flush();
                    } catch (Throwable t) {
                        if (dead == null) {
                            dead = new ArrayList<OutputStream>();
                        }
                        dead.add(out);
                    }
                }
                if (dead != null) {
                    clients.removeAll(dead);
                }
            }
        }

        static byte[] wrapSonyLiveview(int sequence, byte[] jpeg) {
            byte[] out = new byte[8 + 128 + jpeg.length];
            out[0] = (byte) 0xFF;
            out[1] = 0x01;
            out[2] = (byte) ((sequence >> 8) & 0xFF);
            out[3] = (byte) (sequence & 0xFF);
            int ts = (int) System.currentTimeMillis();
            out[4] = (byte) ((ts >> 24) & 0xFF);
            out[5] = (byte) ((ts >> 16) & 0xFF);
            out[6] = (byte) ((ts >> 8) & 0xFF);
            out[7] = (byte) (ts & 0xFF);
            out[8] = 0x24;
            out[9] = 0x35;
            out[10] = 0x68;
            out[11] = 0x79;
            int n = jpeg.length;
            out[12] = (byte) ((n >> 16) & 0xFF);
            out[13] = (byte) ((n >> 8) & 0xFF);
            out[14] = (byte) (n & 0xFF);
            out[15] = 0;
            System.arraycopy(jpeg, 0, out, 136, jpeg.length);
            return out;
        }
    }
}
