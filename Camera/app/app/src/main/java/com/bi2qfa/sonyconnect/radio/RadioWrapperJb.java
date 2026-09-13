package com.bi2qfa.sonyconnect.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;
import android.util.Log;

import com.sony.wifi.p2p.WifiP2pExtManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;







public class RadioWrapperJb implements RadioWrapper {

    private static final String TAG = "RadioWrapperJb";
    
    private static final long LATCH_TIMEOUT_MS = 20000;
    private static final long LATCH_TIMEOUT_QUERY_MS = 10000;

    private final Context ctx;
    private final WifiP2pManager p2pManager;      
    private final WifiP2pExtManager extManager;   
    
    private volatile String lastError = "";

    public String getLastError() { return lastError; }

    private volatile WifiP2pManager.Channel channel;
    private final CountDownLatch channelReady = new CountDownLatch(1);
    private String connectedStaAddr = "";

    public RadioWrapperJb(Context appContext) {
        this.ctx = appContext;
        Object svc = safeGet(appContext, "wifip2p");
        if (svc instanceof WifiP2pExtManager) {
            this.extManager = (WifiP2pExtManager) svc;
            this.p2pManager = (WifiP2pManager) svc;   
        } else {
            
            this.extManager = null;
            this.p2pManager = null;
        }

        IntentFilter f = new IntentFilter();
        f.addAction("android.net.wifi.p2p.STATE_CHANGED");
        f.addAction("android.net.wifi.p2p.CONNECTION_STATE_CHANGE");
        f.addAction("android.net.wifi.p2p.PEERS_CHANGED");
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try { handleRaw(context, intent); } catch (Throwable t) { Log.e(TAG, "handleRaw", t); }
            }
        }, f);

        
        if (p2pManager != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper.prepare();
                    try {
                        channel = p2pManager.initialize(ctx, Looper.myLooper(), null);
                    } catch (Throwable t) {
                        Log.e(TAG, "initialize", t);
                    }
                    channelReady.countDown();
                    Looper.loop();
                }
            }, "FtpP2pLooper").start();
        } else {
            channelReady.countDown();
        }
    }

    private static Object safeGet(Context c, String name) {
        try { return c.getSystemService(name); } catch (Throwable t) { return null; }
    }

    private boolean awaitChannel() {
        if (channelReady.getCount() == 0 && channel == null) return false;
        try {
            return channelReady.await(5, TimeUnit.SECONDS)
                    && channel != null && p2pManager != null && extManager != null;
        } catch (InterruptedException e) {
            return false;
        }
    }

    
    private static class SyncAction implements WifiP2pManager.ActionListener {
        final CountDownLatch latch = new CountDownLatch(1);
        boolean ok;
        @Override public void onSuccess() { ok = true; latch.countDown(); }
        @Override public void onFailure(int reason) { ok = false; latch.countDown(); }
        boolean await(long ms) {
            try { return latch.await(ms, TimeUnit.MILLISECONDS) && ok; } catch (InterruptedException e) { return false; }
        }
    }

    private static class SyncGroupInfo implements WifiP2pManager.GroupInfoListener {
        final CountDownLatch latch = new CountDownLatch(1);
        WifiP2pGroup group;
        @Override public void onGroupInfoAvailable(WifiP2pGroup g) { group = g; latch.countDown(); }
        WifiP2pGroup await(long ms) {
            try { latch.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException e) {}
            return group;
        }
    }

    private static class SyncEnabled implements WifiP2pExtManager.WifiP2pEnabledListener {
        final CountDownLatch latch = new CountDownLatch(1);
        boolean enabled;
        @Override public void onEnableStatusAvailable(boolean e) { enabled = e; latch.countDown(); }
        Boolean await(long ms) {
            try { latch.await(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException e) {}
            return enabled;
        }
    }

    

    public boolean setDirectEnabled(boolean enable) {
        if (!awaitChannel()) {
            lastError = "wifip2p服务/Channel不可用";
            return false;
        }
        try {
            java.lang.reflect.Method m = p2pManager.getClass().getMethod("setDirectEnabled",
                    WifiP2pManager.Channel.class, boolean.class, WifiP2pManager.ActionListener.class);
            SyncAction l = new SyncAction();
            m.invoke(p2pManager, channel, enable, l);
            boolean ok = l.await(LATCH_TIMEOUT_MS);
            if (!ok) lastError = "setDirectEnabled无应答/被拒(超时)";
            return ok;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            lastError = "调用异常: " + cause.getClass().getSimpleName();
            return false;
        } catch (NoSuchMethodException e) {
            lastError = "setDirectEnabled方法不存在(反射)";
            return false;
        } catch (Throwable t) {
            lastError = "调用失败:" + t.getClass().getSimpleName();
            return false;
        }
    }

    



    public void issueDirectOff() {
        try {
            if (!awaitChannel()) return;
            java.lang.reflect.Method m = p2pManager.getClass().getMethod("setDirectEnabled",
                    WifiP2pManager.Channel.class, boolean.class, WifiP2pManager.ActionListener.class);
            m.invoke(p2pManager, channel, Boolean.FALSE, new SyncAction());
        } catch (Throwable ignored) {}
    }

    

    public void removeGroup() {
        if (!awaitChannel()) return;
        try {
            SyncAction l = new SyncAction();
            p2pManager.removeGroup(channel, l);
            l.await(8000);
        } catch (Throwable ignored) {}
    }

    public boolean isDirectEnabled() {
        if (!awaitChannel()) return false;
        SyncEnabled l = new SyncEnabled();
        try {
            extManager.isDirectEnabled(channel, l);
        } catch (Throwable t) {
            return false;
        }
        Boolean r = l.await(LATCH_TIMEOUT_QUERY_MS);
        return r != null && r.booleanValue();
    }

    public GroupConfig getLiveGroup() {
        if (!awaitChannel()) return null;
        SyncGroupInfo l = new SyncGroupInfo();
        try {
            p2pManager.requestGroupInfo(channel, l);
        } catch (Throwable t) {
            return null;
        }
        WifiP2pGroup g = l.await(LATCH_TIMEOUT_QUERY_MS);
        if (g == null || g.getNetworkName() == null) return null;
        
        return new GroupConfig(g.getNetworkName(), g.getPassphrase(), 1);
    }

    public boolean startGo(int networkId) {
        if (!awaitChannel()) return false;
        SyncAction l = new SyncAction();
        try {
            p2pManager.createGroup(channel, l);
        } catch (Throwable t) {
            Log.e(TAG, "startGo", t);
            return false;
        }
        boolean ok = l.await(LATCH_TIMEOUT_MS);
        if (!ok) lastError = "createGroup无应答/被拒";
        return ok;
    }

    public void configureIdentity(final String ssidPostfix, final String modelName, final String deviceName) {
        if (!awaitChannel()) return;
        bestEffort(new Op() {
            @Override public boolean run() {
                SyncAction l = new SyncAction();
                extManager.setSsidPostfix(channel, ssidPostfix, l);
                return l.await(4000);
            }
        });
        bestEffort(new Op() {
            @Override public boolean run() {
                SyncAction l = new SyncAction();
                extManager.setModelName(channel, modelName, l);
                return l.await(4000);
            }
        });
        bestEffort(new Op() {
            @Override public boolean run() {
                SyncAction l = new SyncAction();
                extManager.setDeviceName(channel, deviceName, l);
                return l.await(4000);
            }
        });
    }

    private interface Op { boolean run(); }

    private static void bestEffort(Op op) {
        try { op.run(); } catch (Throwable ignored) {}
    }

    

    private void handleRaw(Context c, Intent i) {
        String a = i.getAction();
        if ("android.net.wifi.p2p.STATE_CHANGED".equals(a)) {
            int st = i.getIntExtra("wifi_p2p_state", 1);
            int mapped = st == 1 ? DIRECT_STATE_DISABLED
                       : st == 2 ? DIRECT_STATE_ENABLED : DIRECT_STATE_UNKNOWN;
            Intent out = new Intent(ACTION_DIRECT_STATE_CHANGED);
            out.putExtra(EXTRA_PREV_STATE, DIRECT_STATE_UNKNOWN);
            out.putExtra(EXTRA_STATE, mapped);
            c.sendBroadcast(out);
        } else if ("android.net.wifi.p2p.CONNECTION_STATE_CHANGE".equals(a)) {
            WifiP2pInfo info = (WifiP2pInfo) i.getParcelableExtra("wifiP2pInfo");
            NetworkInfo ni = (NetworkInfo) i.getParcelableExtra("networkInfo");
            if (info != null && ni != null && info.groupFormed
                    && ni.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
                Intent out = new Intent(ACTION_GROUP_CREATE_SUCCESS);
                out.putExtra(EXTRA_CONFIG, getLiveGroup());
                c.sendBroadcast(out);
            } else if ((info == null || !info.groupFormed)
                    && ni != null && ni.getDetailedState() == NetworkInfo.DetailedState.FAILED) {
                c.sendBroadcast(new Intent(ACTION_GROUP_CREATE_FAILURE));
            } else if ((info == null || !info.groupFormed)
                    && ni != null && ni.getDetailedState() == NetworkInfo.DetailedState.DISCONNECTED) {
                checkStationConnected(c);
            }
        } else if ("android.net.wifi.p2p.PEERS_CHANGED".equals(a)) {
            checkStationConnected(c);
        }
    }

    
    private void checkStationConnected(Context c) {
        WifiP2pGroup g = peekGroup();
        String next = "";
        if (g != null && g.getClientList() != null) {
            for (WifiP2pDevice d : g.getClientList()) {
                if (d != null && d.deviceAddress != null) next = d.deviceAddress;
            }
        }
        if (connectedStaAddr.isEmpty() && !next.isEmpty()) {
            Intent o = new Intent(ACTION_STA_CONNECTED);
            o.putExtra(EXTRA_STA_ADDR, next);
            c.sendBroadcast(o);
        } else if (!connectedStaAddr.isEmpty() && next.isEmpty()) {
            Intent o = new Intent(ACTION_STA_DISCONNECTED);
            o.putExtra(EXTRA_STA_ADDR, connectedStaAddr);
            c.sendBroadcast(o);
        } else if (!connectedStaAddr.isEmpty() && !next.equals(connectedStaAddr)) {
            Intent o = new Intent(ACTION_STA_DISCONNECTED);
            o.putExtra(EXTRA_STA_ADDR, connectedStaAddr);
            c.sendBroadcast(o);
            Intent o2 = new Intent(ACTION_STA_CONNECTED);
            o2.putExtra(EXTRA_STA_ADDR, next);
            c.sendBroadcast(o2);
        }
        connectedStaAddr = next;
    }

    private WifiP2pGroup peekGroup() {
        if (!awaitChannel()) return null;
        SyncGroupInfo l = new SyncGroupInfo();
        try { p2pManager.requestGroupInfo(channel, l); } catch (Throwable t) { return null; }
        return l.await(5000);
    }
}
