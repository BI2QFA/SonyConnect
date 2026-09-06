package com.bi2qfa.sonyconnect;

public final class QrCode {

    private QrCode() {}

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private static int gmul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static final int[][] VERSION_EC = {
        {10, 1, 16, 0, 0},
        {16, 1, 28, 0, 0},
        {26, 1, 44, 0, 0},
        {18, 2, 32, 0, 0},
        {24, 2, 43, 0, 0},
        {16, 4, 27, 0, 0},
        {18, 4, 31, 0, 0},
        {22, 2, 38, 2, 39},
        {22, 3, 36, 2, 37},
        {26, 4, 43, 1, 44},
    };

    private static final int[][] ALIGN_POS = {
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
        {6, 28, 50},
    };

    public static boolean[][] encode(String text) {
        byte[] data = toBytes(text);
        int version = chooseVersion(data.length);
        byte[] codewords = buildCodewords(data, version);
        return buildMatrix(codewords, version);
    }

    private static byte[] toBytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    private static int chooseVersion(int byteLen) {
        for (int v = 1; v <= 10; v++) {
            int dataCw = totalDataCodewords(v);

            int countBits = (v <= 9) ? 8 : 16;
            int needBits = 4 + countBits + 8 * byteLen;
            if (needBits <= dataCw * 8) return v;
        }
        return 10;
    }

    private static int totalDataCodewords(int version) {
        int[] e = VERSION_EC[version - 1];
        int ec = e[0], b1 = e[1], d1 = e[2], b2 = e[3], d2 = e[4];
        return b1 * d1 + b2 * d2;
    }

