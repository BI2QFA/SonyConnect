package io.github.bi2qfa.sonyconnect;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

import com.sony.scalar.sysutil.ScalarProperties;

import java.io.File;

/**
 * 设备信息聚合（型号 / 序列号 / 镜头 / 电量 / 地区 / 系统版本 / 存储）。
 *
 * 平台铁律（静态分析定案）：PMCA 应用单进程——绝不派生第二个 Android 进程
 * （历届 :info 探针进程 / Runtime.exec 子进程均触发进程组被杀）。
 *
 * 现行方案：
 *  型号/序列号/固件/平台版本 = ScalarProperties（索尼自带 Java API，键值真值取自
 *  OpenMemories-Framework stubs 与**本机固件 strings 实dump**，PMCADemo 同款）；
 *  **地区 = 原生路**（libsonyinfo.so → OSAL 消息读 backup preset data 的 0xC0，
 *  即 OpenMemories-Tweak「Backup region」同款；scalar 的 sys.dest 只作兜底）；
 *  安卓版本/SDK = android.os.Build（纯 Java 常量）；
 *  镜头 = CameraEx 反射实时读取（换镜头即时生效），EXIF 0xA434 兜底；
 *  电量 = 粘性广播百分比；
 *  存储 = java.io.File.getTotalSpace（statfs）。
 *
 * 分两类：**永不变**的（型号/序列号/固件/地区/平台版本/安卓版本）进程内缓存一次；
 * **会变**的（电量/镜头/SD 占用）每次现取 —— 缓存类别搞混会出现"换卡后容量不变"。
 *
 * ★ 地区是全工程**唯一**走 JNI 的读取点，且只在进程内、每进程一次、整条
 *   try/catch(Throwable)。理由与代价见 {@link #getRegion()} 的注释。
 */
public class DeviceInfo {

    private static final Object lock = new Object();
    private static String cachedModel;
    private static String cachedSerial;
    private static String cachedFirmware;
    /** 永不变的常量属性另锁一份，别和带 TTL 的镜头缓存混在同一个锁里。 */
    private static final Object fixedLock = new Object();
    private static String cachedRegion;
    private static String cachedApiVersion;

    private DeviceInfo() {
    }

    // ===== 对外 API =====

    public static String getModel(Context c, File root) {
        String m = scalar("model.name");
        if (m != null) return m;
        Exif e = exifFallback(root);
        return e == null ? null : e.model;
    }

    /** 序列号：ScalarProperties（本机 EXIF 不写 0xA431，无 EXIF 路） */
    public static String getSerial(Context c, File root) {
        return scalar("model.serial.code");
    }

    public static String getFirmwareVersion() {
        String v = scalarFirmware();
        return v != null ? v : null;
    }

    // ===== 固定信息（地区 / Java API 版本 / 安卓版本 / 存储容量） =====
    //
    // 这些都是"装好之后就不变"的量，随 OP_DEVICE_INFO 一次性下发，**不进 3 秒心跳**
    // （心跳只承担电量与镜头这两个真会变的量）。

    /**
     * 地区（backup region）。
     *
     * ★★ **主路 = OpenMemories-Tweak 的路线**（用户定版："用 tweak 的路线"）：
     * {@code libsonyinfo.so} 经 OSAL 消息 BACKUP_SENSER(0x3E0166) 读 preset data，
     * 校验 0x0C 处 {@code BK2}/{@code BK4}，取 **0xC0** 处的字符串 —— 也就是 Tweak
     * 里「Backup region」显示的那一串（本机固件镜像里是 {@code CX79101_CN2}，
     * 已与 {@code nflasha2/Backup.bin} 偏移 0xC0 逐字节核对过）。
     * 见 {@link NativeInfo#region()}。
     *
     * 兜底 = {@link ScalarProperties} 的 {@code sys.dest}（整数枚举，1=COMMON /
     * 2=CHINA）。**这两条不是同一个数据**：主路是"目的地字符串"（含具体地区码），
     * 兜底只是一个系统目标枚举。之所以留着兜底：原生路要是读不到（OSAL 消息不通、
     * /dev/mem 打不开、头校验不过），有个退化值总比 "—" 有用；但一旦主路能读到，
     * 显示的就是**和 Tweak 一模一样的那串**，不再是派生标签。
     *
     * @return 两路都取不到返回 null（手机端显示 "—"），绝不编造
     */
    public static String getRegion() {
        synchronized (fixedLock) {
            if (cachedRegion != null) return cachedRegion;
        }
        String v = regionFromBackup();
        if (v == null) v = regionFromDestProp();
        if (v != null) {
            synchronized (fixedLock) {
                cachedRegion = v;
            }
        }
        return v;
    }

