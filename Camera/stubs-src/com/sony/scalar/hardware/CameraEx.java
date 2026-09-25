package com.sony.scalar.hardware;

import android.hardware.Camera;
import android.util.Pair;

import java.util.List;

public class CameraEx {

    public static final int FOCUS_DRIVE_DIRECTION_NEAR = 0;
    public static final int FOCUS_DRIVE_DIRECTION_FAR = 1;
    public static final int ZOOM_DIRECTION_TELE = 0;
    public static final int ZOOM_DIRECTION_WIDE = 1;
    public static final int RECORDING_MODE_STILL = 0;
    public static final int RECORDING_MODE_MOVIE = 1;
    public static final int MOVIE_MODE_SINGLE = 0;

    public static CameraEx open(int cameraId, OpenOptions options) {
        throw new RuntimeException("stub");
    }

    public void release() {
        throw new RuntimeException("stub");
    }

    public Camera getNormalCamera() {
        throw new RuntimeException("stub");
    }

    public Camera.Parameters getSupportedParameters(int mode) {
        throw new RuntimeException("stub");
    }

    public int getInhibitionInfo() {
        throw new RuntimeException("stub");
    }

    public ParametersModifier createParametersModifier(Camera.Parameters params) {
        throw new RuntimeException("stub");
    }

    public LensInfo getLensInfo() {
        throw new RuntimeException("stub");
    }

    public void burstableTakePicture() {
        throw new RuntimeException("stub");
    }

    public void cancelTakePicture() {
        throw new RuntimeException("stub");
    }

    public void setJpegListener(JpegListener listener) {
        throw new RuntimeException("stub");
    }

    public void setShutterListener(ShutterListener listener) {
        throw new RuntimeException("stub");
    }

    public void setStoreImageCompleteListener(StoreImageCompleteListener listener) {
        throw new RuntimeException("stub");
    }

    public void setAutoFocusStartListener(AutoFocusStartListener listener) {
        throw new RuntimeException("stub");
    }

    public void setAutoFocusDoneListener(AutoFocusDoneListener listener) {
        throw new RuntimeException("stub");
    }

    public void setPreviewStartListener(PreviewStartListener listener) {
        throw new RuntimeException("stub");
    }

    /** 官方 FocusModeController.setQuickAutoFocus：bf 行为串（遥控用 "af_woaf"）。 */
    public void setQuickAutoFocus(String mode) {
        throw new RuntimeException("stub");
    }

    public void resetQuickAutoFocus(String mode) {
        throw new RuntimeException("stub");
    }

    public void executeAutoFocusStartTrigger(boolean start, String mode) {
        throw new RuntimeException("stub");
    }

    public void startZoom(int direction, int speed) {
        throw new RuntimeException("stub");
    }

    public void stopZoom() {
        throw new RuntimeException("stub");
    }

    public void startTrackingFocus(int x, int y) {
        throw new RuntimeException("stub");
    }

    public void stopTrackingFocus() {
        throw new RuntimeException("stub");
    }

    public void incrementAperture() {
        throw new RuntimeException("stub");
    }

    public void decrementAperture() {
        throw new RuntimeException("stub");
    }

    public void incrementShutterSpeed() {
        throw new RuntimeException("stub");
    }

    public void decrementShutterSpeed() {
        throw new RuntimeException("stub");
    }

    /** 官方遥控用的光圈/快门步进（索引差，见 CameraOperationFNumber/ShutterSpeed）。 */
    public void adjustAperture(int diff) {
        throw new RuntimeException("stub");
    }

    public void adjustShutterSpeed(int diff) {
        throw new RuntimeException("stub");
    }

    public void startDirectShutter() {
        throw new RuntimeException("stub");
    }

    public void stopDirectShutter(DirectShutterStoppedCallback cb) {
        throw new RuntimeException("stub");
    }

    public static class OpenOptions {
        public void setInheritSetting(boolean inherit) {
            throw new RuntimeException("stub");
        }

        public void setPreview(boolean preview) {
            throw new RuntimeException("stub");
        }

        public void setRecordingMode(int mode) {
            throw new RuntimeException("stub");
        }

        public void setMovieMode(int mode) {
            throw new RuntimeException("stub");
        }

        public void setTargetMedia(String media) {
            throw new RuntimeException("stub");
        }
    }

    public static class LensInfo {
        public String LensName;
    }

    public static class StoreImageInfo {
        public String DirectoryName;
        public String FileName;
        public int DirectoryNo;
        public int FileNo;
        public int MediaId;
    }

    public static class ParametersModifier {
        public int getISOSensitivity() {
            throw new RuntimeException("stub");
        }

        public void setISOSensitivity(int iso) {
            throw new RuntimeException("stub");
        }

        public List getSupportedISOSensitivities() {
            throw new RuntimeException("stub");
        }

        public int getAperture() {
            throw new RuntimeException("stub");
        }

        public Pair getShutterSpeed() {
            throw new RuntimeException("stub");
        }

        public String getAutoFocusMode() {
            throw new RuntimeException("stub");
        }

        public void setAutoFocusMode(String mode) {
            throw new RuntimeException("stub");
        }

        public List getSupportedAutoFocusModes() {
            throw new RuntimeException("stub");
        }

        public String getFocusAreaMode() {
            throw new RuntimeException("stub");
        }

        public void setFocusAreaMode(String mode) {
            throw new RuntimeException("stub");
        }

        public int getSelfTimer() {
            throw new RuntimeException("stub");
        }

        public void setSelfTimer(int sec) {
            throw new RuntimeException("stub");
        }

        public List getSupportedSelfTimers() {
            throw new RuntimeException("stub");
        }

        public String getDriveMode() {
            throw new RuntimeException("stub");
        }

        public void setDriveMode(String mode) {
            throw new RuntimeException("stub");
        }

        /** 触摸对焦的焦点坐标（-1000 ~ +1000，官方 net2scalar 坐标系）。 */
        public void setFocusPoint(int centerX, int centerY) {
            throw new RuntimeException("stub");
        }

        /** 官方变焦速度上限（startZoom 的 speed 必须 ≤ 它）。 */
        public int getMaxZoomSpeed() {
            throw new RuntimeException("stub");
        }

        public List getSupportedQuickAutoFocus() {
            throw new RuntimeException("stub");
        }

        public boolean isSupportedRemoteControlMode() {
            throw new RuntimeException("stub");
        }

        public boolean getRemoteControlMode() {
            throw new RuntimeException("stub");
        }

        public void setRemoteControlMode(boolean on) {
            throw new RuntimeException("stub");
        }
    }

    public interface JpegListener {
        void onPictureTaken(byte[] data, CameraEx cameraEx);
    }

    public interface ShutterListener {
        int STATUS_OK = 0;
        int STATUS_CANCELED = 1;
        int STATUS_ERROR = 2;

        void onShutter(int status, CameraEx cameraEx);
    }

    public interface StoreImageCompleteListener {
        void onDone(int status, StoreImageInfo info, CameraEx cameraEx);
    }

    public interface AutoFocusStartListener {
        void onStart(CameraEx cameraEx);
    }

    public interface AutoFocusDoneListener {
        int STATUS_CLEAR = 0;
        int STATUS_LOCK = 1;
        int STATUS_LOCK_WARN = 2;
        int STATUS_WORKING = 3;
        int STATUS_CONTINUOUS = 4;

        void onDone(int status, int[] extra, CameraEx cameraEx);
    }

    public interface PreviewStartListener {
        void onStart(CameraEx cameraEx);
    }

    public interface DirectShutterStoppedCallback {
        void onStopped(CameraEx cameraEx);
    }
}
