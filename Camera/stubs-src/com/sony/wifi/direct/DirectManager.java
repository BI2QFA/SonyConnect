package com.sony.wifi.direct;

import java.util.List;

/**
 * Sony 私有 API 编译桩（compileOnly，不进 APK）。
 * 常量值与相机运行时完全一致（已从 OpenMemories-Framework 核对）。
 */
public class DirectManager {

    // 服务名
    public static final String WIFI_DIRECT_SERVICE = "wifi-direct";

    // 广播 action
    public static final String DIRECT_STATE_CHANGED_ACTION = "com.sony.wifi.direct.DIRECT_STATE_CHANGED_ACTION";
    public static final String GROUP_CREATE_SUCCESS_ACTION = "com.sony.wifi.direct.GROUP_CREATE_SUCCESS_ACTION";
    public static final String GROUP_CREATE_FAILURE_ACTION = "com.sony.wifi.direct.GROUP_CREATE_FAILURE_ACTION";
    public static final String STA_CONNECTED_ACTION = "com.sony.wifi.direct.STA_CONNECTED_ACTION";
    public static final String STA_DISCONNECTED_ACTION = "com.sony.wifi.direct.STA_DISCONNECTED_ACTION";

    // 广播 extra
    public static final String EXTRA_DIRECT_STATE = "direct_state";
    public static final String EXTRA_DIRECT_CONFIG = "direct_config";
    public static final String EXTRA_STA_ADDR = "sta_addr";

    // 状态
    public static final int DIRECT_STATE_DISABLING = 0;
    public static final int DIRECT_STATE_DISABLED = 1;
    public static final int DIRECT_STATE_ENABLING = 3;
    public static final int DIRECT_STATE_ENABLED = 4;
    public static final int DIRECT_STATE_UNKNOWN = -1;

    // 方法（桩内返回默认值，运行时由相机系统提供真实实现）
    public boolean setDirectEnabled(boolean enabled) { return false; }
    public List<DirectConfiguration> getConfigurations() { return null; }
    public boolean startGo(int networkId) { return false; }
    public boolean removeGroup() { return false; }
    public int getDirectState() { return DIRECT_STATE_UNKNOWN; }
}
