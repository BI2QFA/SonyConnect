package com.bi2qfa.sonyconnect.radio;

import android.content.Context;
import android.os.Build;

/** 按系统版本分发无线栈实现（结构照官方 WifiP2pManagerFactory）。 */
public final class RadioWrapperFactory {

    private static RadioWrapper sInstance;

    private RadioWrapperFactory() {}

    public static synchronized RadioWrapper getInstance(Context context) {
        if (sInstance == null) {
            Context app = context.getApplicationContext();
            int sdk = Build.VERSION.SDK_INT;
            if (sdk >= 16) {
                sInstance = new RadioWrapperJb(app);
            } else {
                // 官方工厂只认 SDK10；这里把 <16 的老设备都归入 GB 路线
                sInstance = new RadioWrapperGb(app);
            }
        }
        return sInstance;
    }
}
