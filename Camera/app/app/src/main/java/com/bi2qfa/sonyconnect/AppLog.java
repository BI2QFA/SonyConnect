package com.bi2qfa.sonyconnect;

import java.text.SimpleDateFormat;
import java.util.Date;

















public final class AppLog {

    
    private static final int MAX = 800;

    private static final String[] RING = new String[MAX];
    private static int appended;      
    private static int cursor;        

    private static SimpleDateFormat fmt;

    private AppLog() {
    }

    public static synchronized void i(String tag, String msg) {
        append("I", tag, msg);
    }

    public static synchronized void w(String tag, String msg) {
        append("W", tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        append("E", tag, msg);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        String extra = "";
        if (t != null) {
            extra = " " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        }
        append("E", tag, msg + extra);
    }

    private static void append(String level, String tag, String msg) {
        if (fmt == null) {
            fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        }
        RING[cursor] = fmt.format(new Date()) + " " + level + "/" + tag + " " + msg;
        cursor = (cursor + 1) % MAX;
        appended++;
    }

    
    public static synchronized int size() {
        return appended < MAX ? appended : MAX;
    }

    





    public static synchronized String dump(int maxLines) {
        int total = size();
        int from = (maxLines > 0 && total > maxLines) ? total - maxLines : 0;
        StringBuilder sb = new StringBuilder(Math.max(16, total * 64));
        for (int k = from; k < total; k++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(at(k));
        }
        return sb.toString();
    }

    
    private static String at(int k) {
        int total = size();
        int start = appended > MAX ? cursor : 0;   
        return RING[(start + k) % MAX];
    }
}
