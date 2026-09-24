package io.github.bi2qfa.sonyconnect.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

/**
 * SDK&lt;16（Gingerbread 系）路线：照官方 WifiP2pManagerGb ——
 * 通过 wifi-direct 服务的 Sony DirectManager 同步 API 驱动热点，
 * 私有广播翻译成归一化事件。
 */
public class RadioWrapperGb implements RadioWrapper {

    private volatile String lastError = "";

    /** GB 路线操作均为同步调用，一般不会产生异步错误细节；保留接口占位。 */
    public String getLastError() { return lastError; }

    private final Context ctx;
    private final DirectManager directManager;

    public RadioWrapperGb(Context appContext) {
        this.ctx = appContext;
        Object svc = null;
        try {
            svc = appContext.getSystemService(DirectManager.WIFI_DIRECT_SERVICE);
        } catch (Throwable t) {}
        this.directManager = svc instanceof DirectManager ? (DirectManager) svc : null;

        IntentFilter f = new IntentFilter();
        f.addAction(DirectManager.DIRECT_STATE_CHANGED_ACTION);
        f.addAction(DirectManager.GROUP_CREATE_SUCCESS_ACTION);
        f.addAction(DirectManager.GROUP_CREATE_FAILURE_ACTION);
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try { handleRaw(context, intent); } catch (Throwable ignored) {}
            }
        }, f);
    }

    private boolean unavailable() { return directManager == null; }

    /**
     * 直接返回布尔（Sony GB 路线本身就是同步 API）；失败静默为 false。
     * 注意：即使返回 false 也可能是服务端慢，调用方须按「未确认」处理并配合重试，
     * 不能当成最终结论。
     */
    public boolean setDirectEnabled(boolean enable) {
        if (unavailable()) return false;
        try {
            return directManager.setDirectEnabled(enable);
        } catch (Throwable t) {
            return false;
        }
    }

    /** GB 路线的 DirectManager 本身就是同步 API，没有可拆分的异步投递：直接同步调一次。 */
    public void issueDirectOff() {
        try {
            if (unavailable()) return;
            directManager.setDirectEnabled(false);
        } catch (Throwable ignored) {}
    }

    /** GB 平台无独立组拆解命令：DirectManager 同步关 Direct 即等效拆组。 */
    public void removeGroup() {
        issueDirectOff();
    }

    /**
     * 桩/官方 Gb 常量里 ENABLED=4；无法查询时按未启用处理。
     */
    public boolean isDirectEnabled() {
        if (unavailable()) return false;
        try {
            return directManager.getDirectState() == 4 /*DIRECT_STATE_ENABLED*/;
        } catch (Throwable t) {
            return false;
        }
    }

    public GroupConfig getLiveGroup() {
        if (unavailable()) return null;
        try {
            java.util.List<DirectConfiguration> list = directManager.getConfigurations();
            if (list == null || list.isEmpty()) return null;
            DirectConfiguration last = list.get(list.size() - 1);
            if (last == null || last.getSsid() == null) return null;
            return new GroupConfig(last.getSsid(), last.getPreSharedKey(), last.getNetworkId());
        } catch (Throwable t) {
            return null;
        }
    }

    /** GB 的 startGo 本身就是同步命令，直接透传 networkId。 */
    public boolean startGo(int networkId) {
        if (unavailable()) return false;
        try {
            return directManager.startGo(networkId);
        } catch (Throwable t) {
            return false;
        }
    }

    public void configureIdentity(String ssidPostfix, String modelName, String deviceName) {
        // GB 平台无对应扩展接口：跳过（旧机型的 SSID 由固件自身规则生成）
    }

    // ===== 原生广播 → 归一化事件 =====

    private void handleRaw(Context c, Intent i) {
        String a = i.getAction();
        if (DirectManager.DIRECT_STATE_CHANGED_ACTION.equals(a)) {
            int nativeState = i.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN);
            // 桩常量：DISABLING=0 DISABLED=1 ENABLING=3 ENABLED=4 UNKNOWN=-1
            Integer mapped;
            if (nativeState == DirectManager.DIRECT_STATE_DISABLED) mapped = Integer.valueOf(DIRECT_STATE_DISABLED);
            else if (nativeState == DirectManager.DIRECT_STATE_ENABLED) mapped = Integer.valueOf(DIRECT_STATE_ENABLED);
            else if (nativeState == DirectManager.DIRECT_STATE_ENABLING
                    || nativeState == DirectManager.DIRECT_STATE_DISABLING) return;   // 过渡态不上报
            else mapped = Integer.valueOf(DIRECT_STATE_UNKNOWN);

            Intent out = new Intent(ACTION_DIRECT_STATE_CHANGED);
            out.putExtra(EXTRA_PREV_STATE, DIRECT_STATE_UNKNOWN);
            out.putExtra(EXTRA_STATE, mapped.intValue());
            c.sendBroadcast(out);
        } else if (DirectManager.GROUP_CREATE_SUCCESS_ACTION.equals(a)) {
            GroupConfig cfg = getLiveGroup();
            Intent out = new Intent(ACTION_GROUP_CREATE_SUCCESS);
            out.putExtra(EXTRA_CONFIG, cfg);
            c.sendBroadcast(out);
        } else if (DirectManager.GROUP_CREATE_FAILURE_ACTION.equals(a)) {
            c.sendBroadcast(new Intent(ACTION_GROUP_CREATE_FAILURE));
        }
    }
}
