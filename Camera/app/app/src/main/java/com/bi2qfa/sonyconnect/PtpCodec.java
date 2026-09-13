package com.bi2qfa.sonyconnect;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;














public final class PtpCodec {

    private PtpCodec() {
    }

    
    
    public static final int HEADER_LEN = 8;

    
    public static final int MAX_PACKET = 1024 * 1024;

    






    public static final int CHUNK = 128 * 1024;

    
    public static final int T_INIT_CMD_REQ = 0x0001;
    public static final int T_INIT_CMD_ACK = 0x0002;
    public static final int T_INIT_EVENT_REQ = 0x0003;
    public static final int T_INIT_EVENT_ACK = 0x0004;
    public static final int T_INIT_FAIL = 0x0005;
    public static final int T_OPERATION_REQ = 0x0006;
    public static final int T_OPERATION_RSP = 0x0007;
    public static final int T_EVENT = 0x0008;
    public static final int T_START_DATA = 0x0009;
    public static final int T_DATA = 0x000A;
    public static final int T_END_DATA = 0x000B;
    public static final int T_CANCEL = 0x000C;
    
    public static final int T_PROBE_REQ = 0x000D;
    
    public static final int T_PROBE_RESP = 0x000E;

    
    
    public static final int T_DATA_OPEN = 0x0040;
    public static final int T_DATA_OPEN_ACK = 0x0041;

    
    public static final int DP_NONE = 0;
    public static final int DP_DATA_IN = 1;
    public static final int DP_DATA_OUT = 2;

    
    public static final int FAIL_REJECTED = 0x01;
    public static final int FAIL_UNSUPPORTED = 0x02;
    public static final int FAIL_BUSY = 0x03;
    
    public static final int FAIL_NOT_PAIRED = 0x04;

    
    public static final int OP_PAIR_BEGIN = 0x9001;
    public static final int OP_PAIR_EXCHANGE = 0x9002;
    public static final int OP_PAIR_ABORT = 0x9004;
    
    public static final int OP_PAIR_REMOVE = 0x9005;
    
    





    public static final int OP_PING = 0x9012;
    




    public static final int OP_DEVICE_INFO = 0x9013;
    public static final int OP_LIST_DIR = 0x9020;
    public static final int OP_STAT = 0x9021;
    public static final int OP_GET_OBJECT = 0x9022;
    public static final int OP_THUMB_QUEUE_BEGIN = 0x9023;
    public static final int OP_THUMB_QUEUE_PAUSE = 0x9024;
    public static final int OP_THUMB_QUEUE_RESUME = 0x9025;
    public static final int OP_THUMB_QUEUE_CANCEL = 0x9026;
    
    
    

    
    public static final int EV_THUMB_PROGRESS = 0x9041;
    






    public static final int EV_PAIRED_REMOVED = 0x9042;
    






    public static final int EV_APP_EXITING = 0x9043;
    














    public static final int EV_MODE_SWITCHING = 0x9044;
    
    public static final int MODE_CODE_WIFI = 0;
    
    public static final int MODE_CODE_HOTSPOT = 1;

    
    public static final int RC_OK = 0x2001;
    
    public static final int RC_GENERAL_ERROR = 0x2002;
    
    public static final int RC_SESSION_NOT_OPEN = 0x2003;
    
    public static final int RC_INVALID_TX = 0x2004;
    
    public static final int RC_NOT_SUPPORTED = 0x2005;
    
    public static final int RC_ACCESS_DENIED = 0x2006;
    
    public static final int RC_NOT_FOUND = 0x2007;
    
    public static final int RC_DEVICE_BUSY = 0x2008;
    
    public static final int RC_PAIRING_FAILED = 0x2009;
    
