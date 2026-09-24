package com.sony.scalar.sysutil;

/**
 * 编译桩（compileOnly，绝不打进 APK）：真机由相机框架提供实现。
 *
 * 相机固件里的"朴素时区"对象：只带两个**分钟**单位的整数字段，没有时区 ID、
 * 没有日期规则——正因如此，它才能回答"这台相机现在认为自己比 UTC 快多少分钟"，
 * 而 Android 的 {@code java.util.TimeZone} 在相机平台上未必拿得到真值。
 *
 * ★ 字段名与类型在真机 dex 里核对过：{@code CameraTime.measure} 编译出来是
 *   {@code iget ... PlainTimeZone;->gmtDiff:I} / {@code ->summerTimeDiff:I}
 *   （**字段**，不是 getter）。
 */
public final class PlainTimeZone {

    /** 相对 GMT 的偏移（分钟）。 */
    public int gmtDiff;

    /** 夏令时附加偏移（分钟）；无夏令时时为 0。 */
    public int summerTimeDiff;
}
