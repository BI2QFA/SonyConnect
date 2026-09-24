package com.sony.scalar.sysutil;

/**
 * 编译桩（compileOnly，绝不打进 APK）：真机由相机框架提供实现。
 *
 * 相机固件的时间工具。2.6 只用到 {@link #getCurrentTimeZone()} 一个入口：
 * 拿它来把 FAT 的"本地时间"折回 UTC（见 {@code CameraTime}）。
 *
 * ★ 取不到时真实实现可能返回 null（相机平台上的防御），调用方按 null 处理。
 */
public final class TimeUtil {

    private TimeUtil() {
    }

    /** 当前时区；拿不到返回 null。 */
    public static PlainTimeZone getCurrentTimeZone() {
        throw new RuntimeException("stub");
    }
}
