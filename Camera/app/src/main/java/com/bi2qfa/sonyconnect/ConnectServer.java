package com.bi2qfa.sonyconnect;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;

public class ConnectServer {

    public static final int PROTOCOL_VERSION = 1;

    public interface Handler {
        String onHello(int proto);

        String onHeartbeat();

        String onInfo();

        int onThumbBegin(List<String> paths);

        void onThumbPause();

        void onThumbResume();

        void onThumbCancel();

        void onExitApp();
    }

    private static ConnectServer sActiveInstance;

    public static void setActiveInstance(ConnectServer s) {
        sActiveInstance = s;
    }

    public static void killActiveInstance() {
        ConnectServer s = sActiveInstance;
        if (s != null) {
            s.stop();
        }
    }

    private final int port;
    private final Handler handler;

    private ServerSocket controlSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile Session current;

    public ConnectServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
        setActiveInstance(this);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        controlSocket = new ServerSocket(port, 2);
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "ConnectAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeQuietly(controlSocket);
        controlSocket = null;
        Session s = current;
        if (s != null) s.forceClose();
        current = null;
        if (acceptThread != null) acceptThread.interrupt();
        acceptThread = null;
        sActiveInstance = null;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = controlSocket.accept();
            } catch (IOException e) {
                break;
            }

            Session old = current;
            if (old != null) old.forceClose();
            Session session = new Session(client);
            current = session;
            session.start();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable t) {
            }
        }
    }

    private final class Session extends Thread {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        Session(Socket socket) {
            super("ConnectSession");
            this.socket = socket;
            BufferedReader r = null;
            BufferedWriter w = null;
            try {
                socket.setTcpNoDelay(true);
                r = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"), 16 * 1024);
                w = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
            } catch (Throwable t) {
                closeQuietly(socket);
            }
            in = r;
            out = w;
        }

        @Override
        public void run() {
            try {
                String line;
                while (running && in != null && (line = in.readLine()) != null) {
                    if (line.length() > 64 * 1024) {
                        write("{\"error\":\"line_too_long\"}");
                        continue;
                    }
                    line = line.trim();
                    if (line.length() == 0) continue;
                    String response = dispatch(line);
                    if (response != null) write(response);
                }
            } catch (IOException e) {

            } finally {
                closeQuietly(socket);
                if (current == this) current = null;
            }
        }

        private String dispatch(String line) {
            Map<String, Object> req;
            try {
                req = SJson.parse(line);
            } catch (Throwable t) {
                return SJson.endObj(SJson.member(SJson.startObj(), "error", "bad_json"));
            }
            String cmd = SJson.asString(req, "cmd");
            try {
                if ("HELLO".equals(cmd)) {
                    int proto = (int) SJson.asLong(req, "proto", 0);
                    String r = handler.onHello(proto);
                    return r != null ? r
                            : SJson.endObj(SJson.member(SJson.member(SJson.startObj(),
                            "rsp", "HELLO"), "proto", PROTOCOL_VERSION));
                }
                if ("HEARTBEAT".equals(cmd)) {
                    String r = handler.onHeartbeat();
                    return r != null ? r : SJson.endObj(SJson.member(SJson.startObj(),
                            "rsp", "HEARTBEAT"));
                }
                if ("INFO".equals(cmd)) {
                    String r = handler.onInfo();
                    return r != null ? r : SJson.endObj(SJson.member(SJson.startObj(),
                            "rsp", "INFO"));
                }
                if ("THUMB_BEGIN".equals(cmd)) {
                    List<String> paths = SJson.asStringList(req, "paths");
                    int accepted = paths == null ? 0 : handler.onThumbBegin(paths);
                    return SJson.endObj(SJson.member(SJson.member(SJson.startObj(),
                            "rsp", "THUMB_BEGIN"), "accepted", accepted));
                }
                if ("THUMB_PAUSE".equals(cmd)) {
                    handler.onThumbPause();
                    return SJson.endObj(SJson.member(SJson.startObj(), "rsp", "THUMB_PAUSE"));
                }
                if ("THUMB_RESUME".equals(cmd)) {
                    handler.onThumbResume();
                    return SJson.endObj(SJson.member(SJson.startObj(), "rsp", "THUMB_RESUME"));
                }
                if ("THUMB_CANCEL".equals(cmd)) {
                    handler.onThumbCancel();
                    return SJson.endObj(SJson.member(SJson.startObj(), "rsp", "THUMB_CANCEL"));
                }
                if ("EXIT_APP".equals(cmd)) {
                    handler.onExitApp();
                    return SJson.endObj(SJson.member(SJson.startObj(), "rsp", "EXIT_APP"));
                }
                return SJson.endObj(SJson.member(SJson.startObj(), "error", "unknown_cmd"));
            } catch (Throwable t) {
                return SJson.endObj(SJson.member(SJson.startObj(), "error", "internal"));
            }
        }

        private void write(String body) {
            try {
                out.write(body);
                out.write("\n");
                out.flush();
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }

        void forceClose() {
            closeQuietly(socket);
        }
    }
}
