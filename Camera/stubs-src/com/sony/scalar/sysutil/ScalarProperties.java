package com.sony.scalar.sysutil;

/**
 * 编译桩（compileOnly，绝不打进 APK）：真机由相机框架提供实现。
 *
 * API 真值来自 OpenMemories-Framework stubs（ma1co 反编译相机固件所得），
 * Framework 的 DeviceInfo.CameraDeviceInfo 在所有 Scalar 相机上用它读取
 * 型号/序列号/固件版本——纯 Java、进程内、无原生调用。
 *
 * ★ 下面的键**逐个在本机固件里核对过**：把
 * {@code framework/com.sony.scalar.sysutil.Property.odex} 的字符串表 dump 出来比对，
 * 存在的才写在这里。ma1co 桩里的 {@code PROP_DEST_INFO="version.api"} 与
 * {@code "dest.info"} 在这台 A6300 上**搜不到**，属于别的机型的键，别拿过来用。
 * 地区在这台机上只有 {@code sys.dest} 一个键（见 {@code DeviceInfo.getRegion}）。
 */
public final class ScalarProperties {
    public static final String PROP_MODEL_NAME = "model.name";
    public static final String PROP_MODEL_CODE = "model.code";
    public static final String PROP_MODEL_SERIAL_CODE = "model.serial.code";
    public static final String PROP_VERSION_PLATFORM = "version.platform";
    /** 地区 / 目标市场。整数型：1=COMMON、2=CHINA（对应 INTVAL_SYS_DEST_*）。 */
    public static final String PROP_SYS_DEST = "sys.dest";

    private ScalarProperties() {
    }

    public static String getString(String key) {
        throw new RuntimeException("stub");
    }

    public static String getString(String key, String def) {
        throw new RuntimeException("stub");
    }

    public static int getInt(String key) {
        throw new RuntimeException("stub");
    }

    public static int getInt(String key, int def) {
        throw new RuntimeException("stub");
    }

    /**
     * int 数组型属性。原厂 `BatteryIcon` 就是用它取电量分档阈值的：
     * {@code ScalarProperties.getIntArray("ui.battery.threshold.list")}，
     * 取不到才退回代码里写死的 {80, 50, 20}。
     */
    public static int[] getIntArray(String key) {
        throw new RuntimeException("stub");
    }

    public static String getFirmwareVersion() {
        throw new RuntimeException("stub");
    }
}