    private static byte[] buildCodewords(byte[] data, int version) {
        int[] e = VERSION_EC[version - 1];
        int ecPerBlock = e[0], b1 = e[1], d1 = e[2], b2 = e[3], d2 = e[4];
        int totalData = b1 * d1 + b2 * d2;

        StringBuilder bits = new StringBuilder();
        bits.append("0100");
        int countBits = (version <= 9) ? 8 : 16;
        appendBits(bits, data.length, countBits);
        for (int i = 0; i < data.length; i++) {
            appendBits(bits, data[i] & 0xFF, 8);
        }
        int capacity = totalData * 8;

        int term = Math.min(4, capacity - bits.length());
        for (int i = 0; i < term; i++) bits.append('0');

        while (bits.length() % 8 != 0) bits.append('0');

        int pad = 0;
        while (bits.length() < capacity) {
            appendBits(bits, (pad++ % 2 == 0) ? 0xEC : 0x11, 8);
        }

        byte[] dataCw = new byte[totalData];
        for (int i = 0; i < totalData; i++) {
            dataCw[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }

        int totalBlocks = b1 + b2;
        int totalCodewords = totalData + ecPerBlock * totalBlocks;
        byte[][] blocks = new byte[totalBlocks][];
        int idx = 0;
        for (int b = 0; b < b1; b++) {
            blocks[idx] = rsEncode(sub(dataCw, idx * d1, d1), ecPerBlock);
            idx++;
        }

        int offset = b1 * d1;
        for (int b = 0; b < b2; b++) {
            blocks[idx] = rsEncode(sub(dataCw, offset + b * d2, d2), ecPerBlock);
            idx++;
        }

        byte[] result = new byte[totalCodewords];
        int k = 0;
        int maxData = Math.max(d1, d2);
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (i < blocks[b].length - ecPerBlock) {
                    result[k++] = blocks[b][i];
                }
            }
        }
        for (int i = 0; i < ecPerBlock; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                result[k++] = blocks[b][blocks[b].length - ecPerBlock + i];
            }
        }
        return result;
    }

    private static byte[] sub(byte[] a, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(a, off, r, 0, len);
        return r;
    }

    private static void appendBits(StringBuilder sb, int value, int n) {
        for (int i = n - 1; i >= 0; i--) {
            sb.append(((value >> i) & 1) == 1 ? '1' : '0');
        }
    }

    private static byte[] rsEncode(byte[] data, int ecLen) {
        int[] divisor = rsDivisor(ecLen);
        byte[] ec = new byte[ecLen];
        for (int i = 0; i < data.length; i++) {
            int factor = (data[i] ^ ec[0]) & 0xFF;
            System.arraycopy(ec, 1, ec, 0, ecLen - 1);
            ec[ecLen - 1] = 0;
            for (int j = 0; j < ecLen; j++) {
                ec[j] ^= (byte) gmul(divisor[j], factor);
            }
        }
        byte[] out = new byte[data.length + ecLen];
        System.arraycopy(data, 0, out, 0, data.length);
        System.arraycopy(ec, 0, out, data.length, ecLen);
        return out;
    }

    private static int[] rsDivisor(int degree) {
        int[] r = new int[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = gmul(r[j], root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gmul(root, 2);
        }
        return r;
    }

    private static boolean[][] buildMatrix(byte[] codewords, int version) {
        int size = 17 + 4 * version;
        boolean[][] m = new boolean[size][size];
        boolean[][] isFunc = new boolean[size][size];

        drawFinder(m, isFunc, 0, 0);
        drawFinder(m, isFunc, size - 7, 0);
        drawFinder(m, isFunc, 0, size - 7);
        drawTiming(m, isFunc, size);
        drawAlignment(m, isFunc, version);
        drawFormat(m, isFunc, 0);
        if (version >= 7) drawVersion(m, isFunc, version);

        m[size - 8][8] = true;
        isFunc[size - 8][8] = true;

        int i = 0;
        int totalBits = codewords.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunc[y][x]) {
                        if (i < totalBits) {
                            m[y][x] = ((codewords[i >> 3] >> (7 - (i & 7))) & 1) != 0;
                            i++;
                        } else {
                            m[y][x] = false;
                        }
                    }
                }
            }
        }

        applyMask(m, isFunc, size, 0);
        return m;
    }

    private static void drawFinder(boolean[][] m, boolean[][] f, int r, int c) {
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int y = r + dy, x = c + dx;
                if (x < 0 || y < 0 || x >= m.length || y >= m.length) continue;
                boolean inFinder = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
                boolean black = inFinder
                        && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                if (inFinder) m[y][x] = black;
                f[y][x] = true;
            }
        }
    }

    private static void drawTiming(boolean[][] m, boolean[][] f, int size) {
        for (int i = 8; i < size - 8; i++) {
            if (!f[6][i]) {
                m[6][i] = (i % 2 == 0);
                f[6][i] = true;
            }
            if (!f[i][6]) {
                m[i][6] = (i % 2 == 0);
                f[i][6] = true;
            }
        }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] f, int version) {
        int[] pos = ALIGN_POS[version - 1];
        for (int a = 0; a < pos.length; a++) {
            for (int b = 0; b < pos.length; b++) {

                if ((a == 0 && b == 0) || (a == 0 && b == pos.length - 1) || (a == pos.length - 1 && b == 0)) {
                    continue;
                }
                int cy = pos[a], cx = pos[b];
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        boolean black = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                        m[cy + dy][cx + dx] = black;
                        f[cy + dy][cx + dx] = true;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int size, int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (f[y][x]) continue;
                boolean flip = false;
                switch (mask) {
                    case 0: flip = ((y + x) % 2 == 0); break;
                    case 1: flip = (y % 2 == 0); break;
                    case 2: flip = (x % 3 == 0); break;
                    case 3: flip = ((y + x) % 3 == 0); break;
                    case 4: flip = ((y / 2 + x / 3) % 2 == 0); break;
                    case 5: flip = ((y * x) % 2 + (y * x) % 3 == 0); break;
                    case 6: flip = (((y * x) % 2 + (y * x) % 3) % 2 == 0); break;
                    case 7: flip = (((y + x) % 2 + (y * x) % 3) % 2 == 0); break;
                }
                if (flip) m[y][x] = !m[y][x];
            }
        }
    }

    private static void drawFormat(boolean[][] m, boolean[][] f, int mask) {
        int fmt = formatInfo(mask);
        int size = m.length;

        int[][] pos1 = {
            {8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}, {8, 7}, {8, 8},
            {7, 8}, {5, 8}, {4, 8}, {3, 8}, {2, 8}, {1, 8}, {0, 8},
        };
        for (int i = 0; i < 15; i++) {
            int bit = (fmt >> (14 - i)) & 1;
            m[pos1[i][0]][pos1[i][1]] = bit == 1;
            f[pos1[i][0]][pos1[i][1]] = true;
        }

        int[][] pos2 = {
            {size - 1, 8}, {size - 2, 8}, {size - 3, 8}, {size - 4, 8}, {size - 5, 8}, {size - 6, 8}, {size - 7, 8},
            {8, size - 8}, {8, size - 7}, {8, size - 6}, {8, size - 5}, {8, size - 4}, {8, size - 3}, {8, size - 2}, {8, size - 1},
        };
        for (int i = 0; i < 15; i++) {
            int bit = (fmt >> (14 - i)) & 1;
            m[pos2[i][0]][pos2[i][1]] = bit == 1;
            f[pos2[i][0]][pos2[i][1]] = true;
        }
    }

    private static void drawVersion(boolean[][] m, boolean[][] f, int version) {
        int ver = versionInfo(version);
        int size = m.length;
        for (int i = 0; i < 18; i++) {
            int bit = (ver >> i) & 1;
            int a = size - 11 + (i % 3);
            int b = i / 3;
            m[a][b] = bit == 1; f[a][b] = true;
            m[b][a] = bit == 1; f[b][a] = true;
        }
    }

    private static int formatInfo(int mask) {
        int data = mask;
        int rem = data << 10;
        for (int i = 14; i >= 10; i--) {
            if (((rem >> i) & 1) != 0) rem ^= (0x537 << (i - 10));
        }
        return ((data << 10) | rem) ^ 0x5412;
    }

    private static int versionInfo(int version) {
        int rem = version << 12;
        for (int i = 17; i >= 12; i--) {
            if (((rem >> i) & 1) != 0) rem ^= (0x1F25 << (i - 12));
        }
        return (version << 12) | rem;
    }
}