    public static final int RC_CANCELED = 0x200B;

    
    public static final int KIND_THUMB = 0;
    public static final int KIND_PREVIEW = 1;
    public static final int KIND_ORIGINAL = 2;

    
    public static final String VENDOR_TAG = "SonyConnect/2.0";

    
    
    

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static int i32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    public static long i64(byte[] b, int off) {
        long lo = i32(b, off) & 0xFFFFFFFFL;
        long hi = i32(b, off + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    public static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    public static void put64(byte[] b, int off, long v) {
        put32(b, off, (int) (v & 0xFFFFFFFFL));
        put32(b, off + 4, (int) ((v >>> 32) & 0xFFFFFFFFL));
    }

    
    
    

    
    public static byte[] header(int type, int payloadLen) {
        byte[] h = new byte[HEADER_LEN];
        put32(h, 0, HEADER_LEN + payloadLen);
        put32(h, 4, type);
        return h;
    }

    
    public static byte[] frame(int type, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] out = new byte[HEADER_LEN + n];
        put32(out, 0, out.length);
        put32(out, 4, type);
        if (n > 0) {
            System.arraycopy(payload, 0, out, HEADER_LEN, n);
        }
        return out;
    }

    
    public static final class Msg {
        public int type;
        public byte[] body;

        public Msg(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    



    public static Msg read(InputStream in) throws IOException {
        byte[] h = new byte[HEADER_LEN];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        h[0] = (byte) first;
        readFully(in, h, 1, HEADER_LEN - 1);

        int len = i32(h, 0);
        int type = i32(h, 4);
        if (len < HEADER_LEN || len > MAX_PACKET) {
            throw new IOException("坏包长度: " + len + " (type=" + type + ")");
        }
        byte[] body = new byte[len - HEADER_LEN];
        if (body.length > 0) {
            readFully(in, body, 0, body.length);
        }
        return new Msg(type, body);
    }

    
    public static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int r = in.read(buf, off + got, len - got);
            if (r < 0) {
                throw new EOFException("包体截断: 还差 " + (len - got) + " 字节");
            }
            got += r;
        }
    }

    
    
    

    
    public static int nameLen(String s) {
        return 1 + s.length() * 2;
    }

    
    public static int writeName(byte[] buf, int off, String s) {
        int chars = Math.min(s.length(), 255);
        buf[off] = (byte) chars;
        for (int i = 0; i < chars; i++) {
            char c = s.charAt(i);
            buf[off + 1 + i * 2] = (byte) (c & 0xFF);
            buf[off + 1 + i * 2 + 1] = (byte) ((c >>> 8) & 0xFF);
        }
        return 1 + chars * 2;
    }

    
    public static String readName(byte[] b, int off, int[] nextOff) {
        int chars = u8(b, off);
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int lo = b[off + 1 + i * 2] & 0xFF;
            int hi = b[off + 1 + i * 2 + 1] & 0xFF;
            sb.append((char) ((hi << 8) | lo));
        }
        if (nextOff != null && nextOff.length > 0) {
            nextOff[0] = off + 1 + chars * 2;
        }
        return sb.toString();
    }

    
    
    

    
    public static byte[] initCmdReq(byte[] guid, String friendlyName, int protoVer) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl + 4];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        put32(p, 16 + nl, protoVer);
        return frame(T_INIT_CMD_REQ, p);
    }

    
    public static byte[] initCmdAck(int connNo, byte[] guid, String friendlyName, int protoVer) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[4 + 16 + nl + 4];
        put32(p, 0, connNo);
        System.arraycopy(guid, 0, p, 4, 16);
        writeName(p, 20, friendlyName);
        put32(p, 20 + nl, protoVer);
        return frame(T_INIT_CMD_ACK, p);
    }

    
    public static byte[] initEventReq(int connNo) {
        byte[] p = new byte[4];
        put32(p, 0, connNo);
        return frame(T_INIT_EVENT_REQ, p);
    }

    
    public static byte[] initEventAck() {
        return frame(T_INIT_EVENT_ACK, new byte[0]);
    }

    
    public static byte[] initFail(int reason) {
        byte[] p = new byte[4];
        put32(p, 0, reason);
        return frame(T_INIT_FAIL, p);
    }

    
    public static byte[] opReq(int dataPhase, int opCode, int txId, int[] params) {
        return frame(T_OPERATION_REQ, opBody(dataPhase, opCode, txId, params));
    }

    
    public static byte[] opRsp(int dataPhase, int respCode, int txId, int[] params) {
        return frame(T_OPERATION_RSP, opBody(dataPhase, respCode, txId, params));
    }

    
    private static byte[] opBody(int dataPhase, int code, int txId, int[] params) {
        int n = params == null ? 0 : params.length;
        byte[] p = new byte[10 + n * 4];
        put32(p, 0, dataPhase);
        put16(p, 4, code);
        put32(p, 6, txId);
        for (int i = 0; i < n; i++) {
            put32(p, 10 + i * 4, params[i]);
        }
        return p;
    }

    
    public static final class Op {
        public int dataPhase;
        public int code;
        public int txId;
        public int[] params;
    }

    public static Op parseOp(byte[] body) throws IOException {
        if (body.length < 10) {
            throw new IOException("操作包过短: " + body.length);
        }
        Op o = new Op();
        o.dataPhase = i32(body, 0);
        o.code = u16(body, 4);
        o.txId = i32(body, 6);
        int n = (body.length - 10) / 4;
        o.params = new int[n];
        for (int i = 0; i < n; i++) {
            o.params[i] = i32(body, 10 + i * 4);
        }
        return o;
    }

    









    public static byte[] opReqBlob(int opCode, int txId, byte[] blob) {
        return opReqBlobParams(opCode, txId, null, blob);
    }

    













    public static byte[] opReqBlobParams(int opCode, int txId, int[] params, byte[] blob) {
        int n = params == null ? 0 : params.length;
        int bl = blob == null ? 0 : blob.length;
        byte[] p = new byte[4 + 2 + 4 + 4 + n * 4 + 4 + bl];
        put32(p, 0, DP_DATA_IN);
        put16(p, 4, opCode);
        put32(p, 6, txId);
        put32(p, 10, n);
        for (int i = 0; i < n; i++) {
            put32(p, 14 + i * 4, params[i]);
        }
        int o = 14 + n * 4;
        put32(p, o, bl);
        if (bl > 0) {
            System.arraycopy(blob, 0, p, o + 4, bl);
        }
        return frame(T_OPERATION_REQ, p);
    }

    











    public static byte[] opRspBlob(int respCode, int txId, int[] params, byte[] blob) {
        int n = params == null ? 0 : params.length;
        int bl = blob == null ? 0 : blob.length;
        byte[] p = new byte[4 + 2 + 4 + 4 + n * 4 + 4 + bl];
        put32(p, 0, DP_DATA_IN);
        put16(p, 4, respCode);
        put32(p, 6, txId);
        put32(p, 10, n);
        for (int i = 0; i < n; i++) {
            put32(p, 14 + i * 4, params[i]);
        }
        int o = 14 + n * 4;
        put32(p, o, bl);
        if (bl > 0) {
            System.arraycopy(blob, 0, p, o + 4, bl);
        }
        return frame(T_OPERATION_RSP, p);
    }

    
    public static final class OpBlob {
        public int dataPhase;
        public int code;
        public int txId;
        public int[] params;
        
        public byte[] blob;
    }

    public static OpBlob parseOpBlob(byte[] body) throws IOException {
        if (body.length < 18) {
            throw new IOException("扩展响应包过短: " + body.length);
        }
        OpBlob o = new OpBlob();
        o.dataPhase = i32(body, 0);
        o.code = u16(body, 4);
        o.txId = i32(body, 6);
        int n = i32(body, 10);
        if (n < 0 || 14 + n * 4 + 4 > body.length) {
            throw new IOException("扩展响应 paramCount 越界: " + n);
        }
        o.params = new int[n];
        for (int i = 0; i < n; i++) {
            o.params[i] = i32(body, 14 + i * 4);
        }
        int bl = i32(body, 14 + n * 4);
        if (bl < 0 || 14 + n * 4 + 4 + bl > body.length) {
            throw new IOException("扩展响应 blobLen 越界: " + bl);
        }
        o.blob = new byte[bl];
        if (bl > 0) {
            System.arraycopy(body, 14 + n * 4 + 4, o.blob, 0, bl);
        }
        return o;
    }

    
    public static byte[] event(int evCode, int txId, int[] params) {
        int n = params == null ? 0 : params.length;
        byte[] p = new byte[6 + n * 4];
        put16(p, 0, evCode);
        put32(p, 2, txId);
        for (int i = 0; i < n; i++) {
            put32(p, 6 + i * 4, params[i]);
        }
        return frame(T_EVENT, p);
    }

    public static final class Ev {
        public int code;
        public int txId;
        public int[] params;
    }

    public static Ev parseEvent(byte[] body) throws IOException {
        if (body.length < 6) {
            throw new IOException("事件包过短: " + body.length);
        }
        Ev e = new Ev();
        e.code = u16(body, 0);
        e.txId = i32(body, 2);
        int n = (body.length - 6) / 4;
        e.params = new int[n];
        for (int i = 0; i < n; i++) {
            e.params[i] = i32(body, 6 + i * 4);
        }
        return e;
    }

    
    
    

    



    public static byte[] startData(int txId, long total) {
        byte[] p = new byte[12];
        put32(p, 0, txId);
        put64(p, 4, total);
        return frame(T_START_DATA, p);
    }

    





    public static byte[] dataPacket(int txId, byte[] src, int off, int len) {
        byte[] p = new byte[4 + len];
        put32(p, 0, txId);
        if (len > 0) {
            System.arraycopy(src, off, p, 4, len);
        }
        return frame(T_DATA, p);
    }

    
    public static byte[] endData(int txId, byte[] src, int off, int len) {
        byte[] p = new byte[4 + len];
        put32(p, 0, txId);
        if (len > 0) {
            System.arraycopy(src, off, p, 4, len);
        }
        return frame(T_END_DATA, p);
    }

    

    
    public static final int DATA_PAYLOAD_OFFSET = HEADER_LEN + 4;

    










    public static int dataFrameCapacity() {
        return DATA_PAYLOAD_OFFSET + CHUNK;
    }

    
    public static int dataFrameLen(int payloadLen) {
        return DATA_PAYLOAD_OFFSET + payloadLen;
    }

    





    public static void fillDataFrame(byte[] frame, int type, int txId, int payloadLen) {
        put32(frame, 0, DATA_PAYLOAD_OFFSET + payloadLen);
        put32(frame, 4, type);
        put32(frame, HEADER_LEN, txId);
    }

    
    public static byte[] dataPacket(int txId, byte[] data) {
        int n = data == null ? 0 : data.length;
        byte[] p = new byte[4 + n];
        put32(p, 0, txId);
        if (n > 0) {
            System.arraycopy(data, 0, p, 4, n);
        }
        return frame(T_DATA, p);
    }

    
    public static byte[] endData(int txId, byte[] data) {
        int n = data == null ? 0 : data.length;
        byte[] p = new byte[4 + n];
        put32(p, 0, txId);
        if (n > 0) {
            System.arraycopy(data, 0, p, 4, n);
        }
        return frame(T_END_DATA, p);
    }

    
    public static byte[] cancel(int txId) {
        byte[] p = new byte[4];
        put32(p, 0, txId);
        return frame(T_CANCEL, p);
    }

    
    public static int txIdOf(byte[] body) {
        return body.length >= 4 ? i32(body, 0) : 0;
    }

    
    public static long totalOf(byte[] body) throws IOException {
        if (body.length < 12) {
            throw new IOException("Start Data 载荷过短: " + body.length);
        }
        return i64(body, 4);
    }

    
    
    

    
    public static byte[] dataOpen(byte[] guid, int connNo, long token) {
        byte[] p = new byte[28];
        System.arraycopy(guid, 0, p, 0, 16);
        put32(p, 16, connNo);
        put64(p, 20, token);
        return frame(T_DATA_OPEN, p);
    }

    public static byte[] dataOpenAck() {
        return frame(T_DATA_OPEN_ACK, new byte[0]);
    }

    
    public static final class DataOpen {
        public byte[] guid;
        public int connNo;
        public long token;
    }

    public static DataOpen parseDataOpen(byte[] body) throws IOException {
        if (body.length < 28) {
            throw new IOException("DATA_OPEN 载荷过短: " + body.length);
        }
        DataOpen d = new DataOpen();
        d.guid = new byte[16];
        System.arraycopy(body, 0, d.guid, 0, 16);
        d.connNo = i32(body, 16);
        d.token = i64(body, 20);
        return d;
    }

    
    
    

    




    public static byte[] probe(boolean request, byte[] guid, String friendlyName,
                               int protoPort, int filePort, boolean pairingMode, boolean paired) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl + 8];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        int o = 16 + nl;
        put16(p, o, protoPort);
        put16(p, o + 2, filePort);
        p[o + 4] = (byte) (pairingMode ? 1 : 0);
        p[o + 5] = (byte) (paired ? 1 : 0);
        put16(p, o + 6, 0);
        return frame(request ? T_PROBE_REQ : T_PROBE_RESP, p);
    }

    
    public static final class Probe {
        public byte[] guid;
        public String name;
        public boolean vendor;
        public int protoPort;
        public int filePort;
        public boolean pairingMode;
        public boolean paired;
    }

    public static Probe parseProbe(byte[] body) throws IOException {
        if (body.length < 17) {
            throw new IOException("探测包过短: " + body.length);
        }
        Probe pr = new Probe();
        pr.guid = new byte[16];
        System.arraycopy(body, 0, pr.guid, 0, 16);
        int[] next = new int[1];
        pr.name = readName(body, 16, next);
        int o = next[0];
        if (o + 8 <= body.length) {
            pr.vendor = true;
            pr.protoPort = u16(body, o);
            pr.filePort = u16(body, o + 2);
            pr.pairingMode = body[o + 4] != 0;
            pr.paired = body[o + 5] != 0;
        }
        return pr;
    }

    



    public static byte[] probeCompact(boolean request, byte[] guid, String friendlyName) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        return frame(request ? T_PROBE_REQ : T_PROBE_RESP, p);
    }

    
    public static boolean hasVendorTag(String friendlyName) {
        return friendlyName != null && friendlyName.endsWith(VENDOR_TAG);
    }

    
    
    

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    
    public static String hex(byte[] b) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(HEX[(b[i] >> 4) & 0xF]).append(HEX[b[i] & 0xF]);
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) {
            throw new IllegalArgumentException("非法十六进制串");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法十六进制串");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static boolean eq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    
    public static boolean eqConst(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
