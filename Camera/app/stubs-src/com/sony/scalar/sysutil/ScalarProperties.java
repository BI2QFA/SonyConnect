package com.sony.scalar.sysutil;














public final class ScalarProperties {
    public static final String PROP_MODEL_NAME = "model.name";
    public static final String PROP_MODEL_CODE = "model.code";
    public static final String PROP_MODEL_SERIAL_CODE = "model.serial.code";
    public static final String PROP_VERSION_PLATFORM = "version.platform";
    
    public static final String PROP_SYS_DEST = "sys.dest";

    private ScalarProperties() {
    }

    public static String getString(String key) {
        throw new RuntimeException("stub");
    }

    public static String getString(String key, String def) {
        throw new RuntimeException("stub");
    }

    public static int getInt(String key) {
        throw new RuntimeException("stub");
    }

    public static int getInt(String key, int def) {
        throw new RuntimeException("stub");
    }

    




    public static int[] getIntArray(String key) {
        throw new RuntimeException("stub");
    }

    public static String getFirmwareVersion() {
        throw new RuntimeException("stub");
    }
}
