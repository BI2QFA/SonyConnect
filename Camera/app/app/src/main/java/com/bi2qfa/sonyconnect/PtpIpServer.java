package com.bi2qfa.sonyconnect;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

























public class PtpIpServer {

    
    
    

    
    public interface Handler {
        
        byte[] guid();

        
        String deviceName();

        
        byte[] deviceInfo();

        




        int batteryPct();

        boolean hasLens();

        





        String lensName();

        
        boolean allowInitiator(byte[] guid);

        
        boolean isPairingMode();

        



        boolean hasPairedInitiator();

        
        byte[] readObject(String path, int kind, long offset, int length);

        








        ObjectReader openObject(String path, int kind) throws IOException;

        



        interface ObjectReader {
            




            int readInto(long offset, byte[] dst, int off, int length);

            void close();
        }

        
        long objectSize(String path, int kind);

        
        long objectMtime(String path);

        
        byte[] listDir(String path);

        
        void onThumbControl(int op, String[] paths);

        boolean recEnter();

        void recLeave();

        byte[] recState();

        int recLvStart();

        void recLvStop();

        String recShoot();

        boolean recAf(boolean on);

        boolean recZoom(int dir, int speed);

        boolean recSetProp(String key, String value);

        boolean recTouchAf(float x, float y);

        boolean recMovie(boolean start);

        String recError();
    }

    








    public interface PairingHandler {
        
        boolean isDevicePaired(byte[] guid8);

        
        boolean isPairingOpen();

        
        String pairingCode();

        





        void onPaired(byte[] guid8, String peerDeviceName);

        











        void onPairedDeviceSeen(byte[] guid8, String peerDeviceName);

        
        void onPairingFailed();

        
        void onPairingAborted();

        



        void onUnpair(byte[] guid8);
    }

    
    static final int TX_PAIR_BEGIN = 0x41;
    static final int TX_PAIR_EXCHANGE = 0x42;
    
