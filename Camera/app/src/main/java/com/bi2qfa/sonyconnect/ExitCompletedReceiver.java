package com.bi2qfa.sonyconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

/**
 * 相机平台会话结束广播（DAConnectionManagerService.ExitCompleted）的最后兜底：
 * Activity.onDestroy 在相机上可能根本不执行（电源拨杆/会话强收），这里按同样的
 * 顺序重做一遍关键清理，确认后才自杀进程。绝不先 killProcess——残留状态会
 * 污染整个系统（触发"数据修复"）。顺序与 MainActivity 的有序退出链一致：
 * PTP/IP（有序关闭：数据连接→协议/事件连接→监听→令牌表）
 * → 服务链收尾（含缩略图预取线程，释放 SD 句柄）→ 无线归位 → 清除痕迹 → 自杀。
 */
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
