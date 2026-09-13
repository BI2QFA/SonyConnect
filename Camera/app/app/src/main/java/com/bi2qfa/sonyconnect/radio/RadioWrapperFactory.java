package com.bi2qfa.sonyconnect.radio;

import android.content.Context;
import android.os.Build;


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
                
                sInstance = new RadioWrapperGb(app);
            }
        }
        return sInstance;
    }
}