    /**
     * 主路：原生读 backup preset data 的 0xC0（Tweak 同款）。
     *
     * 整条包 {@code Throwable}（含 {@code UnsatisfiedLinkError}）：真机上这个原生库
     * 历史上带崩过进程，宁可拿不到值也不能让取信息这件事把软件带走。
     */
    private static String regionFromBackup() {
        try {
            if (!NativeInfo.available()) return null;
            String s = NativeInfo.region();
            if (s == null) return null;
            s = s.trim();
            return s.length() > 0 ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 兜底：{@code sys.dest} 枚举。取值与原厂常量 INTVAL_SYS_DEST_* 对齐。 */
    private static String regionFromDestProp() {
        String v = null;
        try {
            int n = ScalarProperties.getInt("sys.dest");
            if (n == 1) v = "COMMON";
            else if (n == 2) v = "CHINA";
            else if (n != 0) v = "DEST " + n;
        } catch (Throwable t) {
            v = null;
        }
        if (v == null) {
            // sys.dest 在部分机型上是字符串型属性：整数读法抛了再试字符串读法
            try {
                String s = ScalarProperties.getString("sys.dest");
                if (s != null && s.length() > 0) v = s;
            } catch (Throwable t) {
            }
        }
        return v;
    }

    /**
     * Java API 版本 = ScalarProperties 的 {@code version.platform}，形如 "2.3"。
     *
     * 键名与解读取自 OpenMemories-Framework（{@code DeviceInfo.CameraDeviceInfo
     * .getPlatform()}：按 "." 切成两段，第一段硬件版本、第二段 API 版本），
     * 与 Tweak 里标注为 "Java API version" 的那个 backup 属性同源。
     * 本机 {@code Property.odex} 的字符串表里确认存在这个键。
     *
     * @return 整串原样返回（不自己劈一半，手机端展示的就是它）；取不到返回 null
     */
    public static String getApiVersion() {
        synchronized (fixedLock) {
            if (cachedApiVersion != null) return cachedApiVersion;
        }
        String v = null;
        try {
            v = ScalarProperties.getString("version.platform");
            if (v != null && v.length() == 0) v = null;
        } catch (Throwable t) {
            v = null;
        }
        if (v != null) {
            synchronized (fixedLock) {
                cachedApiVersion = v;
            }
        }
        return v;
    }

    /** 相机系统安卓版本（本机 "4.1.2"）。纯 Java 常量，不会失败。 */
    public static String getAndroidVersion() {
        return Build.VERSION.RELEASE;
    }

    /** 相机系统安卓 SDK 级别（本机 16）。纯 Java 常量，不会失败。 */
    public static int getAndroidSdk() {
        return Build.VERSION.SDK_INT;
    }

    /**
     * SD 卡用量：{@code [0]}=总容量，{@code [1]}=已用；读不到为 -1。
     *
     * ★ **不缓存**：容量是会变的量（用户中途换卡、边拍边填），而 {@code getRootDir()}
     *   每次调用都重新解析挂载点 —— 缓存下来就会出现"换了卡容量还是旧卡的"
     *   （手机端每个会话只拉一次 DEVICE_INFO，本来就是快照语义，不必再缓存）。
     *
     * 口径：已用 = 总 − 空闲。空闲优先用 {@code getFreeSpace()}（文件管理器口径，
     * 含文件系统保留块）而不是 {@code getUsableSpace()}（那是"本进程还能写多少"，
     * 在相机的 FUSE 挂载上会偏小，算出来的"已用"虚高）。
     *
     * 合理性拦截：总容量为 0 或大于 4 TiB 一律判为读不到（宁缺勿错）。
     * 相机上 {@code getRootDir()} 有时会落到一个**存在但不是挂载点**的空目录，
     * 这时 statfs 报的是父文件系统的大小，看着像真值其实是错的 —— 所以宁可返回
     * -1 让手机端显示 "—"，也不显示一个假容量。
     */
    private static long[] sdUsage(File root) {
        long[] out = new long[] { -1L, -1L };
        if (root == null) return out;
        try {
            long total = root.getTotalSpace();
            long free = root.getFreeSpace();
            if (free <= 0) free = root.getUsableSpace();
            if (total <= 0 || total > 4398046511104L) return out;
            if (free < 0 || free > total) free = 0;
            out[0] = total;
            out[1] = total - free;
        } catch (Throwable t) {
        }
        return out;
    }

    /** SD 卡总容量（字节）；未知 -1。 */
    public static long getSdTotalBytes(File root) {
        return sdUsage(root)[0];
    }

    /** SD 卡已用空间（字节）；未知 -1。 */
    public static long getSdUsedBytes(File root) {
        return sdUsage(root)[1];
    }

    /**
     * 镜头：CameraEx 反射（用户定版：仅此一路，无 EXIF 回退）。
     * 带 2.5 秒 TTL 缓存——运行期间按需周期性重新获取（心跳/INFO 轮询都会
     * 触发），换镜头数秒内生效；又不至于在 3 秒心跳节奏下频繁 open/release。
     * 配方来自索尼 PMCA 手册：CameraEx.open(0, null) → getLensInfo().LensName
     * （public 字段）→ release()。进程内反射调用不违反单进程铁律。
     */
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
            if (RecSession.get().isActive()) {
                String live = RecSession.get().lensName();
                if (live != null && live.length() > 0) {
                    cachedLens = live;
                    lensAt = now;
                    return live;
                }
            }
            Object cam = null;
            String name = null;
            try {
                if (RecSession.get().isActive()) {
                    return cachedLens;
                }
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

    /** 平台剩余分钟（didep.Power 反射已弃用）；协议字段保留，恒 -1 */
    public static int getBatteryRemainMin(Context c) {
        return -1;
    }

    /** 关于页诊断 */
    public static String infoSource(Context c) {
        return (NativeInfo.available() ? "Backup(native)+" : "")
                + "ScalarProperties/Build/statfs";
    }

    // ===== ScalarProperties（键值直用字符串，不依赖桩常量） =====

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
        synchronized (fixedLock) {
            cachedRegion = null;
            cachedApiVersion = null;
        }
    }

    // ===== EXIF 兜底（最新照片的 Make/Model/LensModel） =====

    public static final class Exif {
        public String model;
        public String lens;
    }

    private static File sLastExifFile;
    private static long sLastExifMtime;
    private static Exif sLastExif;

    /** 扫 SD 卡找最新的可解析照片（深度≤5、上限 4000 项），带记忆缓存 */
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
