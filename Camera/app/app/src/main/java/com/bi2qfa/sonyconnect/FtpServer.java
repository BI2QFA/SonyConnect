package com.bi2qfa.sonyconnect;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自包含的匿名只读 FTP 服务器（被动模式）。
 *
 * - 只实现浏览/下载命令，所有写命令回 550。
 * - 根目录限定在传入的 rootDir 内，防止 ../ 逃逸。
 * - 仅支持被动模式（PASV / EPSV）。
 */
public class FtpServer {

    public interface Listener {
        void onLog(String msg);
    }

    /** 虚拟缩略图 RETR 的小图来源钩子（ThumbPrefetcher 缓存优先，未命中回落直接提取） */
    public interface PayloadSource {
        /** 命中缓存返回字节；未命中/不支持返回 null。relPath 为虚拟路径里的相机相对路径 */
        byte[] extractSmall(String relPath, File f);
    }

    private volatile PayloadSource payloadSource;

    public void setPayloadSource(PayloadSource source) {
        this.payloadSource = source;
    }

    // ===== 静态实例注册：让 ExitCompletedReceiver 在 Activity 已死时也能关掉服务、释放 SD 句柄 =====
    private static FtpServer sActiveInstance;

    public static synchronized void setActiveInstance(FtpServer server) {
        sActiveInstance = server;
    }

    /** 强制停止当前存活的实例（无实例则空操作），用于退出兜底。 */
    public static synchronized void killActiveInstance() {
        if (sActiveInstance != null) {
            try { sActiveInstance.stop(); } catch (Throwable t) {}
        }
    }

    private final File rootDir;
    private final int port;
    private final Listener listener;

    private ServerSocket controlSocket;
    private Thread acceptThread;
    private volatile boolean running = false;

    // 会话登记表：stop() 时逐个强拆 socket，确保没有悬空的文件/目录句柄拖住 SD 卡交接
    private final List<Session> sessions = new ArrayList<Session>();

    public FtpServer(File rootDir, int port, Listener listener) {
        this.rootDir = rootDir;
        this.port = port;
        this.listener = listener;
        setActiveInstance(this);
    }