    static final int TX_PAIR_ABORT = 0x44;

    
    static byte[] ascii(String s) {
        byte[] o = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            o[i] = (byte) (s.charAt(i) & 0xFF);
        }
        return o;
    }

    
    static String utf8(byte[] b, int off, int len) {
        if (b == null || off < 0 || off >= b.length || len <= 0) {
            return "";
        }
        int n = Math.min(len, b.length - off);
        try {
            return new String(b, off, n, "UTF-8");
        } catch (Exception e) {
            return new String(b, off, n);
        }
    }

    
    static byte[] utf8(String s) {
        if (s == null) {
            return new byte[0];
        }
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    
    static byte[] shortId(byte[] guid16) {
        byte[] o = new byte[8];
        if (guid16 != null) {
            System.arraycopy(guid16, 0, o, 0, Math.min(8, guid16.length));
        }
        return o;
    }

    
    private byte[] guid16() {
        byte[] g = handler.guid();
        byte[] o = new byte[16];
        if (g != null) {
            System.arraycopy(g, 0, o, 0, Math.min(g.length, 16));
        }
        return o;
    }

    
    static String[] splitLines(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new String[0];
        }
        String s;
        try {
            s = new String(blob, "UTF-8");
        } catch (Exception e) {
            s = new String(blob);
        }
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x0A || c == 0x0D) {
                if (i > start) {
                    out.add(s.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out.toArray(new String[out.size()]);
    }

    
    public static final int[] PROTO_PORT_CANDIDATES = {15740, 15741, 25740, 25741};

    
    private static final int FILE_PORT_SPAN = 16;

    
    public static final int PROTO_VERSION = (1 << 16);

    
    private static final int CONTROL_IDLE_MS = 20000;

    
    private static final int DATA_IDLE_MS = 15000;

    
    private static final long TOKEN_TTL_MS = 15000L;

    
    
    

    private static PtpIpServer sActiveInstance;

    public static synchronized void setActiveInstance(PtpIpServer server) {
        sActiveInstance = server;
    }

    
    public static synchronized void killActiveInstance() {
        if (sActiveInstance != null) {
            try {
                sActiveInstance.stop();
            } catch (Throwable t) {
                
            }
        }
    }

    private final Handler handler;

    
    private volatile PairingHandler pairingHandler;

    
    private boolean pairingBusy;

    private ServerSocket protoServer;
    private ServerSocket fileServer;
    private DatagramSocket probeSocket;

    private Thread protoAcceptThread;
    private Thread fileAcceptThread;
    private Thread probeThread;

    private volatile boolean running;

    private int protoPort;
    private int filePort;

    
    private final List<Conn> conns = new ArrayList<Conn>();
    
    private final List<Conn> fileConns = new ArrayList<Conn>();
    
    private final Map<Long, Pending> pending = new HashMap<Long, Pending>();

    private long connCounter;
    private long tokenCounter;

    public PtpIpServer(Handler handler) {
        this.handler = handler;
        setActiveInstance(this);
    }

    




    public void setPairingHandler(PairingHandler h) {
        this.pairingHandler = h;
    }

    
    private synchronized boolean acquirePairing() {
        if (pairingBusy) {
            return false;
        }
        pairingBusy = true;
        return true;
    }

    private synchronized void releasePairing() {
        pairingBusy = false;
    }

    





    public synchronized boolean isPairingInProgress() {
        return pairingBusy;
    }

    
    public int connectedClientCount() {
        List<Conn> all = snapshot(conns);
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                n++;
            }
        }
        return n;
    }

    
    public String connectedClientName() {
        List<Conn> all = snapshot(conns);
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                return c.peerName == null ? "" : c.peerName;
            }
        }
        return null;
    }

    





    public String connectedClientIdHex() {
        List<Conn> all = snapshot(conns);
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                return PtpCodec.hex(shortId(c.deviceId));
            }
        }
        return null;
    }

    public int getProtoPort() {
        return protoPort;
    }

    public int getFilePort() {
        return filePort;
    }

    public boolean isRunning() {
        return running;
    }

    
    
    

    
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        protoServer = bindPort(PROTO_PORT_CANDIDATES);
        protoPort = protoServer.getLocalPort();

        try {
            fileServer = bindFile(protoPort);
        } catch (IOException e) {
            closeQuietly(protoServer);
            protoServer = null;
            
            
            protoPort = 0;
            throw e;
        }
        filePort = fileServer.getLocalPort();

        
        try {
            probeSocket = new DatagramSocket(null);
            probeSocket.setReuseAddress(true);
            probeSocket.bind(new InetSocketAddress(protoPort));
        } catch (Exception e) {
            closeQuietly(probeSocket);
            probeSocket = null;
        }

        running = true;
        AppLog.i("Net", "服务端启动：协议端口=" + protoPort + " 文件端口=" + filePort
                + (probeSocket != null ? " UDP探测已开" : " UDP探测不可用"));

        final ServerSocket ps = protoServer;
        final ServerSocket fs = fileServer;
        protoAcceptThread = newThread("ptpip-accept", new Runnable() {
            public void run() {
                acceptLoop(ps, false);
            }
        });
        fileAcceptThread = newThread("ptpip-file", new Runnable() {
            public void run() {
                acceptLoop(fs, true);
            }
        });
        if (probeSocket != null) {
            probeThread = newThread("ptpip-probe", new Runnable() {
                public void run() {
                    probeLoop();
                }
            });
        }

    }

    
    private static ServerSocket bindPort(int[] candidates) throws IOException {
        IOException last = null;
        for (int i = 0; i < candidates.length; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(candidates[i]), 8);
                return ss;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("协议端口全部不可用: " + last);
    }

    
    private static ServerSocket bindFile(int base) throws IOException {
        IOException last = null;
        for (int off = 1; off <= FILE_PORT_SPAN; off++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(base + off), 4);
                return ss;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("文件端口全部不可用: " + last);
    }

    private static Thread newThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof ServerSocket) {
                ((ServerSocket) c).close();
            } else if (c instanceof Socket) {
                ((Socket) c).close();
            } else if (c instanceof DatagramSocket) {
                ((DatagramSocket) c).close();
            }
        } catch (Throwable ignored) {
            
        }
    }

    







    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        
        
        
        
        
        pushAppExiting();

        AppLog.w("Net", "服务端停止：拆 " + snapshot(fileConns).size() + " 条文件连接 / "
                + snapshot(conns).size() + " 条协议连接");
        
        List<Conn> data = snapshot(fileConns);
        for (int i = 0; i < data.size(); i++) {
            data.get(i).close();
        }
        
        List<Conn> ctrl = snapshot(conns);
        for (int i = 0; i < ctrl.size(); i++) {
            ctrl.get(i).close();
        }
        
        
        
        
        
        
        
        joinAll(data);
        joinAll(ctrl);
        
        closeQuietly(protoServer);
        closeQuietly(fileServer);
        closeQuietly(probeSocket);
        protoServer = null;
        fileServer = null;
        probeSocket = null;
        
        protoPort = 0;
        filePort = 0;
        
        releasePairing();
        
        synchronized (pending) {
            pending.clear();
        }
        synchronized (conns) {
            conns.clear();
        }
        synchronized (fileConns) {
            fileConns.clear();
        }
        if (sActiveInstance == this) {
            setActiveInstance(null);
        }
    }

    private static List<Conn> snapshot(List<Conn> src) {
        synchronized (src) {
            return new ArrayList<Conn>(src);
        }
    }

    






    private static final long EXIT_JOIN_MS = 2000;

    





    private static void joinAll(List<Conn> list) {
        long per = list.size() > 1 ? Math.max(200L, EXIT_JOIN_MS / list.size()) : EXIT_JOIN_MS;
        for (int i = 0; i < list.size(); i++) {
            Thread t = list.get(i).thread;
            
            if (t == null || t == Thread.currentThread()) continue;
            try {
                t.join(per);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    
    
    
    

    
    

    
    static final class Pending {
        
        byte[] deviceId;
        String path;
        int kind;
        long offset;
        
        int length;
        long expiresAt;
    }

    
    private long issueToken(byte[] deviceId, String path, int kind, long offset, int length) {
        long t;
        synchronized (pending) {
            pruneTokensLocked();
            t = ++tokenCounter;
            Pending p = new Pending();
            p.deviceId = deviceId;
            p.path = path;
            p.kind = kind;
            p.offset = offset;
            p.length = length;
            p.expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
            pending.put(Long.valueOf(t), p);
        }
        return t;
    }

    
    private Pending consumeToken(long token) {
        synchronized (pending) {
            pruneTokensLocked();
            return pending.remove(Long.valueOf(token));
        }
    }

    private void pruneTokensLocked() {
        long now = System.currentTimeMillis();
        List<Long> dead = new ArrayList<Long>();
        for (Map.Entry<Long, Pending> e : pending.entrySet()) {
            if (e.getValue().expiresAt < now) {
                dead.add(e.getKey());
            }
        }
        for (int i = 0; i < dead.size(); i++) {
            pending.remove(dead.get(i));
        }
    }

    
    
    

    static final int ROLE_UNKNOWN = 0;
    static final int ROLE_CONTROL = 1;
    static final int ROLE_EVENT = 2;
    static final int ROLE_FILE = 3;

    private final class Conn implements Runnable {

        final long id;
        private final Socket socket;
        private final boolean fileSide;

        private InputStream in;
        private OutputStream out;

        private int role = ROLE_UNKNOWN;
        private byte[] deviceId;
        private String peerName = "";
        private int connNo;
        private int protoVer;

        private volatile boolean closed;

        



        volatile Thread thread;

        






        private volatile boolean authorized;

        private long lastRead;

        Conn(Socket s, boolean fileSide) {
            synchronized (PtpIpServer.this) {
                this.id = ++connCounter;
            }
            this.socket = s;
            this.fileSide = fileSide;
            this.lastRead = System.currentTimeMillis();
        }

        

        public void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                PtpCodec.Msg first = PtpCodec.read(in);
                if (first == null) {
                    return;
                }
                lastRead = System.currentTimeMillis();

                if (fileSide) {
                    
                    if (first.type == PtpCodec.T_DATA_OPEN) {
                        role = ROLE_FILE;
                        serveFile(first);
                    }
                    return;
                }

                if (first.type == PtpCodec.T_INIT_CMD_REQ) {
                    role = ROLE_CONTROL;
                    socket.setSoTimeout(CONTROL_IDLE_MS);
                    serveControl(first);
                } else if (first.type == PtpCodec.T_INIT_EVENT_REQ) {
                    role = ROLE_EVENT;
                    socket.setSoTimeout(CONTROL_IDLE_MS);
                    serveEvent(first);
                } else {
                    send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                }
            } catch (Throwable t) {
                
            } finally {
                close();
            }
        }

        
        void close() {
            if (closed) {
                return;
            }
            boolean wasControl = role == ROLE_CONTROL;
            closed = true;
            AppLog.i("Net", "断开 conn#" + id + (fileSide ? "（文件）" : "（协议）"));
            closeQuietly(socket);
            List<Conn> bucket = fileSide ? fileConns : conns;
            synchronized (bucket) {
                bucket.remove(this);
            }
            if (wasControl && connectedClientCount() == 0) {
                RecSession.get().leave();
            }
        }

        

        
        private synchronized void send(byte[] framed) throws IOException {
            out.write(framed);
            out.flush();
        }

        





        private synchronized void send(byte[] framed, int off, int len) throws IOException {
            out.write(framed, off, len);
        }

        
        private PtpCodec.Msg recv() throws IOException {
            PtpCodec.Msg m = PtpCodec.read(in);
            if (m == null) {
                throw new EOFException("对端关闭连接");
            }
            lastRead = System.currentTimeMillis();
            return m;
        }


        

        private void serveControl(PtpCodec.Msg first) throws IOException {
            if (first.body.length < 17) {
                send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                return;
            }
            byte[] g = new byte[16];
            System.arraycopy(first.body, 0, g, 0, 16);
            int[] next = new int[1];
            String name = PtpCodec.readName(first.body, 16, next);
            int pv = (next[0] + 4 <= first.body.length) ? PtpCodec.i32(first.body, next[0]) : 0;

            deviceId = g;
            peerName = name;
            protoVer = pv;

            byte[] guid8 = shortId(g);
            AppLog.i("Net", "Init Cmd Req：手机=" + name + " 设备码=" + PtpCodec.hex(guid8)
                    + " 协议版本=" + pv);
            PairingHandler ph = pairingHandler;
            
            
            boolean pairPath = ph != null && !ph.isDevicePaired(guid8) && ph.isPairingOpen();

            if (!pairPath && !handler.allowInitiator(guid8)) {
                
                AppLog.w("Net", "拒绝接入：不在配对表（pairPath=" + pairPath + "）");
                send(PtpCodec.initFail(PtpCodec.FAIL_NOT_PAIRED));
                return;
            }

            
            
            if (!pairPath && ph != null) {
                try {
                    ph.onPairedDeviceSeen(guid8, name);
                } catch (Throwable t) {
                    
                }
            }

            if (!evictOrReject(g)) {
                return;
            }

            
            
            
            if (pairPath && isPairingInProgress()) {
                send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                return;
            }

            connNo = (int) (id & 0x7FFFFFFF);
            send(PtpCodec.initCmdAck(connNo, guid16(), handler.deviceName(), PROTO_VERSION));

            if (pairPath && !pairingLoop(ph)) {
                return;
            }

            
            authorized = true;
            controlLoop();
        }


        private boolean evictOrReject(byte[] g) throws IOException {
            List<Conn> others = snapshot(conns);
            for (int i = 0; i < others.size(); i++) {
                Conn o = others.get(i);
                if (o == this || o.role != ROLE_CONTROL) {
                    continue;
                }
                if (PtpCodec.eq(o.deviceId, g)) {
                    o.close();
                } else {
                    send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                    return false;
                }
            }
            
            List<Conn> fcs = snapshot(fileConns);
            for (int i = 0; i < fcs.size(); i++) {
                Conn fc = fcs.get(i);
                if (!PtpCodec.eq(fc.deviceId, g)) {
                    send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                    return false;
                }
            }
            return true;
        }

        
        
        
        
        
        
        
        
        
        
        
        

        private boolean pairingLoop(PairingHandler ph) throws IOException {
            if (!acquirePairing()) {
                
                
                
                
                PtpCodec.OpBlob b = recvPairOp();
                if (b != null) {
                    send(PtpCodec.opRspBlob(PtpCodec.RC_DEVICE_BUSY, b.txId, null, null));
                }
                return false;
            }
            try {
                return pairingExchange(ph);
            } finally {
                releasePairing();
            }
        }

        
        private PtpCodec.OpBlob recvPairOp() throws IOException {
            PtpCodec.Msg m = recv();
            if (m.type != PtpCodec.T_OPERATION_REQ) {
                return null;
            }
            try {
                return PtpCodec.parseOpBlob(m.body);
            } catch (IOException e) {
                return null;
            }
        }

        private boolean pairingExchange(PairingHandler ph) throws IOException {
            byte[] devIdA = shortId(deviceId);
            byte[] devIdC = shortId(handler.guid());

            
            PtpCodec.OpBlob b1 = recvPairOp();
            if (b1 == null) {
                return false;
            }
            if (b1.code == PtpCodec.OP_PAIR_ABORT) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b1.txId, null, null));
                ph.onPairingAborted();
                return false;
            }
            if (b1.code != PtpCodec.OP_PAIR_BEGIN) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_NOT_SUPPORTED, b1.txId, null, null));
                return false;
            }
            String code = ph.pairingCode();
            if (code == null || !ph.isPairingOpen()) {
                
                send(PtpCodec.opRspBlob(PtpCodec.RC_PAIRING_FAILED, b1.txId, null, null));
                return false;
            }
            String peerDeviceName = utf8(b1.blob, 0, b1.blob == null ? 0 : b1.blob.length).trim();
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b1.txId, null, devIdC));

            
            PtpCodec.OpBlob b2 = recvPairOp();
            if (b2 == null) {
                return false;
            }
            if (b2.code == PtpCodec.OP_PAIR_ABORT) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b2.txId, null, null));
                ph.onPairingAborted();
                return false;
            }
            if (b2.code != PtpCodec.OP_PAIR_EXCHANGE) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_NOT_SUPPORTED, b2.txId, null, null));
                return false;
            }
            String given = utf8(b2.blob, 0, b2.blob == null ? 0 : b2.blob.length).trim();
            if (!PtpCodec.eqConst(ascii(code), ascii(given))) {
                ph.onPairingFailed();
                send(PtpCodec.opRspBlob(PtpCodec.RC_PAIRING_FAILED, b2.txId, null, null));
                return false;
            }
            
            ph.onPaired(devIdA, peerDeviceName);
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b2.txId, null, null));
            return true;
        }


        

        




        private void serveEvent(PtpCodec.Msg first) throws IOException {
            if (first.body.length < 4) {
                send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                return;
            }
            int conn = PtpCodec.i32(first.body, 0);
            Conn owner = findControlByConnNo(conn);
            if (owner == null) {
                send(PtpCodec.initFail(PtpCodec.FAIL_NOT_PAIRED));
                return;
            }
            
            deviceId = owner.deviceId;
            peerName = owner.peerName;
            connNo = conn;
            authorized = true;

            send(PtpCodec.initEventAck());
            eventLoop();
        }

        private Conn findControlByConnNo(int conn) {
            List<Conn> all = snapshot(conns);
            for (int i = 0; i < all.size(); i++) {
                Conn c = all.get(i);
                if (c.role == ROLE_CONTROL && c.connNo == conn) {
                    return c;
                }
            }
            return null;
        }


        

        private void controlLoop() throws IOException {
            while (running && !closed) {
                PtpCodec.Msg m;
                try {
                    m = recv();
                } catch (SocketTimeoutException te) {
                    return;
                }
                if (m.type == PtpCodec.T_OPERATION_REQ) {
                    dispatch(m);
                } else if (m.type == PtpCodec.T_CANCEL) {
                } else {
                }
            }
        }

        
        private String opName(int code) {
            switch (code) {
                case PtpCodec.OP_PING: return "PING(心跳/电量/镜头)";
                case PtpCodec.OP_DEVICE_INFO: return "DEVICE_INFO(设备信息)";
                case PtpCodec.OP_LIST_DIR: return "LIST_DIR(列目录)";
                case PtpCodec.OP_STAT: return "STAT(对象属性)";
                case PtpCodec.OP_GET_OBJECT: return "GET_OBJECT(取对象)";
                case PtpCodec.OP_THUMB_QUEUE_BEGIN: return "THUMB_BEGIN(缩略图预取)";
                case PtpCodec.OP_THUMB_QUEUE_PAUSE: return "THUMB_PAUSE";
                case PtpCodec.OP_THUMB_QUEUE_RESUME: return "THUMB_RESUME";
                case PtpCodec.OP_THUMB_QUEUE_CANCEL: return "THUMB_CANCEL";
                case PtpCodec.OP_PAIR_BEGIN: return "PAIR_BEGIN";
                case PtpCodec.OP_PAIR_EXCHANGE: return "PAIR_EXCHANGE";
                case PtpCodec.OP_PAIR_ABORT: return "PAIR_ABORT";
                case PtpCodec.OP_PAIR_REMOVE: return "PAIR_REMOVE";
                case PtpCodec.OP_REC_ENTER: return "REC_ENTER";
                case PtpCodec.OP_REC_LEAVE: return "REC_LEAVE";
                case PtpCodec.OP_REC_GET_STATE: return "REC_STATE";
                case PtpCodec.OP_LV_START: return "LV_START";
                case PtpCodec.OP_LV_STOP: return "LV_STOP";
                case PtpCodec.OP_SHOOT: return "SHOOT";
                case PtpCodec.OP_AF_HALF: return "AF_HALF";
                case PtpCodec.OP_AF_CANCEL: return "AF_CANCEL";
                case PtpCodec.OP_ZOOM: return "ZOOM";
                case PtpCodec.OP_SET_PROP: return "SET_PROP";
                case PtpCodec.OP_TOUCH_AF: return "TOUCH_AF";
                case PtpCodec.OP_MOVIE_START: return "MOVIE_START";
                case PtpCodec.OP_MOVIE_STOP: return "MOVIE_STOP";
                default: return "?";
            }
        }

        
        private void dispatch(PtpCodec.Msg m) throws IOException {
            PtpCodec.Op op;
            try {
                op = PtpCodec.parseOp(m.body);
            } catch (IOException e) {
                return;
            }
            int tx = op.txId;
            if (op.code != PtpCodec.OP_PING) {   
                AppLog.i("Net", "命令 0x" + Integer.toHexString(op.code) + " " + opName(op.code));
            }

            switch (op.code) {
                case PtpCodec.OP_PING:

                    
                    
                    rspPing(tx);
                    break;

                case PtpCodec.OP_DEVICE_INFO:
                    rspBlobOk(tx, handler.deviceInfo());
                    break;

                case PtpCodec.OP_THUMB_QUEUE_BEGIN:
                case PtpCodec.OP_THUMB_QUEUE_PAUSE:
                case PtpCodec.OP_THUMB_QUEUE_RESUME:
                case PtpCodec.OP_THUMB_QUEUE_CANCEL:
                    handler.onThumbControl(op.code, splitLines(blobOf(m.body)));
                    rspOk(tx, null);
                    break;

                default:
                    if (dispatchRec(op, tx, m) || dispatchData(op, tx, m)) {
                        break;
                    }
                    rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                    break;
            }
        }

        

        private void rspOk(int tx, int[] params) throws IOException {
            send(PtpCodec.opRsp(PtpCodec.DP_NONE, PtpCodec.RC_OK, tx, params));
        }

        private void rspCode(int tx, int code) throws IOException {
            send(PtpCodec.opRsp(PtpCodec.DP_NONE, code, tx, null));
        }

        private void rspBlobOk(int tx, byte[] blob) throws IOException {
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, tx, null, blob));
        }

        






        private void rspPing(int tx) throws IOException {
            String lens = handler.lensName();
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, tx,
                    new int[]{handler.batteryPct(), handler.hasLens() ? 1 : 0},
                    lens == null ? new byte[0] : utf8(lens)));
        }

        
        private byte[] blobOf(byte[] plain) {
            try {
                return PtpCodec.parseOpBlob(plain).blob;
            } catch (IOException e) {
                return new byte[0];
            }
        }

        
        private String pathOf(byte[] plain) {
            return pathOfBlob(blobOf(plain));
        }

        
        private String pathOfBlob(byte[] b) {
            if (b == null || b.length == 0) {
                return "/";
            }
            try {
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return new String(b);
            }
        }

        



        private boolean dispatchData(PtpCodec.Op op, int tx, PtpCodec.Msg m) throws IOException {
            switch (op.code) {
                case PtpCodec.OP_LIST_DIR: {
                    String path = pathOf(m.body);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    byte[] json = handler.listDir(path);
                    if (json == null) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    rspBlobOk(tx, json);
                    return true;
                }
                case PtpCodec.OP_STAT: {
                    String path = pathOf(m.body);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    long size = handler.objectSize(path, PtpCodec.KIND_ORIGINAL);
                    if (size < 0) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    long mtime = handler.objectMtime(path);
                    rspOk(tx, new int[]{
                            (int) size, (int) (size >>> 32),
                            (int) mtime, (int) (mtime >>> 32)});
                    return true;
                }
                case PtpCodec.OP_GET_OBJECT: {
                    
                    
                    PtpCodec.OpBlob g;
                    try {
                        g = PtpCodec.parseOpBlob(m.body);
                    } catch (IOException e) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    String path = pathOfBlob(g.blob);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    int kind = g.params.length > 0 ? g.params[0] : PtpCodec.KIND_THUMB;
                    long off = g.params.length > 1 ? (g.params[1] & 0xFFFFFFFFL) : 0L;
                    if (g.params.length > 2) {
                        off |= ((long) g.params[2]) << 32;
                    }
                    int len = g.params.length > 3 ? g.params[3] : -1;
                    if (off < 0) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    long size = handler.objectSize(path, kind);
                    if (size < 0 || off > size) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    
                    long token = issueToken(deviceId, path, kind, off, len);
                    rspOk(tx, new int[]{(int) token, filePort});
                    return true;
                }
                case PtpCodec.OP_PAIR_BEGIN:
                case PtpCodec.OP_PAIR_EXCHANGE:
                case PtpCodec.OP_PAIR_ABORT:
                    
                    rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                    return true;
                case PtpCodec.OP_PAIR_REMOVE: {
                    
                    
                    PairingHandler ph = pairingHandler;
                    if (ph == null) {
                        rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                        return true;
                    }
                    ph.onUnpair(shortId(deviceId));
                    rspOk(tx, null);
                    return true;
                }
                default:
                    return false;
            }
        }

        private boolean dispatchRec(PtpCodec.Op op, int tx, PtpCodec.Msg m) throws IOException {
            switch (op.code) {
                case PtpCodec.OP_REC_ENTER:
                    if (handler.recEnter()) {
                        rspOk(tx, null);
                    } else {
                        rspBlobErr(tx, PtpCodec.RC_GENERAL_ERROR, handler.recError());
                    }
                    return true;
                case PtpCodec.OP_REC_LEAVE:
                    handler.recLeave();
                    rspOk(tx, null);
                    return true;
                case PtpCodec.OP_REC_GET_STATE:
                    rspBlobOk(tx, handler.recState());
                    return true;
                case PtpCodec.OP_LV_START: {
                    int port = handler.recLvStart();
                    if (port > 0) {
                        rspOk(tx, new int[]{port});
                    } else {
                        rspBlobErr(tx, PtpCodec.RC_GENERAL_ERROR, handler.recError());
                    }
                    return true;
                }
                case PtpCodec.OP_LV_STOP:
                    handler.recLvStop();
                    rspOk(tx, null);
                    return true;
                case PtpCodec.OP_SHOOT: {
                    String path = handler.recShoot();
                    if (path != null) {
                        rspBlobOk(tx, utf8(path));
                    } else {
                        rspBlobErr(tx, PtpCodec.RC_DEVICE_BUSY, handler.recError());
                    }
                    return true;
                }
                case PtpCodec.OP_AF_HALF:
                    rspBool(tx, handler.recAf(true));
                    return true;
                case PtpCodec.OP_AF_CANCEL:
                    rspBool(tx, handler.recAf(false));
                    return true;
                case PtpCodec.OP_ZOOM: {
                    int dir = op.params.length > 0 ? op.params[0] : RecSession.ZOOM_STOP;
                    int speed = op.params.length > 1 ? op.params[1] : 1;
                    rspBool(tx, handler.recZoom(dir, speed));
                    return true;
                }
                case PtpCodec.OP_SET_PROP: {
                    String raw = pathOf(m.body);
                    int eq = raw.indexOf('=');
                    if (eq <= 0) {
                        rspCode(tx, PtpCodec.RC_GENERAL_ERROR);
                        return true;
                    }
                    rspBool(tx, handler.recSetProp(raw.substring(0, eq), raw.substring(eq + 1)));
                    return true;
                }
                case PtpCodec.OP_TOUCH_AF: {
                    if (op.params.length < 2) {
                        rspBool(tx, handler.recTouchAf(-1f, -1f));
                        return true;
                    }
                    float x = op.params[0] / 1000f;
                    float y = op.params[1] / 1000f;
                    rspBool(tx, handler.recTouchAf(x, y));
                    return true;
                }
                case PtpCodec.OP_MOVIE_START:
                    rspBool(tx, handler.recMovie(true));
                    return true;
                case PtpCodec.OP_MOVIE_STOP:
                    rspBool(tx, handler.recMovie(false));
                    return true;
                default:
                    return false;
            }
        }

        private void rspBool(int tx, boolean ok) throws IOException {
            if (ok) {
                rspOk(tx, null);
            } else {
                rspBlobErr(tx, PtpCodec.RC_GENERAL_ERROR, handler.recError());
            }
        }

        private void rspBlobErr(int tx, int code, String msg) throws IOException {
            byte[] blob = msg == null ? new byte[0] : utf8(msg);
            send(PtpCodec.opRspBlob(code, tx, null, blob));
        }

        

        








        private void serveFile(PtpCodec.Msg first) throws IOException {
            AppLog.i("Net", "文件端口 DATA_OPEN（conn#" + id + "）");
            PtpCodec.DataOpen d;
            try {
                d = PtpCodec.parseDataOpen(first.body);
            } catch (IOException e) {
                return;
            }
            deviceId = d.guid;

            Pending p = consumeToken(d.token);
            if (p == null) {
                AppLog.w("Net", "DATA_OPEN 令牌无效/过期 → 断开");
                return;
            }
            if (!PtpCodec.eq(p.deviceId, deviceId)) {
                return;
            }

            Conn owner = findControlByDevice(deviceId);
            if (owner == null) {
                return;
            }
            connNo = owner.connNo;
            socket.setSoTimeout(DATA_IDLE_MS);
            send(PtpCodec.dataOpenAck());

            authorized = true;
            transfer(p);
        }

        








        private void transfer(Pending p) throws IOException {
            AppLog.i("Net", "开始传输 " + p.path + " kind=" + p.kind
                    + " offset=" + p.offset + " len=" + p.length);
            long total = handler.objectSize(p.path, p.kind);
            if (total < 0) {
                return;
            }
            long start = p.offset;
            long end = (p.length < 0) ? total : Math.min(total, start + p.length);
            long remaining = Math.max(0L, end - start);
            int tx = 1;

            Handler.ObjectReader reader = null;
            try {
                reader = handler.openObject(p.path, p.kind);
            } catch (Throwable t) {
                reader = null;
            }

            long sent = 0;
            try {
                send(PtpCodec.startData(tx, remaining));

                
                
                
                
                byte[] frame = new byte[PtpCodec.dataFrameCapacity()];
                while (running && !closed && sent < remaining) {
                    int want = (int) Math.min((long) PtpCodec.CHUNK, remaining - sent);
                    int got = readChunk(reader, p, start + sent, frame,
                            PtpCodec.DATA_PAYLOAD_OFFSET, want);
                    if (got <= 0) {
                        
                        return;
                    }
                    boolean last = sent + got >= remaining;
                    PtpCodec.fillDataFrame(frame,
                            last ? PtpCodec.T_END_DATA : PtpCodec.T_DATA, tx, got);
                    send(frame, 0, PtpCodec.dataFrameLen(got));
                    sent += got;
                }

                if (remaining == 0) {
                    
                    send(PtpCodec.endData(tx, new byte[0]));
                }
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {
                        
                    }
                }
            }
        }

        
        private int readChunk(Handler.ObjectReader reader, Pending p, long offset,
                              byte[] dst, int dstOff, int want) {
            if (reader != null) {
                return reader.readInto(offset, dst, dstOff, want);
            }
            byte[] chunk = handler.readObject(p.path, p.kind, offset, want);
            if (chunk == null || chunk.length == 0) {
                return 0;
            }
            int n = Math.min(chunk.length, want);
            System.arraycopy(chunk, 0, dst, dstOff, n);
            return n;
        }


        private Conn findControlByDevice(byte[] g) {
            List<Conn> all = snapshot(conns);
            for (int i = 0; i < all.size(); i++) {
                Conn c = all.get(i);
                if (c.role == ROLE_CONTROL && c.authorized && PtpCodec.eq(c.deviceId, g)) {
                    return c;
                }
            }
            return null;
        }

        



        private void eventLoop() throws IOException {
            while (running && !closed) {
                PtpCodec.Msg m;
                try {
                    m = recv();
                } catch (SocketTimeoutException te) {
                    continue;
                }
                if (m.type == PtpCodec.T_INIT_EVENT_REQ) {
                    send(PtpCodec.initEventAck());
                } else {
                }
            }
        }
    }

    



    private void acceptLoop(ServerSocket ss, boolean isFile) {
        while (running) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (running) {
                }
                break;
            }
            if (!running) {
                closeQuietly(s);
                break;
            }
            try {
                s.setTcpNoDelay(true);
                s.setSendBufferSize(256 * 1024);
                s.setReceiveBufferSize(256 * 1024);
            } catch (Exception e) {
                
            }
            Conn c = new Conn(s, isFile);
            AppLog.i("Net", (isFile ? "文件" : "协议") + "端口接入 " + s.getInetAddress()
                    + " conn#" + c.id);
            List<Conn> bucket = isFile ? fileConns : conns;
            synchronized (bucket) {
                bucket.add(c);
            }
            
            
            c.thread = newThread("ptpip-conn-" + c.id, c);
        }
    }

    
    
    

    










    private void probeLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                probeSocket.receive(pkt);
            } catch (IOException e) {
                if (running) {
                }
                break;
            }
            try {
                byte[] raw = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), pkt.getOffset(), raw, 0, pkt.getLength());
                PtpCodec.Msg m = PtpCodec.read(new ByteArrayInputStream(raw));
                if (m == null || m.type != PtpCodec.T_PROBE_REQ) {
                    continue;
                }
                PtpCodec.Probe pr = PtpCodec.parseProbe(m.body);
                byte[] resp;
                if (PtpCodec.hasVendorTag(pr.name)) {
                    resp = PtpCodec.probe(false, guid16(), vendorName(),
                            protoPort, filePort,
                            handler.isPairingMode(), handler.hasPairedInitiator());
                } else {
                    resp = PtpCodec.probeCompact(false, guid16(), handler.deviceName());
                }
                DatagramPacket out = new DatagramPacket(resp, resp.length,
                        pkt.getAddress(), pkt.getPort());
                probeSocket.send(out);
            } catch (Throwable t) {
                
            }
        }
    }

    
    private String vendorName() {
        String n = handler.deviceName();
        if (n == null || n.length() == 0) {
            n = "SonyConnect";
        }
        return n + " " + PtpCodec.VENDOR_TAG;
    }

    
    
    

    









    public void pushEvent(int evCode, int txId, int[] params) {
        byte[] framed = PtpCodec.event(evCode, txId, params);
        List<Conn> all = snapshot(conns);
        int sent = 0;
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role != ROLE_EVENT || c.closed || !c.authorized) {
                continue;
            }
            try {
                c.send(framed);
                sent++;
            } catch (IOException e) {
                c.close();
            }
        }
        if (sent == 0) {
        }
    }

    










    public void pushPairRemoved() {
        pushEvent(PtpCodec.EV_PAIRED_REMOVED, 0, null);
    }

    









    public void pushAppExiting() {
        if (modeSwitchNotified) {
            modeSwitchNotified = false;
            return;
        }
        pushEvent(PtpCodec.EV_APP_EXITING, 0, null);
    }

    





    private volatile boolean modeSwitchNotified = false;

    









    public void pushModeSwitching(int mode) {
        modeSwitchNotified = true;
        pushEvent(PtpCodec.EV_MODE_SWITCHING, 0, new int[] { mode });
    }

    












    public void dropAllConnections() {
        pushAppExiting();
        List<Conn> data = snapshot(fileConns);
        for (int i = 0; i < data.size(); i++) {
            data.get(i).close();
        }
        List<Conn> ctrl = snapshot(conns);
        for (int i = 0; i < ctrl.size(); i++) {
            ctrl.get(i).close();
        }
        joinAll(data);
        joinAll(ctrl);
    }

    
    
    

    









    static boolean isPathSafe(String path) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') {
            return false;
        }
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        String[] segs = path.split("/");
        for (int i = 0; i < segs.length; i++) {
            if ("..".equals(segs[i]) || ".".equals(segs[i])) {
                return false;
            }
        }
        return true;
    }
}