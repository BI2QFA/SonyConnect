import com.bi2qfa.sonyconnect.ConnectServer;
import com.bi2qfa.sonyconnect.SJson;
import com.bi2qfa.sonyconnect.ThumbPrefetcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ProtocolTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        sjsonChecks();
        serverSessionChecks();
        prefetcherChecks();
        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void sjsonChecks() {
        Map<String, Object> o = SJson.parse("{\"cmd\":\"HELLO\",\"proto\":1,\"flag\":true}");
        check("SJson 解析 cmd", "HELLO".equals(SJson.asString(o, "cmd")));
        check("SJson 解析 proto", SJson.asLong(o, "proto", -1) == 1);
        check("SJson 解析 flag", Boolean.TRUE.equals(o.get("flag")));

        Map<String, Object> p = SJson.parse(
                "{\"paths\":[\"/a;b.jpg\",\"/c,d.arw\"],\"s\":\"引\\\"号\\\\斜杠\\n换行\"}");
        List<String> arr = SJson.asStringList(p, "paths");
        check("SJson 数组解析", arr != null && arr.size() == 2 && "/a;b.jpg".equals(arr.get(0)));
        check("SJson 转义还原", "引\"号\\斜杠\n换行".equals(SJson.asString(p, "s")));

        String enc = SJson.endObj(SJson.member(SJson.startObj(), "k", "a\"b\\c\td"));
        check("SJson 编码往返", "a\"b\\c\td".equals(SJson.asString(SJson.parse(enc), "k")));

        boolean threw = false;
        try {
            SJson.parse("{\"a\":\"x\"");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("SJson 坏输入抛错", threw);
    }

    private static final List<String> SCRIPT_PATHS = Arrays.asList("/DCIM/a.ARW", "/DCIM/b.JPG");

    static void serverSessionChecks() throws Exception {
        final int[] begins = {0};
        final int[] pauses = {0};
        final int[] resumes = {0};
        final int[] cancels = {0};
        final int[] exits = {0};
        final String[] helloProto = {null};

        ConnectServer.Handler handler = new ConnectServer.Handler() {
            public String onHello(int proto) {
                helloProto[0] = String.valueOf(proto);
                return "{\"rsp\":\"HELLO\",\"proto\":1,\"app\":\"SonyConnectCamera\"}";
            }

            public String onHeartbeat() {
                return "{\"rsp\":\"HEARTBEAT\",\"app\":\"SonyConnectCamera\"}";
            }

            public String onInfo() {
                return "{\"rsp\":\"INFO\",\"model\":\"ILCE-6300\",\"serial\":\"3364328\",\"lens\":null,"
                        + "\"batteryPct\":88,\"thumb\":{\"state\":\"running\",\"done\":3,\"total\":10}}";
            }

            public int onThumbBegin(List<String> paths) {
                begins[0]++;
                return paths == null ? 0 : paths.size();
            }

            public void onThumbPause() {
                pauses[0]++;
            }

            public void onThumbResume() {
                resumes[0]++;
            }

            public void onThumbCancel() {
                cancels[0]++;
            }

            public void onExitApp() {
                exits[0]++;
            }
        };

        ConnectServer server = new ConnectServer(2122, handler);
        server.start();
        try {
            Client c1 = new Client();
            check("HELLO 应答", c1.req("{\"cmd\":\"HELLO\",\"proto\":1}")
                    .contains("\"rsp\":\"HELLO\""));
            check("HELLO 透传 proto", "1".equals(helloProto[0]));
            check("HEARTBEAT 应答", c1.req("{\"cmd\":\"HEARTBEAT\"}")
                    .contains("\"rsp\":\"HEARTBEAT\""));

            String info = c1.req("{\"cmd\":\"INFO\"}");
            check("INFO 应答型号", info.contains("\"model\":\"ILCE-6300\""));
            check("INFO 应答嵌套对象原样透传", info.contains("\"thumb\":{\"state\":\"running\""));

            String tb = c1.req("{\"cmd\":\"THUMB_BEGIN\",\"paths\":"
                    + SJson.strArray(SCRIPT_PATHS) + "}");
            check("THUMB_BEGIN accepted=2", tb.contains("\"accepted\":2"));
            check("THUMB_BEGIN 到达 handler", begins[0] == 1);
            c1.req("{\"cmd\":\"THUMB_PAUSE\"}");
            c1.req("{\"cmd\":\"THUMB_RESUME\"}");
            c1.req("{\"cmd\":\"THUMB_CANCEL\"}");
            check("THUMB 控制 3 命令", pauses[0] == 1 && resumes[0] == 1 && cancels[0] == 1);

            check("未知命令", c1.req("{\"cmd\":\"FOO\"}").contains("unknown_cmd"));
            check("坏 JSON", c1.req("{\"cmd\": BROKEN").contains("bad_json"));

            check("EXIT_APP 应答", c1.req("{\"cmd\":\"EXIT_APP\"}")
                    .contains("\"rsp\":\"EXIT_APP\""));
            check("EXIT_APP 到达 handler", exits[0] == 1);

            Client c2 = new Client();
            check("第二个客户端 HELLO", c2.req("{\"cmd\":\"HELLO\",\"proto\":1}")
                    .contains("\"rsp\":\"HELLO\""));
            check("旧客户端被踢断开", c1.probeKicked(1000));
            c2.close();
        } finally {
            server.stop();
        }
    }

    static void prefetcherChecks() throws Exception {
        File arwSrc = new File("C:/Users/93849/Desktop/DSC01781.ARW");
        File jpgSrc = new File("C:/Users/93849/Desktop/DSC01779.JPG");
        if (!arwSrc.isFile()) {
            check("预取测试样本缺失", false);
            return;
        }
        File root = java.nio.file.Files.createTempDirectory("sc-prefetch").toFile();
        File sub = new File(root, "DCIM");
        sub.mkdirs();
        java.nio.file.Files.copy(arwSrc.toPath(), new File(sub, "a.ARW").toPath());
        java.nio.file.Files.copy(jpgSrc.toPath(), new File(sub, "b.JPG").toPath());

        ThumbPrefetcher pf = new ThumbPrefetcher(root);
        pf.begin(Arrays.asList("DCIM/a.ARW", "DCIM/b.JPG", "DCIM/ghost.ARW"));
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && !"idle".equals(pf.getState())) {
            Thread.sleep(20);
        }
        check("预取完成回 idle", "idle".equals(pf.getState()));
        check("预取计数 done=3", pf.getDone() == 3 && pf.getTotal() == 3);
        check("ARW 命中缓存", pf.lookup("DCIM/a.ARW") != null);
        check("JPG 命中缓存", pf.lookup("DCIM/b.JPG") != null);
        check("坏文件不污染缓存", pf.lookup("DCIM/ghost.ARW") == null);

        pf.begin(Arrays.asList("DCIM/a.ARW"));
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && pf.getDone() != 1) {
            Thread.sleep(20);
        }
        check("重新 begin 计数复位", pf.getDone() == 1 && pf.getTotal() == 1);
        pf.stop();
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) passed++;
        else failed++;
    }

    private static class Client {
        private final Socket socket;
        private final BufferedReader in;
        private final BufferedWriter out;

        Client() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 2122), 3000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
        }

        String req(String line) throws Exception {
            out.write(line);
            out.write("\n");
            out.flush();
            return in.readLine();
        }

        boolean alive() {
            return socket.isConnected() && !socket.isClosed();
        }

        boolean probeKicked(long timeoutMs) throws Exception {
            socket.setSoTimeout((int) timeoutMs);
            try {
                return in.read() == -1;
            } catch (java.net.SocketTimeoutException e) {
                return false;
            } catch (Exception e) {
                return true;
            }
        }

        void close() throws Exception {
            socket.close();
        }
    }
}
