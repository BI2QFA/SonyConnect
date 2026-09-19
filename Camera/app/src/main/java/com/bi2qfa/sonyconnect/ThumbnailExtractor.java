package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 索尼 ARW/JPG 内嵌 JPEG 提取器（纯 Java，零 Android 依赖，桌面可测）。
 *
 * 算法 = 交接文档《ARW缩略图分析交接总结》的通用缩略图解析方法
 * （100MSDCF 361 个文件批量验证 100% 命中）：
 *  1. 读文件头 512KB；
 *  2. TIFF 基准：ARW=0（文件即 TIFF）；JPG=APP1 "Exif\0\0" 段内 TIFF 头；
 *  3. 遍历 IFD 链（IFD0、0x014A SubIFDs、0x8769 Exif IFD、next-IFD → IFD1…），
 *     任何 IFD 中同时存在 0x0201/0x0202 即一个内嵌 JPEG 候选
 *     （绝对偏移 = TIFF 基准 + 0x0201 值；IFD 内所有偏移都相对 TIFF 头）；
 *  4. 逐候选校验：FFD8 起、FFD9 止、标记段可完整走读出合法 SOF 尺寸；
 *  5. 按 SOF 宽度分类：≥1000px → 大预览，否则 → 小缩略图；
 *  6. JPG 专属：大预览不在 Exif 里（Sony MakerNotes 私有编码）。链上找不到
 *     大图时读尾部 1MB，枚举全部 FFD8FF 候选做完整标记段解析（熵数据处理
 *     FF00 填充与 FFD0-D7 RST），取「最长、FFD9 正常结束、SOF 合法」者。
 *     ⚠️ 不能用小固定窗口找第一个 FFD8FF（512KB 窗实测漏过 536KB 处的预览）。
 *  7. ARW 不需要第 6 步：IFD0 的 0x0201/0x0202 直接给出大预览精确偏移。
 *
 * 偏移因文件/画幅/固件而异，绝不硬编码；II/MM 字节序都支持。
 * 所有读取走 RandomAccessFile 点读或一次性有界缓冲，即开即关，不长持 SD 句柄。
 */
public final class ThumbnailExtractor {

    private static final int TAG_MAKE = 0x010F;
    private static final int TAG_MODEL = 0x0110;
    private static final int TAG_JPEG_IF_OFFSET = 0x0201;
    private static final int TAG_JPEG_IF_LENGTH = 0x0202;
    private static final int TAG_SUB_IFDS = 0x014A;
    private static final int TAG_EXIF_IFD_POINTER = 0x8769;
    private static final int TAG_BODY_SERIAL = 0xA431;   // 本机不写（真样本验证），保留兜底尝试
    private static final int TAG_LENS_MODEL = 0xA434;    // 本机有写，EXIF 兜底的镜头名来源

    private static final int HEAD_PARSE = 512 * 1024;      // 通用方法：头部解析窗
    private static final int TAIL_WINDOW = 1024 * 1024;    // JPG 大预览尾部扫描窗（1MB）
    private static final long MAX_JPEG = 16L * 1024 * 1024;
    private static final int BIG_PREVIEW_MIN_WIDTH = 1000; // ≥1000px 判为大预览
    private static final int MAX_IFDS = 16;                // IFD 链遍历上限（防环）

    /** EXIF 信息（设备信息兜底链用） */
    public static final class ExifInfo {
        public String make;
        public String model;
        public String bodySerial;
        public String lens;
    }

    /** 校验通过的内嵌 JPEG：数据 + SOF 尺寸 */
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

    /** IFD 链上收集到的候选（绝对偏移域内） */
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

    /** 小缩略图（列表/网格用，160×120 级 ~10KB，毫秒级） */
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

