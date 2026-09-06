package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class ThumbnailExtractor {

    private static final int TAG_MAKE = 0x010F;
    private static final int TAG_MODEL = 0x0110;
    private static final int TAG_JPEG_IF_OFFSET = 0x0201;
    private static final int TAG_JPEG_IF_LENGTH = 0x0202;
    private static final int TAG_SUB_IFDS = 0x014A;
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    private static final int TAG_BODY_SERIAL = 0xA431;
    private static final int TAG_LENS_MODEL = 0xA434;

    private static final int HEAD_PARSE = 512 * 1024;
    private static final int TAIL_WINDOW = 1024 * 1024;
    private static final long MAX_JPEG = 16L * 1024 * 1024;
    private static final int BIG_PREVIEW_MIN_WIDTH = 1000;
    private static final int MAX_IFDS = 16;

    public static final class ExifInfo {
        public String make;
        public String model;
        public String bodySerial;
        public String lens;
    }

    private static final class Jpeg {
        final byte[] data;
        final int width;
        final int height;

        Jpeg(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private static final class Cand {
        final long abs;
        final long len;

        Cand(long abs, long len) {
            this.abs = abs;
            this.len = len;
        }
    }

    private ThumbnailExtractor() {
    }

    public static boolean supports(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".arw") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    public static byte[] extractSmall(File f) throws IOException {
        Session s = Session.open(f);
        if (s != null) {
            try {
                List cands = collectCandidates(s);
                for (int i = 0; i < cands.size(); i++) {
                    Jpeg j = validateCandidate(s, (Cand) cands.get(i));
                    if (j != null && j.width > 0 && j.width < BIG_PREVIEW_MIN_WIDTH) {
                        return j.data;
                    }
                }
            } finally {
                s.close();
            }
        }
        return null;
    }

    public static byte[] extractPreview(File f) throws IOException {
        String n = f.getName().toLowerCase();
        boolean isJpg = n.endsWith(".jpg") || n.endsWith(".jpeg");
        Session s = Session.open(f);
        if (s != null) {
            try {
                List cands = collectCandidates(s);
                Jpeg best = null;
                for (int i = 0; i < cands.size(); i++) {
                    Jpeg j = validateCandidate(s, (Cand) cands.get(i));
                    if (j != null && j.width >= BIG_PREVIEW_MIN_WIDTH) {
                        if (best == null || j.data.length > best.data.length) best = j;
                    }
                }
                if (best != null) return best.data;
            } finally {
                s.close();
            }
        }

        if (isJpg) return jpgPreviewTailScan(f);
        return null;
    }

    public static ExifInfo readInfo(File f) {
        try {
            Session s = Session.open(f);
            if (s == null) return null;
            try {
                return readInfoFrom(s.tiff);
            } finally {
                s.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Session {
        final Region region;
        final Tiff tiff;
        private final RandomAccessFile raf;

        private Session(Region region, Tiff tiff, RandomAccessFile raf) {
            this.region = region;
            this.tiff = tiff;
            this.raf = raf;
        }

        static Session open(File f) throws IOException {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".arw")) {
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                Tiff t = Tiff.open(new RafRegion(raf), 0L);
                if (t == null) {
                    raf.close();
                    return null;
                }
                return new Session(new RafRegion(raf), t, raf);
            }
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
                int len = (int) Math.min((long) HEAD_PARSE, f.length());
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                byte[] head;
                try {
                    head = new byte[len];
                    raf.readFully(head);
                } finally {
                    raf.close();
                }
                long base = locateExifTiff(head);
                if (base < 0) return null;
                Tiff t = Tiff.open(new BufferRegion(head), base);
                if (t == null) return null;
                return new Session(new BufferRegion(head), t, null);
            }
            return null;
        }

        void close() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {

                }
            }
        }
    }

    private static List collectCandidates(Session s) throws IOException {
        List cands = new ArrayList();
        HashSet visited = new HashSet();
        ArrayList pending = new ArrayList();
        pending.add(Long.valueOf(s.tiff.ifd0));
        int guard = 0;
        while (!pending.isEmpty() && guard < MAX_IFDS) {
            long ifdAbs = ((Long) pending.remove(pending.size() - 1)).longValue();
            if (ifdAbs <= 0 || !visited.add(Long.valueOf(ifdAbs))) continue;
            guard++;
            collectCandInIfd(s, ifdAbs, cands);
            long[] subs = readLongArray(s, ifdAbs, TAG_SUB_IFDS);
            if (subs != null) {
                for (int i = 0; i < subs.length; i++) {
                    pending.add(Long.valueOf(s.tiff.tiffBase + subs[i]));
                }
            }
            long[] exif = readLongArray(s, ifdAbs, TAG_EXIF_IFD_POINTER);
            if (exif != null && exif.length > 0) {
                pending.add(Long.valueOf(s.tiff.tiffBase + exif[0]));
            }
            long next = s.tiff.nextIfd(ifdAbs);
            if (next > 0) pending.add(Long.valueOf(next));
        }
        return cands;
    }

    private static void collectCandInIfd(Session s, long ifdAbs, List out) throws IOException {
        long[] off = new long[1];
        long[] len = new long[1];
        boolean hasOff = s.tiff.findTag(ifdAbs, TAG_JPEG_IF_OFFSET, off);
        boolean hasLen = s.tiff.findTag(ifdAbs, TAG_JPEG_IF_LENGTH, len);
        if (hasOff && hasLen && off[0] > 0 && len[0] > 0) {
            out.add(new Cand(s.tiff.tiffBase + off[0], len[0]));
        }
    }

    private static long[] readLongArray(Session s, long ifdAbs, int wantTag) throws IOException {
        byte[] head = new byte[2];
        s.region.readFully(ifdAbs, head);
        int count = u16(head, 0, s.tiff.le);
        if (count <= 0 || count > 512) return null;
        byte[] entries = new byte[12 * count];
        s.region.readFully(ifdAbs + 2, entries);
        for (int i = 0; i < count; i++) {
            int off = i * 12;
            if (u16(entries, off, s.tiff.le) != wantTag) continue;
            int type = u16(entries, off + 2, s.tiff.le);
            if (type != 4 && type != 3) return null;
            int cnt = (int) u32(entries, off + 4, s.tiff.le);
            if (cnt <= 0 || cnt > 64) return null;
            long[] out = new long[cnt];
            if (type == 3 && cnt > 2) return null;
            if (cnt * (type == 3 ? 2 : 4) <= 4) {

                for (int k = 0; k < cnt; k++) {
                    out[k] = type == 3
                            ? u16(entries, off + 8 + k * 2, s.tiff.le)
                            : u32(entries, off + 8 + k * 4, s.tiff.le) & 0xFFFFFFFFL;
                }
            } else {
                long arrOff = s.tiff.tiffBase + (u32(entries, off + 8, s.tiff.le) & 0xFFFFFFFFL);
                int step = type == 3 ? 2 : 4;
                byte[] buf = new byte[cnt * step];
                try {
                    s.region.readFully(arrOff, buf);
                } catch (IOException e) {
                    return null;
                }
                for (int k = 0; k < cnt; k++) {
                    out[k] = type == 3
                            ? u16(buf, k * 2, s.tiff.le)
                            : u32(buf, k * 4, s.tiff.le) & 0xFFFFFFFFL;
                }
            }
            return out;
        }
        return null;
    }

    private static Jpeg validateCandidate(Session s, Cand c) {
        if (c.len <= 4 || c.len > MAX_JPEG) return null;
        if (c.abs < 0 || c.abs + c.len > s.region.length()) return null;
        byte[] b = new byte[(int) c.len];
        try {
            s.region.readFully(c.abs, b);
        } catch (IOException e) {
            return null;
        }
        return toVerifiedJpeg(b);
    }

    private static Jpeg toVerifiedJpeg(byte[] b) {
        if (b.length < 4) return null;
        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        if ((b[b.length - 2] & 0xFF) != 0xFF || (b[b.length - 1] & 0xFF) != 0xD9) return null;
        int[] dim = jpegScan(b, 0, b.length);
        if (dim == null || dim[0] <= 0 || dim[1] <= 0) return null;
        return new Jpeg(b, dim[0], dim[1]);
    }

    static int[] jpegScan(byte[] b, int start, int end) {
        if (end - start < 4) return null;
        if ((b[start] & 0xFF) != 0xFF || (b[start + 1] & 0xFF) != 0xD8) return null;
        int i = start + 2;
        int w = -1;
        int h = -1;
        while (i + 4 <= end) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int m = b[i + 1] & 0xFF;
            if (m == 0xFF) {
                i++;
                continue;
            }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2;
                continue;
            }
            if (m == 0xD8) {
                i += 2;
                continue;
            }
            if (m == 0xD9) {
                return w > 0 ? new int[] { w, h, i + 2 } : null;
            }
            int segLen = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (segLen < 2) return null;
            if (m == 0xDA) {
                int j = i + 2 + segLen;
                while (j + 1 < end) {
                    if ((b[j] & 0xFF) == 0xFF) {
                        int nx = b[j + 1] & 0xFF;
                        if (nx == 0x00 || (nx >= 0xD0 && nx <= 0xD7)) {
                            j += 2;
                            continue;
                        }
                        if (nx == 0xFF) {
                            j++;
                            continue;
                        }
                        if (nx == 0xD9) return w > 0 ? new int[] { w, h, j + 2 } : null;
                        return null;
                    }
                    j++;
                }
                return null;
            }
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (i + 9 >= end) return null;
                h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
            }
            i += 2 + segLen;
        }
        return null;
    }

    private static byte[] jpgPreviewTailScan(File f) throws IOException {
        long size = f.length();
        int window = (int) Math.min((long) TAIL_WINDOW, size);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] buf = new byte[window];
            raf.seek(size - window);
            raf.readFully(buf);
            byte[] best = null;
            for (int i = 0; i + 4 <= window; i++) {
                if ((buf[i] & 0xFF) != 0xFF || (buf[i + 1] & 0xFF) != 0xD8
                        || (buf[i + 2] & 0xFF) != 0xFF) {
                    continue;
                }
                int[] dim = jpegScan(buf, i, window);
                if (dim == null) continue;
                int end = dim[2];
                if (end - i > (best == null ? 0 : best.length)) {
                    byte[] out = new byte[end - i];
                    System.arraycopy(buf, i, out, 0, out.length);
                    best = out;
                }
            }
            return best;
        } finally {
            raf.close();
        }
    }

    private static ExifInfo readInfoFrom(Tiff t) throws IOException {
        ExifInfo info = new ExifInfo();
        info.make = t.readAscii(t.ifd0, TAG_MAKE);
        info.model = t.readAscii(t.ifd0, TAG_MODEL);
        long[] exifIfd = new long[1];
        if (t.findTag(t.ifd0, TAG_EXIF_IFD_POINTER, exifIfd) && exifIfd[0] > 0) {

            long exifAbs = t.tiffBase + exifIfd[0];
            info.bodySerial = t.readAscii(exifAbs, TAG_BODY_SERIAL);
            info.lens = t.readAscii(exifAbs, TAG_LENS_MODEL);
        }
        if (info.make == null && info.model == null && info.bodySerial == null
                && info.lens == null) {
            return null;
        }
        return info;
    }

    private static final class Tiff {
        final Region region;
        final boolean le;
        final long tiffBase;
        final long ifd0;

        private Tiff(Region region, boolean le, long tiffBase, long ifd0) {
            this.region = region;
            this.le = le;
            this.tiffBase = tiffBase;
            this.ifd0 = ifd0;
        }

        static Tiff open(Region region, long tiffBase) throws IOException {
            byte[] h = new byte[8];
            region.readFully(tiffBase, h);
            boolean le;
            if (h[0] == 'I' && h[1] == 'I') le = true;
            else if (h[0] == 'M' && h[1] == 'M') le = false;
            else return null;
            if (u16(h, 2, le) != 42) return null;
            long rel = u32(h, 4, le);
            if (rel <= 0) return null;
            return new Tiff(region, le, tiffBase, tiffBase + rel);
        }

        long nextIfd(long ifdAbs) throws IOException {
            byte[] cnt = new byte[2];
            region.readFully(ifdAbs, cnt);
            int count = u16(cnt, 0, le);
            if (count <= 0 || count > 512) return 0;
            byte[] last = new byte[4];
            region.readFully(ifdAbs + 2 + 12L * count, last);
            return u32(last, 0, le) == 0 ? 0 : tiffBase + u32(last, 0, le);
        }

        boolean findTag(long ifdAbs, int wantTag, long[] valueOut) throws IOException {
            byte[] head = new byte[2];
            region.readFully(ifdAbs, head);
            int count = u16(head, 0, le);
            if (count <= 0 || count > 512) return false;
            byte[] entries = new byte[12 * count];
            region.readFully(ifdAbs + 2, entries);
            for (int i = 0; i < count; i++) {
                int off = i * 12;
                if (u16(entries, off, le) != wantTag) continue;
                int type = u16(entries, off + 2, le);
                long cnt = u32(entries, off + 4, le);
                if (type == 4 && cnt >= 1) {
                    valueOut[0] = u32(entries, off + 8, le) & 0xFFFFFFFFL;
                    return true;
                }
                if (type == 3 && cnt >= 1) {
                    valueOut[0] = u16(entries, off + 8, le);
                    return true;
                }
                return false;
            }
            return false;
        }

        String readAscii(long ifdAbs, int wantTag) throws IOException {
            byte[] head = new byte[2];
            region.readFully(ifdAbs, head);
            int count = u16(head, 0, le);
            if (count <= 0 || count > 512) return null;
            byte[] entries = new byte[12 * count];
            region.readFully(ifdAbs + 2, entries);
            for (int i = 0; i < count; i++) {
                int off = i * 12;
                if (u16(entries, off, le) != wantTag) continue;
                int type = u16(entries, off + 2, le);
                long cnt = u32(entries, off + 4, le);
                if (type != 2 || cnt <= 0 || cnt > 1024) return null;
                byte[] data;
                if (cnt <= 4) {
                    data = new byte[(int) cnt];
                    System.arraycopy(entries, off + 8, data, 0, (int) cnt);
                } else {
                    long valueOff = tiffBase + (u32(entries, off + 8, le) & 0xFFFFFFFFL);
                    data = new byte[(int) cnt];
                    try {
                        region.readFully(valueOff, data);
                    } catch (IOException e) {
                        return null;
                    }
                }
                int len = 0;
                while (len < data.length && data[len] != 0) len++;
                return new String(data, 0, len, "ISO-8859-1").trim();
            }
            return null;
        }
    }

    private interface Region {
        void readFully(long absPos, byte[] buf) throws IOException;

        long length();
    }

    private static final class RafRegion implements Region {
        private final RandomAccessFile raf;

        RafRegion(RandomAccessFile raf) {
            this.raf = raf;
        }

        public void readFully(long absPos, byte[] buf) throws IOException {
            raf.seek(absPos);
            raf.readFully(buf);
        }

        public long length() {
            try {
                return raf.length();
            } catch (IOException e) {
                return 0;
            }
        }
    }

    private static final class BufferRegion implements Region {
        private final byte[] buf;

        BufferRegion(byte[] buf) {
            this.buf = buf;
        }

        public void readFully(long absPos, byte[] out) throws IOException {
            if (absPos < 0 || absPos + out.length > buf.length) {
                throw new IOException("IFD read out of parse window");
            }
            System.arraycopy(buf, (int) absPos, out, 0, out.length);
        }

        public long length() {
            return buf.length;
        }
    }

    private static long locateExifTiff(byte[] head) {
        if (head.length < 12 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) return -1;
        int i = 2;
        while (i + 4 <= head.length) {
            if ((head[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = head[i + 1] & 0xFF;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                i += 2;
                continue;
            }
            if (marker == 0xDA) return -1;
            int segLen = u16(head, i + 2, true);
            if (segLen < 2) return -1;
            if (marker == 0xE1 && i + 10 <= head.length
                    && head[i + 4] == 'E' && head[i + 5] == 'x'
                    && head[i + 6] == 'i' && head[i + 7] == 'f'
                    && head[i + 8] == 0 && head[i + 9] == 0) {
                return i + 10;
            }
            i += 2 + segLen;
        }
        return -1;
    }

    private static int u16(byte[] b, int off, boolean le) {
        int lo = b[off] & 0xFF;
        int hi = b[off + 1] & 0xFF;
        return le ? (hi << 8) | lo : (lo << 8) | hi;
    }

    private static long u32(byte[] b, int off, boolean le) {
        int a = b[off] & 0xFF;
        int c = b[off + 1] & 0xFF;
        int d = b[off + 2] & 0xFF;
        int e = b[off + 3] & 0xFF;
        if (le) {
            return ((long) e << 24) | (d << 16) | (c << 8) | a;
        }
        return ((long) a << 24) | (c << 16) | (d << 8) | e;
    }
}
