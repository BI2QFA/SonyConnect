package com.sony.scalar.provider;

/**
 * 索尼媒体库（Avindex）桩。官方遥控用它取"外部媒体 ID"：
 * {@code AvindexStore.getExternalMediaIds()[0]} —— 既用于
 * {@code CameraEx.OpenOptions.setTargetMedia(...)}，也用于
 * {@code MediaRecorder.setOutputMedia(...)}。
 *
 * <p>运行期由相机固件提供真实现，本类只供编译/文档用途（RecSession 全走反射）。
 */
public class AvindexStore {

    public static String[] getExternalMediaIds() {
        throw new RuntimeException("stub");
    }
}
