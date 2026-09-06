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

public class FtpServer {

    public interface Listener {
        void onLog(String msg);
    }

    public interface PayloadSource {

        byte[] extractSmall(String relPath, File f);
    }

    private volatile PayloadSource payloadSource;

    public void setPayloadSource(PayloadSource source) {
        this.payloadSource = source;
    }

    private static FtpServer sActiveInstance;

    public static synchronized void setActiveInstance(FtpServer server) {
        sActiveInstance = server;
    }

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

    private void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    private void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }

    private class Session extends Thread {
        private final Socket control;
        private BufferedReader in;
        private BufferedWriter out;
        private File cwd;
        private ServerSocket pasvSocket;
        private Socket dataSocket;

        private long restartOffset = 0;

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
                control.setTcpNoDelay(true);
                reply(out, "220 Welcome to SonyFTP (read-only)");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() == 0) continue;
                    handle(line);
                }
            } catch (IOException e) {

            } finally {
                closeQuietly(pasvSocket);
                closeQuietly(dataSocket);
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(control);
                unregisterSession(this);
            }
        }

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

            else if (cmd.equals("STOR") || cmd.equals("STOU") || cmd.equals("APPE")
                    || cmd.equals("DELE") || cmd.equals("RNFR") || cmd.equals("RNTO")
                    || cmd.equals("MKD") || cmd.equals("RMD") || cmd.equals("SITE")
                    || cmd.equals("ALLO") || cmd.equals("CHMOD")) {
                reply(out, "550 Permission denied (read-only server).");
            }
            else { reply(out, "500 Unknown command."); }
        }

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
            dataSocket = data;

            data.setTcpNoDelay(true);
            data.setSendBufferSize(256 * 1024);
            data.setReceiveBufferSize(256 * 1024);
            return data;
        }

        private void doList(String arg) throws IOException {
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

        private static final String VIRT_THUMB = "/.sonyconnect/th/";
        private static final String VIRT_PREVIEW = "/.sonyconnect/pv/";

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