    /** 大预览（点开全屏用，1616×1080 级 ~0.4-0.6MB，~0.2s） */
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
        // JPG 兜底：链上无大图（Sony 的预览由 MakerNotes 私有标签引用）→ 尾扫描
        if (isJpg) return jpgPreviewTailScan(f);
        return null;
    }

    /** EXIF 型号/序列号/镜头兜底；解析不出返回 null */
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

    // ===== 解析会话：ARW 整文件点读 / JPG 头缓冲 =====

    private static final class Session {
        final Region region;
        final Tiff tiff;
        private final RandomAccessFile raf; // ARW 时非 null，close 负责

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
                    // 关闭失败无补救动作
                }
            }
        }
    }

    // ===== 通用方法第 3 步：IFD 链候选收集 =====

    /** 遍历 IFD0 → SubIFDs → Exif IFD → next-IFD 链，收集全部 0x0201+0x0202 候选 */
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

    /** 单个 IFD 的 0x0201/0x0202 成对候选 */
    private static void collectCandInIfd(Session s, long ifdAbs, List out) throws IOException {
        long[] off = new long[1];
        long[] len = new long[1];
        boolean hasOff = s.tiff.findTag(ifdAbs, TAG_JPEG_IF_OFFSET, off);
        boolean hasLen = s.tiff.findTag(ifdAbs, TAG_JPEG_IF_LENGTH, len);
        if (hasOff && hasLen && off[0] > 0 && len[0] > 0) {
            out.add(new Cand(s.tiff.tiffBase + off[0], len[0]));
        }
    }

    /** IFD 中 LONG/SHORT 数组标签的值（0x014A/0x8769 用），无则 null */
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
            if (type == 3 && cnt > 2) return null; // SHORT 内联最多 2 个
            if (cnt * (type == 3 ? 2 : 4) <= 4) {
                // 内联在 value 字段
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

    // ===== 通用方法第 4 步：候选校验（FFD8/FFD9 + 完整标记段走读 SOF） =====

    private static Jpeg validateCandidate(Session s, Cand c) {
        if (c.len <= 4 || c.len > MAX_JPEG) return null;
        if (c.abs < 0 || c.abs + c.len > s.region.length()) return null;
        byte[] b = new byte[(int) c.len];
        try {
            s.region.readFully(c.abs, b);
        } catch (IOException e) {
            return null; // JPG 头窗外的候选在此自然淘汰（Sony JPG 大图本就不在 Exif）
        }
        return toVerifiedJpeg(b);
    }

    /** 校验一段字节是完整合法 JPEG 并解出 SOF 尺寸；非法返回 null */
    private static Jpeg toVerifiedJpeg(byte[] b) {
        if (b.length < 4) return null;
        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        if ((b[b.length - 2] & 0xFF) != 0xFF || (b[b.length - 1] & 0xFF) != 0xD9) return null;
        int[] dim = jpegScan(b, 0, b.length);
        if (dim == null || dim[0] <= 0 || dim[1] <= 0) return null;
        return new Jpeg(b, dim[0], dim[1]);
    }

    /**
     * JPEG 标记段完整走读：返回 {width, height, endOfImage}；结构非法返回 null。
     * 处理 FF00 填充、FFD0-D7 RST、SOF 多变体；SOS 后扫描熵数据直到 EOI。
     */
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
                i++; // 填充字节
                continue;
            }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2; // TEM/RST：无长度字段
                continue;
            }
            if (m == 0xD8) {
                i += 2; // 嵌套 SOI（异常但容忍）
                continue;
            }
            if (m == 0xD9) { // EOI
                return w > 0 ? new int[] { w, h, i + 2 } : null;
            }
            int segLen = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (segLen < 2) return null;
            if (m == 0xDA) { // SOS：其后是熵编码数据
                int j = i + 2 + segLen;
                while (j + 1 < end) {
                    if ((b[j] & 0xFF) == 0xFF) {
                        int nx = b[j + 1] & 0xFF;
                        if (nx == 0x00 || (nx >= 0xD0 && nx <= 0xD7)) {
                            j += 2; // FF00 填充 / RST
                            continue;
                        }
                        if (nx == 0xFF) {
                            j++;
                            continue;
                        }
                        if (nx == 0xD9) return w > 0 ? new int[] { w, h, j + 2 } : null;
                        return null; // 熵数据中出现其它标记 = 结构损坏
                    }
                    j++;
                }
                return null; // 缓冲尽未遇 EOI
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

    // ===== 通用方法第 6 步：JPG 大预览尾扫描 =====

    /** 尾部 1MB 枚举 FFD8FF，完整标记段解析，取最长合法者为大预览 */
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

    // ===== EXIF 信息兜底 =====

    private static ExifInfo readInfoFrom(Tiff t) throws IOException {
        ExifInfo info = new ExifInfo();
        info.make = t.readAscii(t.ifd0, TAG_MAKE);
        info.model = t.readAscii(t.ifd0, TAG_MODEL);
        long[] exifIfd = new long[1];
        if (t.findTag(t.ifd0, TAG_EXIF_IFD_POINTER, exifIfd) && exifIfd[0] > 0) {
            // 指针值相对 TIFF 头；findTag 返回内联原始值，这里换算为 region 绝对偏移
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

    // ===== TIFF/IFD 会话 =====

    /** 一次解析会话：字节序在 TIFF 头处确定并贯穿整个会话 */
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

        /** tiffBase 处应是 II*\0 / MM*\0 + IFD0 相对偏移；非法返回 null */
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

        /** IFD 的 next-IFD 指针（无则 0） */
        long nextIfd(long ifdAbs) throws IOException {
            byte[] cnt = new byte[2];
            region.readFully(ifdAbs, cnt);
            int count = u16(cnt, 0, le);
            if (count <= 0 || count > 512) return 0;
            byte[] last = new byte[4];
            region.readFully(ifdAbs + 2 + 12L * count, last);
            return u32(last, 0, le) == 0 ? 0 : tiffBase + u32(last, 0, le);
        }

        /** IFD 中 LONG/SHORT count=1 标签的值（0x0201/0x0202/0x8769 等内联值） */
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
                if (type == 4 && cnt >= 1) { // LONG
                    valueOut[0] = u32(entries, off + 8, le) & 0xFFFFFFFFL;
                    return true;
                }
                if (type == 3 && cnt >= 1) { // SHORT
                    valueOut[0] = u16(entries, off + 8, le);
                    return true;
                }
                return false;
            }
            return false;
        }

        /** IFD 中 ASCII 标签字符串（NUL 截断）；注意 offset 相对 TIFF 头 */
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

    // ===== Region：屏蔽 RandomAccessFile 与内存缓冲两种后端 =====

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

    // ===== 公共小件 =====

    /** 在 JPG 头部缓冲里定位 APP1 Exif 段内 TIFF 头的绝对偏移；找不到返回 -1 */
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
                i += 2; // 无长度字段
                continue;
            }
            if (marker == 0xDA) return -1; // SOS 前没找到 Exif
            int segLen = u16(head, i + 2, true);
            if (segLen < 2) return -1;
            if (marker == 0xE1 && i + 10 <= head.length
                    && head[i + 4] == 'E' && head[i + 5] == 'x'
                    && head[i + 6] == 'i' && head[i + 7] == 'f'
                    && head[i + 8] == 0 && head[i + 9] == 0) {
                return i + 10; // "Exif\0\0" 之后即 TIFF 头
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
