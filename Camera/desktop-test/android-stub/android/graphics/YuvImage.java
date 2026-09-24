package android.graphics;

import java.io.OutputStream;

public class YuvImage {
    public YuvImage(byte[] yuv, int format, int width, int height, int[] strides) {}

    public boolean compressToJpeg(Rect rectangle, int quality, OutputStream stream) {
        return false;
    }
}
