package com.bi2qfa.sonyconnect;

/**
 * {@code libsonyinfo.so} 的 JNI 入口（索尼 Backup 存储直读）。
 *
 * 方法链与 OpenMemories-Tweak **逐字同款**（详见 {@code native/sonyinfo.c}）：
 * 链接期用空 stub 通过链接、运行时由相机 {@code /system/lib/libosal_uipc.so}
 * 真库解析 OSAL 符号（{@code native/osal_uipc.S}）。
 *
 * ★★ 三个必须守住的点（前两条是真机踩出来的）：
 * <ol>
 *   <li><b>只在进程内调</b>。历届 {@code :info} 探针进程 / {@code Runtime.exec}
 *       子进程都被相机的进程组策略杀掉，单进程铁律不能破。</li>
 *   <li><b>调用方必须 try/catch(Throwable)</b>。真机出现过原生层异常带崩主进程，
 *       而且 {@code System.loadLibrary} 在相机上也可能失败（库不存在/ABI 不符）。
 *       所以这里把加载结果收在 {@link #available()} 里，未加载成功就一个字都别调。</li>
 *   <li><b>每个进程只调一次</b>。这些值一辈子不变，{@link DeviceInfo} 侧已做缓存，
 *       不要在任何循环/心跳里碰它。</li>
 * </ol>
 */
final class NativeInfo {

    /** 原生库是否装载成功；失败后本进程不再重试。 */
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("sonyinfo");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        LOADED = ok;
    }

    private NativeInfo() {
    }

    static boolean available() {
        return LOADED;
    }

    /**
     * 地区（backup region），形如 {@code CX79101_CN2}。
     *
     * 与 OpenMemories-Tweak「Backup region」同一条路：
     * {@code backup_senser_cmd_preset_data_read(1, …)} 取 preset data，
     * 校验偏移 0x0C 处是 {@code BK2}/{@code BK4}，再取偏移 0xC0 的字符串。
     *
     * @return 读不到（消息失败、/dev/mem 打不开、头校验不过、字段为空）返回 null
     */
    static native String region();
}
