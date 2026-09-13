package com.bi2qfa.sonyconnect;



















final class NativeInfo {

    
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

    








    static native String region();
}
