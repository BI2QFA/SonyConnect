package com.bi2qfa.sonyconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;









public class ExitCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            PtpIpServer.killActiveInstance();
        } catch (Throwable t) {
        }
        try {
            MainActivity.killServicesQuietly();
        } catch (Throwable t) {
        }
        try {
            MainActivity.killRadioQuietly(context);
        } catch (Throwable t) {
        }
        try {
            MainActivity.deleteTraceFiles(context);
        } catch (Throwable t) {
        }
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
        }
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable t) {
        }
    }
}
