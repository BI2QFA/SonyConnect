package com.sony.scalar.media;

import com.sony.scalar.hardware.CameraEx;

public class MediaRecorder {

    public static final int MEDIA_RECORDER_ERROR_UNKNOWN = 1;

    public MediaRecorder() {
        throw new RuntimeException("stub");
    }

    public void setCamera(CameraEx camera) {
        throw new RuntimeException("stub");
    }

    public void setVideoSource(int source) {
        throw new RuntimeException("stub");
    }

    public void setAudioSource(int source) {
        throw new RuntimeException("stub");
    }

    public void setOutputMedia(String media) {
        throw new RuntimeException("stub");
    }

    public void prepare() {
        throw new RuntimeException("stub");
    }

    public void start() {
        throw new RuntimeException("stub");
    }

    public void stop() {
        throw new RuntimeException("stub");
    }

    public void reset() {
        throw new RuntimeException("stub");
    }

    public void release() {
        throw new RuntimeException("stub");
    }

    public void setOnErrorListener(OnErrorListener listener) {
        throw new RuntimeException("stub");
    }

    public void setOnRecordListener(OnRecordListener listener) {
        throw new RuntimeException("stub");
    }

    public void setOnRecTimeListener(OnRecTimeListener listener) {
        throw new RuntimeException("stub");
    }

    public interface OnErrorListener {
        void onError(MediaRecorder mr, int what, int extra);
    }

    public interface OnRecordListener {
        void onRecord(MediaRecorder mr, int what, int extra);
    }

    public interface OnRecTimeListener {
        void onRecTime(MediaRecorder mr, int seconds);
    }

    public static final class VideoSource {
        public static final int CAMERA = 1;
    }

    public static final class AudioSource {
        public static final int MIC = 1;
        public static final int CAMCORDER = 5;
    }
}
