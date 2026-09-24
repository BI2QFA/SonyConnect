package android.hardware;

import java.util.ArrayList;
import java.util.List;

public class Camera {
    public interface PreviewCallback {
        void onPreviewFrame(byte[] data, Camera camera);
    }

    public interface PictureCallback {
        void onPictureTaken(byte[] data, Camera camera);
    }

    public interface AutoFocusCallback {
        void onAutoFocus(boolean success, Camera camera);
    }

    public static class Size {
        public int width;
        public int height;
    }

    public static class Parameters {
        public Size getPreviewSize() {
            Size s = new Size();
            s.width = 640;
            s.height = 480;
            return s;
        }

        public List<Size> getSupportedPreviewSizes() {
            return new ArrayList<Size>();
        }

        public List<Integer> getSupportedPreviewFormats() {
            return new ArrayList<Integer>();
        }

        public void setPreviewSize(int w, int h) {}

        public void setPreviewFormat(int f) {}

        public String getWhiteBalance() { return "auto"; }

        public List<String> getSupportedWhiteBalance() { return new ArrayList<String>(); }

        public String getFlashMode() { return "off"; }

        public List<String> getSupportedFlashModes() { return new ArrayList<String>(); }

        public String getFocusMode() { return "auto"; }

        public void setFocusMode(String m) {}

        public int getExposureCompensation() { return 0; }

        public void setExposureCompensation(int v) {}

        public int getMinExposureCompensation() { return -6; }

        public int getMaxExposureCompensation() { return 6; }

        public String getSceneMode() { return ""; }

        public List<String> getSupportedSceneModes() { return new ArrayList<String>(); }

        public void setWhiteBalance(String v) {}

        public void setFlashMode(String v) {}

        public void setSceneMode(String v) {}
    }

    public Parameters getParameters() { return new Parameters(); }

    public void setParameters(Parameters p) {}

    public void setPreviewCallback(PreviewCallback cb) {}

    public void startPreview() {}

    public void stopPreview() {}

    public void takePicture(Object shutter, Object raw, PictureCallback jpeg) {}

    public void autoFocus(AutoFocusCallback cb) {}

    public void cancelAutoFocus() {}
}