    public synchronized void start() throws IOException {
        controlSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket client = controlSocket.accept();
                        new Session(client).start();
                    } catch (IOException e) {
                        if (running) log("连接错误: " + e.getMessage());
                    }
                }
            }
        }, "FtpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int getPort() {
        return controlSocket == null ? port : controlSocket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (controlSocket != null) controlSocket.close();
        } catch (IOException e) {}
        controlSocket = null;
        // 强拆所有会话的数据/命令连接：RETR 中断开写入会让读循环立刻结束，
        // 打开的文件与目录流随之关闭，SD 卡得以尽快交还相机固件
        Session[] snapshot;
        synchronized (this) {
            snapshot = sessions.toArray(new Session[sessions.size()]);
        }
        for (int i = 0; i < snapshot.length; i++) {
            try { snapshot[i].forceClose(); } catch (Throwable t) {}
        }
        sessions.clear();
        if (sActiveInstance == this) {
            setActiveInstance(null);
        }
    }

    void registerSession(Session s) {
        synchronized (sessions) {
            sessions.add(s);
        }
    }

    void unregisterSession(Session s) {
        synchronized (sessions) {
            sessions.remove(s);
        }
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private void reply(BufferedWriter out, String s) throws IOException {
        out.write(s + "\r\n");
        out.flush();
    }

    private void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException e) {}
    }

    // Socket / ServerSocket 在 Java 6 未实现 Closeable，需单独重载
    private void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    private void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    // ============ 会话 ============

    private class Session extends Thread {
        private final Socket control;
        private BufferedReader in;
        private BufferedWriter out;
        private File cwd;
        private ServerSocket pasvSocket;
        private Socket dataSocket;   // 当前传输中的数据连接，stop() 时会被强拆
        // RFC 3659 续传偏移：REST 设置，只在紧随的一次传输命令中生效
        private long restartOffset = 0;
        // 预建格式化器，避免每次列目录/取时间都 new（相机 CPU 弱，省开销）
        private final SimpleDateFormat listDateFormat = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
        private final SimpleDateFormat mdtmDateFormat = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);

        Session(Socket control) {
            this.control = control;
            this.cwd = rootDir;
            registerSession(this);
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(control.getInputStream(), "UTF-8"));
                out = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), "UTF-8"));
                control.setTcpNoDelay(true);   // 命令通道关 Nagle，响应更快
                reply(out, "220 Welcome to SonyFTP (read-only)");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) continue;
                    handle(line);
                }
            } catch (IOException e) {
                // 连接断开
            } finally {
                closeQuietly(pasvSocket);
                closeQuietly(dataSocket);
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(control);
                unregisterSession(this);
            }
        }

        /** 供服务器整体停止时强拆：关掉 PASV/数据/命令三个 socket，阻塞中的读立刻抛错收尾。 */
        void forceClose() {
            closeQuietly(pasvSocket);
            pasvSocket = null;
            closeQuietly(dataSocket);
            dataSocket = null;
            closeQuietly(control);
        }

        private void handle(String line) {
            try {
                String cmd;
                String arg = "";
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    cmd = line;
                } else {
                    cmd = line.substring(0, sp);
                    arg = line.substring(sp + 1);
                }
                dispatch(cmd.trim().toUpperCase(Locale.US), arg.trim());
            } catch (IOException e) {
                closeQuietly(control);
            } catch (Throwable t) {
                try { reply(out, "550 " + t.getMessage()); } catch (IOException e) {}
            }
        }

        private void dispatch(String cmd, String arg) throws IOException {
            if (cmd.equals("USER")) { reply(out, "331 Please specify the password."); }
            else if (cmd.equals("PASS")) { reply(out, "230 Login successful (anonymous)."); }
            else if (cmd.equals("SYST")) { reply(out, "215 UNIX Type: L8"); }
            else if (cmd.equals("FEAT")) {
                reply(out, "211-Features:");
                reply(out, " UTF8");
                reply(out, " REST STREAM");
                reply(out, "211 End");
            }
            else if (cmd.equals("TYPE")) { reply(out, "200 Type set to I."); }
            else if (cmd.equals("OPTS")) { reply(out, "200 OK."); }
            else if (cmd.equals("NOOP")) { reply(out, "200 NOOP ok."); }
            else if (cmd.equals("HELP")) { reply(out, "214 Commands: USER PASS SYST FEAT TYPE PWD CWD CDUP PASV EPSV LIST NLST REST RETR SIZE MDTM QUIT NOOP."); }
            else if (cmd.equals("PWD")) {
                reply(out, "257 \"" + virtualPath(cwd) + "\" is current directory.");
            }
            else if (cmd.equals("CWD")) { doCwd(arg); }
            else if (cmd.equals("CDUP")) { doCwd(".."); }
            else if (cmd.equals("PASV")) { doPasv(); }
            else if (cmd.equals("EPSV")) { doEpsv(arg); }
            else if (cmd.equals("LIST")) { restartOffset = 0; doList(arg); }
            else if (cmd.equals("NLST")) { restartOffset = 0; doNlst(arg); }
            else if (cmd.equals("RETR")) { doRetr(arg); }
            else if (cmd.equals("SIZE")) { doSize(arg); }
            else if (cmd.equals("MDTM")) { doMdtm(arg); }
            else if (cmd.equals("REST")) { doRest(arg); }
            else if (cmd.equals("QUIT")) { reply(out, "221 Goodbye."); closeQuietly(control); }
            // 写命令全部拒绝
            else if (cmd.equals("STOR") || cmd.equals("STOU") || cmd.equals("APPE")
                    || cmd.equals("DELE") || cmd.equals("RNFR") || cmd.equals("RNTO")
                    || cmd.equals("MKD") || cmd.equals("RMD") || cmd.equals("SITE")
                    || cmd.equals("ALLO") || cmd.equals("CHMOD")) {
                reply(out, "550 Permission denied (read-only server).");
            }
            else { reply(out, "500 Unknown command."); }
        }

        // ===== 目录操作 =====

        private void doCwd(String arg) throws IOException {
            File target = resolveVirtual(arg);
            if (!target.isDirectory()) {
                reply(out, "550 Not a directory.");
                return;
            }
            cwd = target;
            reply(out, "250 Directory changed to \"" + virtualPath(cwd) + "\".");
        }

        private String virtualPath(File f) throws IOException {
            String root = rootDir.getCanonicalPath();
            String canon = f.getCanonicalPath();
            if (canon.equals(root)) return "/";
            String rel = canon.substring(root.length() + 1);
            return "/" + rel;
        }

        // 把虚拟路径（FTP 视角）解析成根目录内的真实文件，禁止逃逸
        private File resolveVirtual(String arg) throws IOException {
            if (arg == null || arg.length() == 0) return rootDir;
            File base = arg.startsWith("/") ? rootDir : cwd;
            File target = new File(base, arg);
            String canon = target.getCanonicalPath();
            String rootCanon = rootDir.getCanonicalPath();
            if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                throw new SecurityException("Access denied.");
            }
            return target;
        }

        // ===== 被动模式 =====

        private void doPasv() throws IOException {
            closeQuietly(pasvSocket);
            pasvSocket = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"));
            int p = pasvSocket.getLocalPort();
            String ip = control.getLocalAddress().getHostAddress().replace('.', ',');
            reply(out, "227 Entering Passive Mode (" + ip + "," + (p / 256) + "," + (p % 256) + ").");
        }

        private void doEpsv(String arg) throws IOException {
            closeQuietly(pasvSocket);
            pasvSocket = new ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"));
            int p = pasvSocket.getLocalPort();
            reply(out, "229 Entering Extended Passive Mode (|||" + p + "|).");
        }

        private Socket acceptData() throws IOException {
            if (pasvSocket == null) {
                reply(out, "425 Use PASV or EPSV first.");
                return null;
            }
            ServerSocket ss = pasvSocket;
            pasvSocket = null;
            Socket data = ss.accept();
            closeQuietly(ss);
            dataSocket = data;   // 登记当前数据连接，便于 stop()/forceClose() 强拆释放 SD 句柄
            // 数据通道：关 Nagle + 放大收发缓冲，提升吞吐
            data.setTcpNoDelay(true);
            data.setSendBufferSize(256 * 1024);
            data.setReceiveBufferSize(256 * 1024);
            return data;
        }

        // ===== 列出 / 下载 =====

        private void doList(String arg) throws IOException {
            AppLog.i("Ftp", "LIST " + arg);
            reply(out, "150 Opening data connection for directory listing.");
            Socket data = null;
            try {
                data = acceptData();
                if (data == null) return;
                BufferedWriter dw = new BufferedWriter(new OutputStreamWriter(data.getOutputStream(), "UTF-8"));
                File[] files = cwd.listFiles();
                if (files != null) {
                    for (File f : files) {
                        dw.write(listLine(f));
                        dw.write("\r\n");
                    }
                }
                dw.flush();
                reply(out, "226 Directory send OK.");
            } catch (IOException e) {
                reply(out, "425 Error listing directory.");
            } finally {
                closeQuietly(data);
            }
        }

        private void doNlst(String arg) throws IOException {
            reply(out, "150 Opening data connection for file list.");
            Socket data = null;
            try {
                data = acceptData();
                if (data == null) return;
                BufferedWriter dw = new BufferedWriter(new OutputStreamWriter(data.getOutputStream(), "UTF-8"));
                File[] files = cwd.listFiles();
                if (files != null) {
                    for (File f : files) {
                        dw.write(f.getName());
                        dw.write("\r\n");
                    }
                }
                dw.flush();
                reply(out, "226 File list send OK.");
            } catch (IOException e) {
                reply(out, "425 Error listing.");
            } finally {
                closeQuietly(data);
            }
        }

        private void doRetr(String arg) throws IOException {
            AppLog.i("Ftp", "RETR " + arg);
            // SonyConnect 虚拟缩略图路径优先：/.sonyconnect/th|pv/<相机相对路径>
            String[] virt = matchVirtual(arg);
            if (virt != null) {
                doRetrVirtual(virt[0], virt[1]);
                return;
            }
            File f = resolveVirtual(arg);
            if (!f.exists() || f.isDirectory()) {
                reply(out, "550 No such file or directory.");
                return;
            }
            // 续传偏移一次性生效：取出即清零；==长度时发 0 字节后正常收尾
            long off = restartOffset;
            restartOffset = 0;
            if (off < 0 || off > f.length()) {
                reply(out, "550 Invalid restart offset.");
                return;
            }
            reply(out, "150 Opening binary mode data connection for " + f.getName() + " (" + f.length() + " bytes).");
            Socket data = null;
            RandomAccessFile raf = null;
            try {
                data = acceptData();
                if (data == null) return;
                OutputStream dos = data.getOutputStream();
                raf = new RandomAccessFile(f, "r");
                if (off > 0) raf.seek(off);
                // 128KB 大缓冲：减少 read/write 系统调用次数（相机 CPU 弱，越少越快）
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = raf.read(buf)) > 0) {
                    dos.write(buf, 0, n);
                }
                dos.flush();
                reply(out, "226 Transfer complete.");
            } catch (IOException e) {
                reply(out, "426 Transfer aborted.");
            } finally {
                closeQuietly(raf);
                closeQuietly(data);
            }
        }

        private void doRest(String arg) throws IOException {
            long v;
            try {
                v = Long.parseLong(arg);
            } catch (Throwable t) {
                v = -1;
            }
            if (v < 0) {
                reply(out, "501 Invalid byte offset.");
                return;
            }
            restartOffset = v;
            reply(out, "350 Restarting at " + v + ". Send STORE or RETRIEVE.");
        }

        private void doSize(String arg) throws IOException {
            String[] virt = matchVirtual(arg);
            if (virt != null) {
                File f = resolveVirtualMedia(virt[1]);
                if (f == null) {
                    reply(out, "550 No such media file.");
                    return;
                }
                byte[] payload = extractPayload(virt[0], virt[1], f);
                if (payload == null) {
                    reply(out, "550 Preview not available.");
                    return;
                }
                reply(out, "213 " + payload.length);
                return;
            }
            File f = resolveVirtual(arg);
            if (!f.exists() || f.isDirectory()) {
                reply(out, "550 No such file.");
                return;
            }
            reply(out, "213 " + f.length());
        }

        private void doMdtm(String arg) throws IOException {
            String[] virt = matchVirtual(arg);
            if (virt != null) {
                File f = resolveVirtualMedia(virt[1]);
                if (f == null) {
                    reply(out, "550 No such file.");
                    return;
                }
                reply(out, "213 " + mdtmDateFormat.format(new Date(f.lastModified())));
                return;
            }
            File f = resolveVirtual(arg);
            if (!f.exists()) {
                reply(out, "550 No such file.");
                return;
            }
            String t = mdtmDateFormat.format(new Date(f.lastModified()));
            reply(out, "213 " + t);
        }

        // ===== SonyConnect 虚拟缩略图路径 =====

        private static final String VIRT_THUMB = "/.sonyconnect/th/";
        private static final String VIRT_PREVIEW = "/.sonyconnect/pv/";

        /** 命中虚拟路径则返回 {kind, 相对路径}，否则 null */
        private String[] matchVirtual(String arg) {
            if (arg == null) return null;
            String p = arg.trim();
            if (p.length() == 0) return null;
            if (!p.startsWith("/")) p = "/" + p;
            if (p.startsWith(VIRT_THUMB)) {
                return new String[]{"th", stripSlashes(p.substring(VIRT_THUMB.length()))};
            }
            if (p.startsWith(VIRT_PREVIEW)) {
                return new String[]{"pv", stripSlashes(p.substring(VIRT_PREVIEW.length()))};
            }
            return null;
        }

        private String stripSlashes(String s) {
            while (s.startsWith("/")) s = s.substring(1);
            return s;
        }

        /** 虚拟路径的相对路径 → 根目录内真实媒体文件（禁止逃逸）；不满足返回 null */
        private File resolveVirtualMedia(String rel) throws IOException {
            if (rel == null || rel.length() == 0) return null;
            File target = new File(rootDir, rel);
            String canon = target.getCanonicalPath();
            String rootCanon = rootDir.getCanonicalPath();
            if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                throw new SecurityException("Access denied.");
            }
            if (!target.exists() || target.isDirectory() || !ThumbnailExtractor.supports(target)) {
                return null;
            }
            return target;
        }

        private byte[] extractPayload(String kind, String rel, File f) {
            try {
                if ("th".equals(kind)) {
                    // 预取缓存钩子（ThumbPrefetcher），未命中回落实时提取
                    PayloadSource src = payloadSource;
                    if (src != null) {
                        byte[] cached = src.extractSmall(rel, f);
                        if (cached != null) return cached;
                    }
                    return ThumbnailExtractor.extractSmall(f);
                }
                return ThumbnailExtractor.extractPreview(f);
            } catch (Throwable t) {
                return null;
            }
        }

        /** 虚拟 RETR：先提取再发 150（长度真实），REST 语义与真实文件一致 */
        private void doRetrVirtual(String kind, String rel) throws IOException {            File f = resolveVirtualMedia(rel);
            if (f == null) {
                reply(out, "550 No such media file.");
                return;
            }
            long off = restartOffset;
            restartOffset = 0;
            byte[] payload = extractPayload(kind, rel, f);
            if (payload == null) {
                reply(out, "550 Preview not available.");
                return;
            }
            if (off < 0 || off > payload.length) {
                reply(out, "550 Invalid restart offset.");
                return;
            }
            reply(out, "150 Opening binary mode data connection for " + f.getName()
                    + " (" + payload.length + " bytes).");
            Socket data = null;
            try {
                data = acceptData();
                if (data == null) return;
                OutputStream dos = data.getOutputStream();
                if (off < payload.length) {
                    dos.write(payload, (int) off, payload.length - (int) off);
                }
                dos.flush();
                reply(out, "226 Transfer complete.");
            } catch (IOException e) {
                reply(out, "426 Transfer aborted.");
            } finally {
                closeQuietly(data);
            }
        }

        private String listLine(File f) {
            String perms = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
            String date = listDateFormat.format(new Date(f.lastModified()));
            long size = f.isDirectory() ? 4096 : f.length();
            return perms + " 1 owner group " + size + " " + date + " " + f.getName();
        }
    }
}
