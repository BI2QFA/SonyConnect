package com.sony.wifi.p2p;

import android.net.wifi.p2p.WifiP2pManager;

public class WifiP2pExtManager {

    public interface WifiP2pEnabledListener {
        void onEnableStatusAvailable(boolean enabled);
    }

    public interface WifiP2pDeviceListener {
        void onDeviceInfoAvailable(android.net.wifi.p2p.WifiP2pDevice device);
    }

    public boolean isDirectEnabled(WifiP2pManager.Channel channel, WifiP2pEnabledListener listener) { return false; }

    public boolean setDirectEnabled(WifiP2pManager.Channel channel, boolean enable, WifiP2pManager.ActionListener listener) { return false; }

    public boolean setSsidPostfix(WifiP2pManager.Channel channel, String postfix, WifiP2pManager.ActionListener listener) { return false; }

    public boolean setModelName(WifiP2pManager.Channel channel, String name, WifiP2pManager.ActionListener listener) { return false; }

    public boolean setDeviceName(WifiP2pManager.Channel channel, String name, WifiP2pManager.ActionListener listener) { return false; }

    public boolean getMyDevice(WifiP2pManager.Channel channel, WifiP2pDeviceListener listener) { return false; }
}
