package io.github.bi2qfa.sonyconnect;

import com.sony.scalar.sysutil.PlainTimeZone;
import com.sony.scalar.sysutil.TimeUtil;

/**
 * FAT 文件时间的时区校正（2.6）。
 *
 * <p>相机往存储卡上写的是**相机本地时间**（FAT/exFAT 的 mtime 没有时区概念），
 * 而 Android 的 {@code File.lastModified()} 把它当**本机时区**解释成 UTC 毫秒。
 * 结果就是：手机端列目录看到的时间比照片实际拍摄时间**差一个时区**（夏令时再多一小时）。
 * 照片时间排错时这个偏差极具误导性，所以这里按相机时区把时间**回退**成 UTC。
 *
 * <p>时区偏移从索尼自带的 Java API 取（{@code TimeUtil.getCurrentTimeZone()} →
 * {@code gmtDiff + summerTimeDiff}，单位分钟）。取不到就**不校正**（降级可用，
 * 只写一行日志），绝不让"读时区"这件事把列目录/传输路径搞挂。
 *
 * <p>★ 时区可能变（用户改设置 / 夏令时切换），所以测量结果只保留
 * {@link #REMEASURE_MS} 一分钟：到点重测一次。一分钟内的重复调用走缓存，
 * 列目录上千个文件不会反复问系统。
 */
public final class CameraTime {

    /** 测量结果的有效期（毫秒）。 */
    private static final long REMEASURE_MS = 60000;

    /** 相机时区相对 UTC 的偏移（毫秒）。 */
    private static volatile long zoneMs = 0;

    /** 是否已经测过（失败也算测过，避免每分钟重试刷日志）。 */
    private static volatile boolean measured = false;

    /** 上次测量时刻。 */
    private static volatile long measuredAt = 0;

    private CameraTime() {
    }

    /**
     * 把 {@code File.lastModified()} 的原始值校正成 UTC 毫秒。
     * 偏移为 0（未测到/时区就是 UTC）时原样返回。
     */
    public static long correct(long lastModified) {
        long z = zoneOffsetMs();
        return z == 0 ? lastModified : lastModified - z;
    }

    private static long zoneOffsetMs() {
        long now = System.currentTimeMillis();
        if (!measured || now - measuredAt > REMEASURE_MS) {
            measure(now);
        }
        return zoneMs;
    }

    private static synchronized void measure(long now) {
        {
            try {
                PlainTimeZone tz = TimeUtil.getCurrentTimeZone();
                if (tz != null) {
                    int minutes = tz.gmtDiff + tz.summerTimeDiff;
                    // 分钟 → 毫秒。★ 这里刻意复用 REMEASURE_MS（同为 60000），
                    //   历史上的原样写法，语义是"分钟 × 60000"。
                    zoneMs = minutes * REMEASURE_MS;
                    measured = true;
                    measuredAt = now;
                    AppLog.i("Time", "相机时区偏移 " + minutes + " 分钟，文件时间按此回退");
                }
            } catch (Throwable t) {
                // 取不到时区：不校正（原样返回），只记一次
                measured = true;
                zoneMs = 0L;
                measuredAt = now;
                AppLog.w("Time", "TimeUtil 不可用，文件时间不做校正: " + t);
            }
        }
    }
}
