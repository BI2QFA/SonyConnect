package com.bi2qfa.sonyconnect;

import java.io.File;

public class BatchTest {

    public static void main(String[] args) throws Exception {
        File dir = new File(args != null && args.length > 0
                ? args[0] : "C:/Users/93849/Desktop/100MSDCF");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("[SKIP] 样本目录缺失: " + dir);
            System.exit(2);
        }

        int total = 0;
        int failed = 0;
        long t0 = System.currentTimeMillis();
        for (File f : files) {
            if (!ThumbnailExtractor.supports(f)) continue;
            total++;
            String err = check(f);
            if (err != null) {
                failed++;
                System.out.println("FAIL " + f.getName() + " : " + err);
            }
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("BATCH " + (total - failed) + "/" + total
                + " passed, " + ms + " ms (" + (total > 0 ? ms / total : 0) + " ms/file)");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String check(File f) {
        try {
            byte[] small = ThumbnailExtractor.extractSmall(f);
            if (small == null) return "小图提取失败";
            if ((small[0] & 0xFF) != 0xFF || (small[1] & 0xFF) != 0xD8) return "小图非 JPEG";
            int[] sd = ThumbnailExtractor.jpegScan(small, 0, small.length);
            if (sd == null) return "小图标记段不完整";
            if (sd[0] >= 1000) return "小图宽度异常 " + sd[0];

            byte[] pv = ThumbnailExtractor.extractPreview(f);
            if (pv == null) return "大预览提取失败";
            if ((pv[0] & 0xFF) != 0xFF || (pv[1] & 0xFF) != 0xD8) return "大预览非 JPEG";
            int[] pd = ThumbnailExtractor.jpegScan(pv, 0, pv.length);
            if (pd == null) return "大预览标记段不完整";
            if (pd[0] < 1000) return "大预览宽度异常 " + pd[0];
            if (pv.length <= small.length) return "大预览不大于小图";
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }
}
