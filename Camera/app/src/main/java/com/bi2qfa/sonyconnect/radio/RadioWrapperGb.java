package com.bi2qfa.sonyconnect.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.sony.wifi.direct.DirectConfiguration;
import com.sony.wifi.direct.DirectManager;

public class RadioWrapperGb implements RadioWrapper {

    private volatile String lastError = "";

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

    public boolean setDirectEnabled(boolean enable) {
        if (unavailable()) return false;
        try {
            return directManager.setDirectEnabled(enable);
        } catch (Throwable t) {
            return false;
        }
    }

    public void issueDirectOff() {
        try {
            if (unavailable()) return;
            directManager.setDirectEnabled(false);
        } catch (Throwable ignored) {}
    }

    public void removeGroup() {
        issueDirectOff();
    }

    public boolean isDirectEnabled() {
        if (unavailable()) return false;
        try {
            return directManager.getDirectState() == 4  ;
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

    public boolean startGo(int networkId) {
        if (unavailable()) return false;
        try {
            return directManager.startGo(networkId);
        } catch (Throwable t) {
            return false;
        }
    }

    public void configureIdentity(String ssidPostfix, String modelName, String deviceName) {

    }

    private void handleRaw(Context c, Intent i) {
        String a = i.getAction();
        if (DirectManager.DIRECT_STATE_CHANGED_ACTION.equals(a)) {
            int nativeState = i.getIntExtra(DirectManager.EXTRA_DIRECT_STATE, DirectManager.DIRECT_STATE_UNKNOWN);

            Integer mapped;
            if (nativeState == DirectManager.DIRECT_STATE_DISABLED) mapped = Integer.valueOf(DIRECT_STATE_DISABLED);
            else if (nativeState == DirectManager.DIRECT_STATE_ENABLED) mapped = Integer.valueOf(DIRECT_STATE_ENABLED);
            else if (nativeState == DirectManager.DIRECT_STATE_ENABLING
                    || nativeState == DirectManager.DIRECT_STATE_DISABLING) return;
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
