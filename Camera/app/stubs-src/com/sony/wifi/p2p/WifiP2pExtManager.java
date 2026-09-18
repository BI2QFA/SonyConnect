package com.sony.wifi.p2p;

import android.net.wifi.p2p.WifiP2pManager;

/**
 * 索尼 WifiP2pExtManager 编译桩（运行时由相机系统提供真实实现）。
 * 方法签名照抄自官方 Smart Remote Embedded v4.31 反编译结果
 * （com.sony.imaging.app.srctrl.network.wifiWrapper.WifiP2pManagerJb）。
 *
 * 说明：真机上该类 extends WifiP2pManager；但因 SDK 中父类构造器不可访问，
 * 此桩不声明继承——调用方需另取一份 (WifiP2pManager)getSystemService("wifip2p")
 * 引用来使用标准 API（initialize/createGroup/…），扩展 API 走本类引用，
 * 两者底层是同一个服务对象。只用于编译期，绝不打进 APK。
 */
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
