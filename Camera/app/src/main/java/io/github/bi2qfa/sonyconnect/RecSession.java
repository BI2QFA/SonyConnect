package io.github.bi2qfa.sonyconnect;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.SurfaceHolder;

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
    private volatile boolean recording;
    private volatile int recSeconds;
    private Thread recTicker;
    private String lastShotPath = "";

    private Class<?> cameraExClass;
    private Object cameraEx;
    private Camera camera;
    private Object mediaRecorder;

    // ★ 官方智能遥控同款帧源：com.sony.scalar.hardware.CameraSequence 原生 JPEG 流
    //   （智能遥控.apk 反编译确认的精确签名）：
    //     CameraSequence.open(CameraEx) → Options.setOption(...) → startPreviewSequence(Options)
    //     → getPreviewSequenceFrames(1) 返回 DeviceMemory[]，元素即 DeviceBuffer：
    //       getSize()I、read(ByteBuffer,II)I、release()V（签名取自 dex method_id 表）
    private Object cameraSeq;
    private Thread seqThread;
    private volatile boolean seqRunning;
    private final java.nio.ByteBuffer seqBuf = java.nio.ByteBuffer.allocateDirect(160 * 1024);

    private LiveviewServer liveview;
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<byte[]>();
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private int previewW;
    private int previewH;
    private SurfaceHolder previewHolder;

    // 取景诊断计数（REC_GET_STATE 可查，真机排障全靠它们）
    private volatile int lvSrc; // 0=无 1=seq 2=cb
    private final AtomicInteger seqFrames = new AtomicInteger();
    private final AtomicInteger cbFrames = new AtomicInteger();
    private final AtomicInteger jpgFrames = new AtomicInteger();
    private volatile String lastSeqErr = "";
    private volatile String lastShootFired = "";
    private volatile String lastShootErr = "";
    private final AtomicBoolean shotRejected = new AtomicBoolean(false);
    // 快门真值诊断：onShutter 状态（-1 未触发）与 capture 是否真的启动
    private final AtomicInteger lastShutterStatus = new AtomicInteger(-1);
    private final AtomicBoolean captureStarted = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
                // ★ A7R2 实测定版：OpenOptions 必须传 null —— 之前那套
                //   setPreview(true)/setInheritSetting/setRecordingMode(0) 自造组合
                //   打开后相机 HAL 不出帧（LCD 全黑、快门无响应）。recipe-lab-sony-pmca
                //   在真机上验证过的就是 open(0, null)。
                cameraExClass = Class.forName("com.sony.scalar.hardware.CameraEx");
                Class<?> optionsCl = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
                Method open = cameraExClass.getMethod("open", int.class, optionsCl);
                cameraEx = open.invoke(null, Integer.valueOf(0), null);
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
                active = true;
                focusStatus = "idle";
                recording = false;
                recSeconds = 0;
                AppLog.i("Rec", "遥控会话已打开");
                emit(EV_PROP, 1, 0);
                // 取景面若已经活着（快速重进），直接把预览接上去
                if (previewHolder != null) {
                    startPreviewToHolder(previewHolder);
                }
                return true;
            } catch (Throwable t) {
                lastError = shortErr(t);
                AppLog.w("Rec", "enter 失败: " + lastError);
                releaseQuiet();
                return false;
            }
        }
    }

    /**
     * 取景面就绪（MainActivity 的 SurfaceView 变为可见时回调进来）。
     * recipe-lab-sony-pmca 的管线：setPreviewDisplay(真实 SurfaceHolder) 后 startPreview，
     * 相机 LCD 才会渲染实时取景 —— 之前用离屏 SurfaceTexture(0) 的写法在 A7R2 上一帧不出。
     */
    public void surfaceCreated(SurfaceHolder h) {
        synchronized (lock) {
            previewHolder = h;
            if (active && camera != null) {
                startPreviewToHolder(h);
            }
        }
    }

    public void surfaceDestroyed() {
        synchronized (lock) {
            previewHolder = null;
            if (camera != null) {
                try {
                    camera.stopPreview();
                } catch (Throwable t) {
                }
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
            emit(EV_PROP, 0, 0);
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
            // 帧源：官方 CameraSequence 原生 JPEG 优先（A7R2 上官方 App 就靠它出流），
            // 打不开再退回 camera1 预览回调的 NV21→JPEG 编码路径
            boolean seq = startSequenceLocked();
            lvSrc = seq ? 1 : 2;
            if (!seq) {
                // 预览回调一个帧都没来过的话再补挂一次（部分 HAL 要求预览启动后才挂回调）
                rearmPreviewCallbackLocked();
            }
            try {
                liveview = new LiveviewServer(latestJpeg);
                liveview.start();
                AppLog.i("Rec", "liveview 端口=" + liveview.port() + " 帧源=" + (seq ? "seq" : "cb"));
                return liveview.port();
            } catch (Throwable t) {
                lastError = shortErr(t);
                liveview = null;
                stopSequenceLocked();
                lvSrc = 0;
                return -1;
            }
        }
    }

    /** 官方 LiveviewLoader.startSequence 同款：open → Options → startPreviewSequence。 */
    private boolean startSequenceLocked() {
        if (seqRunning) {
            return true;
        }
        if (cameraEx == null) {
            lastSeqErr = "cameraEx=null";
            return false;
        }
        stopSequenceLocked();
        try {
            Class<?> seqCl = Class.forName("com.sony.scalar.hardware.CameraSequence");
            Class<?> camExCl = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> optCl = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Object seq = seqCl.getMethod("open", camExCl).invoke(null, cameraEx);
            if (seq == null) {
                lastSeqErr = "open 返回 null";
                AppLog.w("Rec", "CameraSequence.open null");
                return false;
            }
            Object opts = optCl.newInstance();
            Method setOpt = optCl.getMethod("setOption", String.class, int.class);
            setOpt.invoke(opts, "PREVIEW_FRAME_RATE", Integer.valueOf(30000)); // 30.000fps
            setOpt.invoke(opts, "PREVIEW_FRAME_WIDTH", Integer.valueOf(640));
            setOpt.invoke(opts, "PREVIEW_FRAME_HEIGHT", Integer.valueOf(0)); // 0=随宽度自动
            setOpt.invoke(opts, "PREVIEW_FRAME_FORMAT", Integer.valueOf(256)); // JPEG
            setOpt.invoke(opts, "PREVIEW_FRAME_MAX_NUM", Integer.valueOf(1));
            setOpt.invoke(opts, "JPEG_COMPRESS_RATE_DENOM", Integer.valueOf(15));
            setOpt.invoke(opts, "JPEG_COMPRESS_MAX_SIZE", Integer.valueOf(100)); // KB
            seqCl.getMethod("startPreviewSequence", optCl).invoke(seq, opts);
            cameraSeq = seq;
            seqRunning = true;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    sequencePump();
                }
            }, "rec-seq-pump");
            t.setDaemon(true);
            seqThread = t;
            t.start();
            lastSeqErr = "";
            AppLog.i("Rec", "CameraSequence 取流已启动");
            return true;
        } catch (Throwable t) {
            lastSeqErr = shortErr(t);
            cameraSeq = null;
            AppLog.w("Rec", "CameraSequence 失败: " + lastSeqErr);
            return false;
        }
    }

    private void stopSequenceLocked() {
        Thread t = seqThread;
        seqThread = null;
        seqRunning = false;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
            }
        }
        Object seq = cameraSeq;
        cameraSeq = null;
        if (seq != null) {
            try {
                Class<?> seqCl = Class.forName("com.sony.scalar.hardware.CameraSequence");
                seqCl.getMethod("stopPreviewSequence").invoke(seq);
                seqCl.getMethod("release").invoke(seq);
            } catch (Throwable t2) {
            }
        }
    }

    /** 官方 JpegLoader.getJpegData 同款拉帧：getPreviewSequenceFrames(1) → read → release。 */
    private void sequencePump() {
        Method getFrames;
        Method getSize;
        Method read;
        Method release;
        try {
            Class<?> seqCl = Class.forName("com.sony.scalar.hardware.CameraSequence");
            Class<?> bufCl = Class.forName("com.sony.scalar.hardware.DeviceBuffer");
            Class<?> memCl = Class.forName("com.sony.scalar.hardware.DeviceMemory");
            getFrames = seqCl.getMethod("getPreviewSequenceFrames", int.class);
            getSize = bufCl.getMethod("getSize");
            read = bufCl.getMethod("read", java.nio.ByteBuffer.class, int.class, int.class);
            release = memCl.getMethod("release");
        } catch (Throwable t) {
            lastSeqErr = shortErr(t);
            seqRunning = false;
            return;
        }
        while (seqRunning) {
            Object seq = cameraSeq;
            if (seq == null) {
                break;
            }
            try {
                Object framesObj = getFrames.invoke(seq, Integer.valueOf(1));
                if (framesObj instanceof Object[]) {
                    Object[] frames = (Object[]) framesObj;
                    if (frames.length > 0 && frames[0] != null) {
                        int size = ((Integer) getSize.invoke(frames[0])).intValue();
                        if (size > 0 && size <= seqBuf.capacity()) {
                            seqBuf.rewind();
                            read.invoke(frames[0], seqBuf, Integer.valueOf(size), Integer.valueOf(0));
                            byte[] jpeg = new byte[size];
                            seqBuf.rewind();
                            seqBuf.get(jpeg);
                            latestJpeg.set(jpeg);
                            seqFrames.incrementAndGet();
                        }
                        // 官方把整个数组都 release（DeviceMemory 是平台内存，不还就泄漏）
                        for (int i = 0; i < frames.length; i++) {
                            if (frames[i] != null) {
                                try {
                                    release.invoke(frames[i]);
                                } catch (Throwable t) {
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                lastSeqErr = shortErr(t);
            }
            try {
                Thread.sleep(30); // 官方 LIVEVIEW_OBTAINING_INTERVAL=30ms
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** 帧源退回回调路径时补挂一次预览回调（部分 HAL 要求预览起来之后才接受回调）。 */
    private void rearmPreviewCallbackLocked() {
        if (camera == null || previewHolder == null) {
            return;
        }
        try {
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera cam) {
                    enqueueJpeg(data);
                }
            });
            AppLog.i("Rec", "预览回调已补挂");
        } catch (Throwable t) {
            AppLog.w("Rec", "补挂回调: " + shortErr(t));
        }
    }

    public void stopLiveview() {
        synchronized (lock) {
            stopLiveviewLocked();
        }
    }

    public String shoot() {
        synchronized (lock) {
            if (!active || camera == null) {
                lastError = "未进入遥控";
                return null;
            }
            if (recording) {
                lastError = "正在录像";
                return null;
            }
        }
        // 拍前快照 DCIM 最新文件：结果兜底靠"出现了比它新的文件"来判定。
        // 双卡机身（A7R2 有两个卡槽）照片可能落到主卡之外的存储，全部 /android/storage/* + 外置根都扫
        File[] dcims = dcimRoots();
        File newestBefore = newestUnderAll(dcims);
        long shotAt = System.currentTimeMillis();
        synchronized (shotLock) {
            shotPath = null;
            shotJpeg = null;
            shotLatch = new CountDownLatch(1);
        }
        lastShootFired = "";
        lastShootErr = "";
        lastShutterStatus.set(-1);
        captureStarted.set(false);
        shotRejected.set(false);
        // ★ 快门必须在主线程触发：官方走 ExecutorCreator 专用线程、recipe-lab 在 UI
        //   线程。次序反过来：takePicture(null,null,null) 优先（recipe-lab 在真机上
        //   用 open(0,null) 这套环境验证过它能拍照存卡），抛异常才降级官方智能遥控的
        //   burstableTakePicture（上一轮实测它在我们的环境里静默无效）。
        mainHandler.post(new Runnable() {
            public void run() {
                synchronized (lock) {
                    if (cameraEx == null && camera == null) {
                        shotRejected.set(true);
                        return;
                    }
                    boolean fired = false;
                    try {
                        if (camera != null) {
                            camera.takePicture(null, null, null);
                            fired = true;
                            lastShootFired = "takePicture";
                        }
                    } catch (Throwable t) {
                        lastShootErr = shortErr(t);
                    }
                    if (!fired) {
                        fired = invokeSilent(cameraEx, "burstableTakePicture", null, null);
                        if (fired) {
                            lastShootFired = "burstable";
                        }
                    }
                    if (!fired) {
                        shotRejected.set(true);
                    }
                }
            }
        });
        String result = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (result == null && System.currentTimeMillis() < deadline) {
            if (shotRejected.get()) {
                String reason = lastShootErr.length() > 0 ? lastShootErr : "";
                int st = lastShutterStatus.get();
                if (st == 1) {
                    reason = "相机取消了快门";
                } else if (st == 2) {
                    reason = "相机快门错误";
                }
                lastError = "快门未成功" + (reason.length() > 0 ? "（" + reason + "）" : "");
                AppLog.w("Rec", "shoot: " + lastError);
                return null;
            }
            // 路径一：StoreImageCompleteListener / JpegListener 点亮了 latch（精确文件名）
            synchronized (shotLock) {
                if (shotLatch.getCount() == 0) {
                    String ptp = toPtpPath(shotPath);
                    if (ptp == null && shotJpeg != null && shotJpeg.length > 0) {
                        ptp = toPtpPath(dumpJpeg(shotJpeg));
                    }
                    if (ptp != null && ptp.length() > 0) {
                        result = ptp;
                    }
                }
            }
            if (result == null) {
                // 路径二：DCIM 轮询 —— 监听不可用的机型上，新落盘的文件就是答案
                File nf = newestUnderAll(dcims);
                if (nf != null && !nf.equals(newestBefore) && nf.lastModified() >= shotAt - 2000) {
                    String ptp = toPtpPath(nf);
                    if (ptp != null) {
                        result = ptp;
                        synchronized (shotLock) {
                            shotPath = nf.getAbsolutePath();
                        }
                    }
                }
            }
            if (result == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (result == null) {
            // 超时清场：recipe-lab 拍后照做的 cancelTakePicture，把卡在拍照态的 HAL 放回来
            invokeSilent(cameraEx, "cancelTakePicture", null, null);
        }
        restartPreviewQuiet();
        if (result != null) {
            lastShotPath = result;
            emit(EV_SHOT, 1, 0);
            return result;
        }
        // 诊断全在报错里：哪个快门、onShutter 状态、capture 是否启动、抑制位
        lastError = "拍照超时（" + lastShootFired
                + " 快门" + lastShutterStatus.get()
                + " 启动" + (captureStarted.get() ? 1 : 0)
                + " 抑制" + inhibitionInfo() + "）";
        return null;
    }

    /** CameraEx.getInhibitionInfo()（拍照抑制原因位图；0=无抑制，-1=读不到）。 */
    private int inhibitionInfo() {
        try {
            Object r = cameraEx == null
                    ? null
                    : cameraEx.getClass().getMethod("getInhibitionInfo").invoke(cameraEx);
            return r instanceof Integer ? ((Integer) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 所有可能出照片的 DCIM 根：/android/storage/* 各槽 + 外置存储根，去重。 */
    private File[] dcimRoots() {
        List<File> out = new ArrayList<File>();
        if (rootDir != null) {
            addDcim(out, new File(rootDir, "DCIM"));
        }
        try {
            addDcim(out, new File(android.os.Environment.getExternalStorageDirectory(), "DCIM"));
        } catch (Throwable t) {
        }
        try {
            File[] fs = new File("/android/storage").listFiles();
            if (fs != null) {
                for (int i = 0; i < fs.length; i++) {
                    if (fs[i].isDirectory()) {
                        addDcim(out, new File(fs[i], "DCIM"));
                    }
                }
            }
        } catch (Throwable t) {
        }
        return (File[]) out.toArray(new File[out.size()]);
    }

    private void addDcim(List<File> list, File dcim) {
        if (dcim == null || !dcim.isDirectory()) {
            return;
        }
        try {
            String p = dcim.getCanonicalPath();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getCanonicalPath().equals(p)) {
                    return;
                }
            }
        } catch (Throwable t) {
        }
        list.add(dcim);
    }

    private File newestUnderAll(File[] dirs) {
        File best = null;
        for (int i = 0; i < dirs.length; i++) {
            File f = newestUnder(dirs[i], 0);
            if (f != null && (best == null || f.lastModified() > best.lastModified())) {
                best = f;
            }
        }
        return best;
    }

    /** DCIM 下（限深）最新修改的普通文件；用于拍照结果的兜底发现。 */
    private File newestUnder(File dir, int depth) {
        if (dir == null || depth > 3 || !dir.isDirectory()) {
            return null;
        }
        File best = null;
        long bestM = 0;
        File[] fs = dir.listFiles();
        if (fs == null) {
            return null;
        }
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (f.isDirectory()) {
                File sub = newestUnder(f, depth + 1);
                if (sub != null && sub.lastModified() > bestM) {
                    bestM = sub.lastModified();
                    best = sub;
                }
            } else if (f.lastModified() > bestM) {
                bestM = f.lastModified();
                best = f;
            }
        }
        return best;
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
                } else if ("picFmt".equals(key) && mod != null) {
                    // 官方 PictureQualityController 的组合模型：存储格式走
                    // ParametersModifier.setPictureStorageFormat("raw"/"rawjpeg"/"jpeg")，
                    // JPEG 质量走 camera1 Parameters.setJpegQuality(95/50/25)
                    // （官方常量：X.FINE=95、FINE=50、STD=25）
                    String fmt;
                    int q;
                    if ("raw".equals(value)) {
                        fmt = "raw";
                        q = -1;
                    } else if ("raw+jpeg".equals(value)) {
                        fmt = "rawjpeg";
                        q = -1;
                    } else if ("x.fine".equals(value)) {
                        fmt = "jpeg";
                        q = 95;
                    } else if ("std".equals(value)) {
                        fmt = "jpeg";
                        q = 25;
                    } else {
                        fmt = "jpeg";
                        q = 50;
                    }
                    ok = invokeSilent(mod, "setPictureStorageFormat",
                            new Class[]{String.class}, new Object[]{fmt});
                    if (ok && q >= 0 && p != null) {
                        p.setJpegQuality(q);
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
            // 真机排障诊断：帧源、各环节计数、最近一次错误
            SJson.member(sb, "lvSrc", lvSrc == 1 ? "seq" : lvSrc == 2 ? "cb" : "off");
            SJson.member(sb, "seqFrames", seqFrames.get());
            SJson.member(sb, "cbFrames", cbFrames.get());
            SJson.member(sb, "jpgFrames", jpgFrames.get());
            SJson.member(sb, "lvClients", liveview != null ? liveview.clients() : 0);
            SJson.member(sb, "lvSent", liveview != null ? liveview.sentFrames() : 0);
            SJson.member(sb, "shootFired", lastShootFired);
            SJson.member(sb, "shootErr", lastShootErr);
            SJson.member(sb, "seqErr", lastSeqErr);
            SJson.member(sb, "shutterSt", lastShutterStatus.get());
            SJson.member(sb, "capStart", captureStarted.get() ? 1 : 0);
            SJson.member(sb, "inhibit", active ? inhibitionInfo() : 0);
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
                putAnyList(sb, "selfTimerAvail", invoke(mod, "getSupportedSelfTimers", null, null));
                putPicFmt(sb, mod, p);
            }
            return utf8(SJson.endObj(sb));
        }
    }

    private void startPreviewToHolder(SurfaceHolder holder) {
        if (camera == null || holder == null) {
            return;
        }
        try {
            camera.setPreviewDisplay(holder);
        } catch (Throwable t) {
            AppLog.w("Rec", "setPreviewDisplay: " + shortErr(t));
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
        try {
            // 取景回调与显示共存（camera1 语义）：LCD 显示走 SurfaceView，
            // 手机 liveview 走 NV21 帧 → YUV→JPEG
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
            AppLog.i("Rec", "预览已启动 " + previewW + "x" + previewH);
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
        cbFrames.incrementAndGet();
        if (yuv == null || previewW <= 0 || previewH <= 0) {
            return;
        }
        if (seqRunning) {
            // 官方 CameraSequence 帧源已接手，回调路径不再编码（CPU 留给取流）
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
                    jpgFrames.incrementAndGet();
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
                        // 官方 ShootingHandler.ShutterListenerEx 的语义：0=成功 1=取消 2=错误。
                        // 这就是"快门到底有没有真的释放"的权威信号。
                        if ("onShutter".equals(m.getName()) && a != null && a.length > 0
                                && a[0] instanceof Integer) {
                            int st = ((Integer) a[0]).intValue();
                            lastShutterStatus.set(st);
                            AppLog.i("Rec", "onShutter status=" + st);
                            if (st != 0) {
                                shotRejected.set(true);
                            }
                        }
                        return null;
                    }
                });
        installProxy("com.sony.scalar.hardware.CameraEx$OnCaptureStatusListener", "setCaptureStatusListener",
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) {
                        if ("onStart".equals(m.getName())) {
                            captureStarted.set(true);
                            AppLog.i("Rec", "capture onStart");
                        }
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

    private String toPtpPath(String abs) {
        if (abs == null || abs.length() == 0) {
            return null;
        }
        return toPtpPath(new File(abs));
    }

    private String toPtpPath(File f) {
        if (f == null || rootDir == null) {
            return null;
        }
        try {
            String abs = f.getCanonicalPath();
            String root = rootDir.getCanonicalPath();
            if (abs.equals(root)) {
                return "/";
            }
            String prefix = root.endsWith(File.separator) ? root : root + File.separator;
            if (!abs.startsWith(prefix)) {
                return null;
            }
            String rel = abs.substring(prefix.length()).replace('\\', '/');
            return "/" + rel;
        } catch (Throwable t) {
            return null;
        }
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

    /**
     * 拍摄质量（官方 PictureQualityController 的组合模型）→ 统一档位：
     * raw / raw+jpeg / x.fine / fine / std。存储格式来自
     * getPictureStorageFormat，JPEG 质量档来自 camera1 getJpegQuality。
     */
    private void putPicFmt(StringBuilder sb, Object mod, Camera.Parameters p) {
        SJson.member(sb, "picFmt", picFmtLabel(mod, p));
        List<String> avail = new ArrayList<String>();
        Object fmts = invoke(mod, "getSupportedPictureStorageFormats", null, null);
        boolean raw = false, rawjpeg = false, jpeg = false;
        if (fmts instanceof List) {
            List fs = (List) fmts;
            for (int i = 0; i < fs.size(); i++) {
                Object o = fs.get(i);
                if ("raw".equals(o)) {
                    raw = true;
                } else if ("rawjpeg".equals(o)) {
                    rawjpeg = true;
                } else if ("jpeg".equals(o)) {
                    jpeg = true;
                }
            }
        }
        if (raw) {
            avail.add("raw");
        }
        if (rawjpeg) {
            avail.add("raw+jpeg");
        }
        if (jpeg) {
            Object qs = invoke(mod, "getSupportedJpegQualities", null, null);
            boolean xf = false, fi = false, st = false;
            if (qs instanceof List) {
                List q = (List) qs;
                for (int i = 0; i < q.size(); i++) {
                    Object o = q.get(i);
                    if (o instanceof Number) {
                        int n = ((Number) o).intValue();
                        if (n >= 95) {
                            xf = true;
                        } else if (n >= 50) {
                            fi = true;
                        } else if (n > 0) {
                            st = true;
                        }
                    }
                }
            }
            if (xf) {
                avail.add("x.fine");
            }
            if (fi) {
                avail.add("fine");
            }
            if (st) {
                avail.add("std");
            }
            if (!xf && !fi && !st) {
                avail.add("fine"); // 质量表读不到时保底 fine(50)，与官方强制档一致
            }
        }
        putList(sb, "picFmtAvail", avail);
    }

    private String picFmtLabel(Object mod, Camera.Parameters p) {
        Object v = invoke(mod, "getPictureStorageFormat", null, null);
        String s = v == null ? "" : String.valueOf(v);
        if ("raw".equals(s)) {
            return "raw";
        }
        if ("rawjpeg".equals(s)) {
            return "raw+jpeg";
        }
        if ("jpeg".equals(s)) {
            int q = 0;
            try {
                if (p != null) {
                    q = p.getJpegQuality();
                }
            } catch (Throwable t) {
            }
            if (q >= 90) {
                return "x.fine";
            }
            if (q >= 40) {
                return "fine";
            }
            if (q > 0) {
                return "std";
            }
            return "fine";
        }
        return s;
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
        sb.append('[');
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(SJson.str(out.get(i)));
        }
        sb.append(']');
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
                startRecTicker();
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
        recording = false;
        try {
            invokeSilent(mediaRecorder, "stop", null, null);
        } catch (Throwable t) {
        }
        releaseRecorder();
        emit(EV_MOVIE, 0, recSeconds);
        return true;
    }

    private void startRecTicker() {
        recTicker = new Thread(new Runnable() {
            public void run() {
                while (recording) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!recording) {
                        return;
                    }
                    recSeconds++;
                    emit(EV_MOVIE, 1, recSeconds);
                }
            }
        }, "rec-time");
        recTicker.setDaemon(true);
        recTicker.start();
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
        stopSequenceLocked();
        lvSrc = 0;
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
        // 拍照会停预览（camera1 语义）；取景面还挂着才需要重启
        if (previewHolder == null) {
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
        previewHolder = null;
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
        private final AtomicInteger sent = new AtomicInteger();
        private volatile boolean running;

        LiveviewServer(AtomicReference<byte[]> latest) {
            this.latest = latest;
        }

        int port() {
            return server == null ? 0 : server.getLocalPort();
        }

        int clients() {
            return clients.size();
        }

        int sentFrames() {
            return sent.get();
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
                        sent.incrementAndGet();
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
