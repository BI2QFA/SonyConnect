package com.sony.scalar.sysutil;

/**
 * Sony 私有 API 编译桩（compileOnly，不进 APK）。
 *
 * <p>常量值**不是猜的**：这一份是直接从相机固件里那份库里读出来的 ——
 * {@code framework/com.sony.scalar.sysutil.ScalarInput.odex}（ILCE-6300 固件 v2.01，
 * 19,904 字节；odex 头 0x28 之后就是 dex），解析 dex 的 {@code class_data_item}
 * 静态字段值得到，184 个字段一个不差。此前那一版（照 OpenMemories 核对的 10 个）
 * 与之逐项一致，所以老常量的语义可以放心沿用。
 *
 * <p>转盘拨轮那一族（本轮新增）：
 * <pre>
 *   ISV_DIAL_KURU_STATUS   521   ISV_DIAL_KURU_CLOCKWISE 522   ISV_DIAL_KURU_COUNTERCW 523
 *   ISV_DIAL_1_STATUS      524   ISV_DIAL_1_CLOCKWISE    525   ISV_DIAL_1_COUNTERCW    526
 *   ISV_DIAL_2_STATUS      527   ISV_DIAL_2_CLOCKWISE    528   ISV_DIAL_2_COUNTERCW    529
 *   ISV_DIAL_3_STATUS      633   ISV_DIAL_3_CLOCKWISE    634   ISV_DIAL_3_COUNTERCW    635
 * </pre>
 * {@code *_STATUS} 是"这个转盘的存在/状态广播"，**不是转动事件**，别当转动用。
 * 同一份 dex 里还有个功能枚举叫 {@code FUNC_CONTROL_WHEEL_NEXT/PREVIOUS}、
 * {@code FUNC_THIRD_DIAL_NEXT/PREVIOUS}、 {@code FUNC_LENSRING_NEXT} ——
 * 索尼自己也是把这几个转盘统一当"上一个 / 下一个"用的。
 *
 * <p>{@code KeyStatus} 也在这份库里，它只是个状态壳（{@code status} 字段 +
 * {@code getKeyStatus(int)}、{@code getKeyLogicCode()}、{@code NativeGetKeyStatus}），
 * **没有"转了多少格"这种 API** —— 所以转动只可能以**按键事件**
 * （scanCode = 上面那些 DIAL_*）送到应用的 onKeyDown，不存在"轮询累计角度"的另一条路。
 */
public class ScalarInput {

    // 方向键 / 常用键（本项目用到）
    public static final int ISV_KEY_UP = 103;
    public static final int ISV_KEY_DOWN = 108;
    public static final int ISV_KEY_LEFT = 105;
    public static final int ISV_KEY_RIGHT = 106;
    public static final int ISV_KEY_ENTER = 232;
    public static final int ISV_KEY_DELETE = 595;
    public static final int ISV_KEY_MENU = 514;
    public static final int ISV_KEY_SK1 = 229;
    public static final int ISV_KEY_SK2 = 513;
    public static final int ISV_KEY_PLAY = 207;

    // ===== 转盘拨轮（本轮新增：全局支持转动）=====
    public static final int ISV_DIAL_KURU_STATUS = 521;
    public static final int ISV_DIAL_KURU_CLOCKWISE = 522;
    public static final int ISV_DIAL_KURU_COUNTERCW = 523;
    public static final int ISV_DIAL_1_STATUS = 524;
    public static final int ISV_DIAL_1_CLOCKWISE = 525;
    public static final int ISV_DIAL_1_COUNTERCW = 526;
    public static final int ISV_DIAL_2_STATUS = 527;
    public static final int ISV_DIAL_2_CLOCKWISE = 528;
    public static final int ISV_DIAL_2_COUNTERCW = 529;
    public static final int ISV_DIAL_3_STATUS = 633;
    public static final int ISV_DIAL_3_CLOCKWISE = 634;
    public static final int ISV_DIAL_3_COUNTERCW = 635;

    // ===== 其余常用键（只为日志里认得出名字，程序不据它们做事）=====
    public static final int ISV_KEY_STASTOP = 515;
    public static final int ISV_KEY_S1_1 = 516;
    public static final int ISV_KEY_S1_2 = 517;
    public static final int ISV_KEY_S2 = 518;
    public static final int ISV_KEY_FN = 520;
    public static final int ISV_KEY_DISP = 608;
    public static final int ISV_KEY_AEL = 532;
    public static final int ISV_KEY_CUSTOM1 = 622;
    public static final int ISV_KEY_CUSTOM2 = 623;
    public static final int ISV_KEY_CUSTOM3 = 659;
    public static final int ISV_KEY_MODE_DIAL = 572;
    public static final int ISV_KEY_EV_COMPENSATION = 620;
    public static final int ISV_KEY_IRIS_DIAL = 621;
    public static final int ISV_KEY_UNKNOWN = 767;
    public static final int KEY_NOT_SUPPORTED = -1;

    // 方法（桩内返回 null，运行时由相机系统提供真实实现）
    public static KeyStatus getKeyStatus(int key) { return null; }
}
