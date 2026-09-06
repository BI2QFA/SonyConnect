package com.sony.wifi.direct;

import java.util.List;

public class DirectManager {

    public static final String WIFI_DIRECT_SERVICE = "wifi-direct";

    public static final String DIRECT_STATE_CHANGED_ACTION = "com.sony.wifi.direct.DIRECT_STATE_CHANGED_ACTION";
    public static final String GROUP_CREATE_SUCCESS_ACTION = "com.sony.wifi.direct.GROUP_CREATE_SUCCESS_ACTION";
    public static final String GROUP_CREATE_FAILURE_ACTION = "com.sony.wifi.direct.GROUP_CREATE_FAILURE_ACTION";
    public static final String STA_CONNECTED_ACTION = "com.sony.wifi.direct.STA_CONNECTED_ACTION";
    public static final String STA_DISCONNECTED_ACTION = "com.sony.wifi.direct.STA_DISCONNECTED_ACTION";

    public static final String EXTRA_DIRECT_STATE = "direct_state";
    public static final String EXTRA_DIRECT_CONFIG = "direct_config";
    public static final String EXTRA_STA_ADDR = "sta_addr";

    public static final int DIRECT_STATE_DISABLING = 0;
    public static final int DIRECT_STATE_DISABLED = 1;
    public static final int DIRECT_STATE_ENABLING = 3;
    public static final int DIRECT_STATE_ENABLED = 4;
    public static final int DIRECT_STATE_UNKNOWN = -1;

    public boolean setDirectEnabled(boolean enabled) { return false; }
    public List<DirectConfiguration> getConfigurations() { return null; }
    public boolean startGo(int networkId) { return false; }
    public boolean removeGroup() { return false; }
    public int getDirectState() { return DIRECT_STATE_UNKNOWN; }
}
