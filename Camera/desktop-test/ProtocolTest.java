import com.bi2qfa.sonyconnect.PairingStore;
import com.bi2qfa.sonyconnect.PtpCameraHandler;
import com.bi2qfa.sonyconnect.PtpCodec;
import com.bi2qfa.sonyconnect.PtpIpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PTP/IP 桌面验证：线格式编解码、服务端真 socket 会话、配对全流程与端到端文件链路。
 *
 * <pre>
 * ① {@link PtpCodec} 纯字节往返 —— 握手 / 操作 / 事件 / 数据 / 探测 / 文件端口
 * ② {@link PtpIpServer} 真 socket —— Init 握手 + 配对表准入、元数据内联、缩略图队列、
 *    EXIT_APP、事件推送、文件端口 token 传输、单客户端语义、UDP 发现、有序关闭
 * ③ 配对全流程 + 端到端链路（真 PtpCameraHandler + 真文件端口）
 * </pre>
 *
 * 本测试类是**独立客户端实现**：帧按 {@code Length(4)|Type(4)|…} 自己组装，
 * 与手机端 PtpIpClient.kt 走同一套线序 —— 服务端若偏一位，这里立刻红。
 *
 * ★ 加解密层已整体移除（控制面与文件面都是），本类里不再有任何密钥/封包断言；
 *   准入判定改为"查配对表"，对应 ③ 里的配对用例。
 */
public class ProtocolTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== ① PtpCodec 线格式编解码 ===");
        codecChecks();

        System.out.println();
        System.out.println("=== ② PtpIpServer 真 socket 会话 ===");
        serverChecks();

        System.out.println();
        System.out.println("=== ③ 配对全流程 + 端到端链路（真 PtpCameraHandler）===");
        pairingChecks();

        System.out.println();
        System.out.println("=== ④ 传输进行中的控制面应答（文件页能否读目录）===");
        concurrencyChecks();

        System.out.println();
        System.out.println("=== ⑤ 多设备：占用拒绝 + 连接状态 + 配对解除推送 ===");
        multiDeviceChecks();

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ============================================================
    // ① PtpCodec
    // ============================================================

    private static void codecChecks() throws Exception {
        // ---- 包头 / 组帧 / 解帧 ----
        byte[] f = PtpCodec.frame(PtpCodec.T_OPERATION_REQ, new byte[]{1, 2, 3});
        check("frame 总长 = 8 + 载荷", f.length == 11);
        check("frame 包头 Length 小端", PtpCodec.i32(f, 0) == 11);
        check("frame 包头 Type 小端", PtpCodec.i32(f, 4) == PtpCodec.T_OPERATION_REQ);

        PtpCodec.Msg m = PtpCodec.read(new ByteArrayInputStream(f));
        check("read 往返 type/body",
                m != null && m.type == PtpCodec.T_OPERATION_REQ
                        && m.body.length == 3 && m.body[2] == 3);
        check("read 空流返回 null（对端干净关闭）",
                PtpCodec.read(new ByteArrayInputStream(new byte[0])) == null);

        byte[] badLen = new byte[8];
        PtpCodec.put32(badLen, 0, 4);
        check("read 坏长度(<8) 抛 IOException", throwsIo(badLen));

        byte[] huge = new byte[8];
        PtpCodec.put32(huge, 0, 2 * 1024 * 1024);
        check("read 超 MAX_PACKET 抛 IOException", throwsIo(huge));

        byte[] trunc = new byte[10];
        PtpCodec.put32(trunc, 0, 20);
        check("read 包体截断抛异常", throwsIo(trunc));

        // ---- 握手包 ----
        byte[] g16 = new byte[16];
        for (int i = 0; i < 16; i++) g16[i] = (byte) (0xA0 + i);

        byte[] req = PtpCodec.initCmdReq(g16, "ILCE-6300", PtpIpServer.PROTO_VERSION);
        PtpCodec.Msg rq = PtpCodec.read(new ByteArrayInputStream(req));
        check("InitCmdReq 类型", rq.type == PtpCodec.T_INIT_CMD_REQ);
        check("InitCmdReq GUID 原样",
                Arrays.equals(Arrays.copyOfRange(rq.body, 0, 16), g16));
        int[] nx = new int[1];
        check("InitCmdReq FriendlyName 往返（UTF-16LE）",
                "ILCE-6300".equals(PtpCodec.readName(rq.body, 16, nx)));
        check("InitCmdReq ProtocolVersion",
                PtpCodec.i32(rq.body, nx[0]) == PtpIpServer.PROTO_VERSION);

        byte[] ack = PtpCodec.initCmdAck(7, g16, "CAM", PtpIpServer.PROTO_VERSION);
        PtpCodec.Msg ak = PtpCodec.read(new ByteArrayInputStream(ack));
        check("InitCmdAck 类型", ak.type == PtpCodec.T_INIT_CMD_ACK);
        check("InitCmdAck ConnNo=7", PtpCodec.i32(ak.body, 0) == 7);
        int[] nx2 = new int[1];
        check("InitCmdAck FriendlyName", "CAM".equals(PtpCodec.readName(ak.body, 20, nx2)));

        check("InitEventReq 类型",
                PtpCodec.read(new ByteArrayInputStream(PtpCodec.initEventReq(3)))
                        .type == PtpCodec.T_INIT_EVENT_REQ);
        check("InitEventAck 类型",
                PtpCodec.read(new ByteArrayInputStream(PtpCodec.initEventAck()))
                        .type == PtpCodec.T_INIT_EVENT_ACK);
        PtpCodec.Msg fl = PtpCodec.read(
                new ByteArrayInputStream(PtpCodec.initFail(PtpCodec.FAIL_BUSY)));
        check("InitFail 类型与原因",
                fl.type == PtpCodec.T_INIT_FAIL
                        && PtpCodec.i32(fl.body, 0) == PtpCodec.FAIL_BUSY);

        // ---- 操作包（标准形式） ----
        byte[] or = PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PING, 42, new int[]{7, 9});
        PtpCodec.Op op = PtpCodec.parseOp(
                PtpCodec.read(new ByteArrayInputStream(or)).body);
        check("opReq 往返 code/tx/params",
                op.code == PtpCodec.OP_PING && op.txId == 42
                        && op.params.length == 2 && op.params[1] == 9);

        byte[] os = PtpCodec.opRsp(PtpCodec.DP_NONE, PtpCodec.RC_NOT_FOUND, 42,
                new int[]{(int) 0xDEADBEEFL});
        PtpCodec.Op op2 = PtpCodec.parseOp(
                PtpCodec.read(new ByteArrayInputStream(os)).body);
        check("opRsp 往返 respCode/tx/params",
                op2.code == PtpCodec.RC_NOT_FOUND && op2.txId == 42
                        && op2.params[0] == (int) 0xDEADBEEFL);

        // ---- 操作包（厂商内联 blob 形式） ----
        byte[] bl = new byte[]{0x11, 0x22, 0x33};
        byte[] orb = PtpCodec.opReqBlob(PtpCodec.OP_PAIR_BEGIN, 1, bl);
        PtpCodec.OpBlob ob = PtpCodec.parseOpBlob(
                PtpCodec.read(new ByteArrayInputStream(orb)).body);
        check("opReqBlob 往返 blob",
                ob.code == PtpCodec.OP_PAIR_BEGIN && ob.txId == 1
                        && Arrays.equals(ob.blob, bl));

        byte[] orbp = PtpCodec.opReqBlobParams(PtpCodec.OP_GET_OBJECT, 5,
                new int[]{2, 100, 0, -1}, ascii("/DCIM/100MSDCF/A.ARW"));
        byte[] orbpBody = PtpCodec.read(new ByteArrayInputStream(orbp)).body;
        PtpCodec.OpBlob obp = PtpCodec.parseOpBlob(orbpBody);
        check("opReqBlobParams params 从偏移 14 起",
                obp.params.length == 4 && obp.params[0] == 2
                        && obp.params[1] == 100 && obp.params[3] == -1);
        check("opReqBlobParams blob 对齐",
                "/DCIM/100MSDCF/A.ARW".equals(new String(obp.blob, "UTF-8")));
        PtpCodec.Op mis = PtpCodec.parseOp(orbpBody);
        check("parseOp 误用于 blob 形式必然错位（故必须整体解析）",
                mis.params.length > 0 && mis.params[0] != obp.params[0]);

        // ---- 事件 ----
        byte[] ev = PtpCodec.event(PtpCodec.EV_THUMB_PROGRESS, 0, new int[]{3, 10});
        PtpCodec.Msg em = PtpCodec.read(new ByteArrayInputStream(ev));
        PtpCodec.Ev e = PtpCodec.parseEvent(em.body);
        check("event 往返 code/tx/params",
                em.type == PtpCodec.T_EVENT && e.code == PtpCodec.EV_THUMB_PROGRESS
                        && e.params.length == 2 && e.params[1] == 10);

        // ---- 数据阶段（64 位总长是经典坑） ----
        long total = 5L * 1024 * 1024 * 1024;
        PtpCodec.Msg sd = PtpCodec.read(
                new ByteArrayInputStream(PtpCodec.startData(9, total)));
        check("startData 类型", sd.type == PtpCodec.T_START_DATA);
        check("startData 64 位总长无损", PtpCodec.totalOf(sd.body) == total);
        check("startData txId", PtpCodec.txIdOf(sd.body) == 9);

        PtpCodec.Msg dp = PtpCodec.read(
                new ByteArrayInputStream(PtpCodec.dataPacket(9, new byte[]{5, 6})));
        check("dataPacket txId + 载荷",
                dp.type == PtpCodec.T_DATA && PtpCodec.txIdOf(dp.body) == 9
                        && dp.body.length == 6);
        PtpCodec.Msg ed = PtpCodec.read(
                new ByteArrayInputStream(PtpCodec.endData(9, new byte[0])));
        check("endData 类型", ed.type == PtpCodec.T_END_DATA);
        PtpCodec.Msg cn = PtpCodec.read(new ByteArrayInputStream(PtpCodec.cancel(9)));
        check("cancel txId", cn.type == PtpCodec.T_CANCEL
                && PtpCodec.txIdOf(cn.body) == 9);

        // ---- 文件端口握手 ----
        byte[] dopen = PtpCodec.dataOpen(g16, 1234, 987654321L);
        PtpCodec.Msg dm = PtpCodec.read(new ByteArrayInputStream(dopen));
        PtpCodec.DataOpen d = PtpCodec.parseDataOpen(dm.body);
        check("dataOpen 往返 guid/connNo/token",
                dm.type == PtpCodec.T_DATA_OPEN && Arrays.equals(d.guid, g16)
                        && d.connNo == 1234 && d.token == 987654321L);
        check("dataOpenAck 类型",
                PtpCodec.read(new ByteArrayInputStream(PtpCodec.dataOpenAck()))
                        .type == PtpCodec.T_DATA_OPEN_ACK);

        // ---- UDP 发现 ----
        byte[] prq = PtpCodec.probe(true, g16, "CAM " + PtpCodec.VENDOR_TAG,
                15740, 15741, true, false);
        PtpCodec.Msg pm = PtpCodec.read(new ByteArrayInputStream(prq));
        PtpCodec.Probe pr = PtpCodec.parseProbe(pm.body);
        check("probe 类型=请求", pm.type == PtpCodec.T_PROBE_REQ);
        check("probe 扩展尾端口/标志",
                pr.vendor && pr.protoPort == 15740 && pr.filePort == 15741
                        && pr.pairingMode && !pr.paired);
        check("probe 名称带厂商标记", PtpCodec.hasVendorTag(pr.name));
        check("probe 名称解析（UTF-16LE）", pr.name.startsWith("CAM"));

        byte[] comp = PtpCodec.probeCompact(false, g16, "CAM");
        PtpCodec.Msg cm = PtpCodec.read(new ByteArrayInputStream(comp));
        PtpCodec.Probe cp = PtpCodec.parseProbe(cm.body);
        check("紧凑探测无厂商扩展（vendor=false，端口=0）",
                cm.type == PtpCodec.T_PROBE_RESP && !cp.vendor
                        && cp.protoPort == 0 && cp.filePort == 0);
        check("hasVendorTag 负例", !PtpCodec.hasVendorTag("CAM"));

        // ---- 小工具 ----
        byte[] raw = new byte[]{0x00, 0x1F, (byte) 0x80, (byte) 0xFF};
        check("hex/unhex 往返",
                Arrays.equals(PtpCodec.unhex(PtpCodec.hex(raw)), raw));
        check("eq 相等", PtpCodec.eq(g16, g16.clone()));
        check("eq 不等长", !PtpCodec.eq(g16, new byte[15]));
        check("eqConst 常量时间比较",
                PtpCodec.eqConst(raw, raw.clone())
                        && !PtpCodec.eqConst(raw, new byte[]{1, 2, 3, 4}));
    }

    private static boolean throwsIo(byte[] raw) {
        try {
            PtpCodec.read(new ByteArrayInputStream(raw));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    // ============================================================

    // ③ PtpIpServer —— 真 socket 会话
    // ============================================================

    private static void serverChecks() throws Exception {
        byte[] guid16 = new byte[16];
        for (int i = 0; i < 16; i++) {
            guid16[i] = (byte) (0xA0 + i);
        }
        Stub stub = new Stub(guid16);
        PtpIpServer server = new PtpIpServer(stub);
        server.start();
        int protoPort = server.getProtoPort();
        int filePort = server.getFilePort();

        check("服务端启动，协议端口 > 0", protoPort > 0);
        check("文件端口 = 协议端口 + 1", filePort == protoPort + 1);
        check("isRunning() 为真", server.isRunning());

        // ---- UDP 发现 ----
        PtpCodec.Probe ext = udpProbe(protoPort, guid16,
                "Phone " + PtpCodec.VENDOR_TAG, 1000);
        check("UDP 扩展应答可达", ext != null);
        check("扩展应答回报真实协议端口", ext != null && ext.protoPort == protoPort);
        check("扩展应答回报真实文件端口", ext != null && ext.filePort == filePort);
        check("扩展应答回报配对模式", ext != null && ext.pairingMode);
        check("扩展应答带厂商标记", ext != null && PtpCodec.hasVendorTag(ext.name));

        PtpCodec.Probe compact = udpProbe(protoPort, guid16, "ThirdParty", 1000);
        check("第三方探测回紧凑应答（无扩展尾）", compact != null && !compact.vendor);
        check("紧凑应答仍含友好名",
                compact != null && compact.name != null && compact.name.length() > 0);

        // ---- 控制连接：Init Cmd + 双向认证 ----
        Client c = new Client();
        c.connect("127.0.0.1", protoPort, guid16);
        check("Init Cmd 握手成功（connNo > 0）", c.connNo > 0);
        check("Init 握手通过（已配对设备直接进控制循环）", c.connNo > 0);
        check("握手应答里的设备名与 Handler 一致",
                stub.deviceName().equals(c.peerName));

        // ---- PING：唯一实时包（电量 + 有无镜头进参数，镜头名进 blob）----
        Client.Reply ping = c.req(PtpCodec.OP_PING, null);
        check("PING 响应码 RC_OK", ping.code == PtpCodec.RC_OK);
        check("PING 回报电量 77",
                ping.params != null && ping.params.length == 2 && ping.params[0] == 77);
        check("PING 回报已装镜头", ping.params != null && ping.params[1] == 1);
        check("PING 走内联 blob 带回镜头名", contains(ping.blob, "E PZ 16-50mm"));

        // ---- DEVICE_INFO：静态快照（只含不会变的字段，一次往返就够）----
        Client.Reply info = c.req(PtpCodec.OP_DEVICE_INFO, null);
        check("DEVICE_INFO 走内联 blob", info.blob != null && info.blob.length > 0);
        check("DEVICE_INFO 含机型与序列号",
                contains(info.blob, "ILCE-6300") && contains(info.blob, "6512345"));

        // ---- LIST_DIR ----
        Client.Reply ls = c.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/DCIM"));
        check("LIST_DIR 返回目录 JSON",
                ls.code == PtpCodec.RC_OK && contains(ls.blob, "DSC00001.ARW"));

        // ---- 路径逃逸拦截 ----
        Client.Reply esc = c.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/mnt/../etc"));
        check("路径逃逸 → ACCESS_DENIED", esc.code == PtpCodec.RC_ACCESS_DENIED);
        Client.Reply esc2 = c.reqBlob(PtpCodec.OP_GET_OBJECT,
                new int[]{PtpCodec.KIND_THUMB, 0, 0, -1}, ascii("DCIM/rel.ARW"));
        check("相对路径 → ACCESS_DENIED", esc2.code == PtpCodec.RC_ACCESS_DENIED);

        // ---- STAT：64 位 size/mtime ----
        Client.Reply stt = c.reqBlob(PtpCodec.OP_STAT, null, ascii("/DCIM/DSC00001.ARW"));
        check("STAT size 64 位往返", u64(stt.params, 0) == stub.thumb.length);
        check("STAT mtime 64 位往返", u64(stt.params, 2) == 1700000000000L);
        Client.Reply miss = c.reqBlob(PtpCodec.OP_STAT, null, ascii("/DCIM/NOPE.ARW"));
        check("STAT 不存在的对象 → NOT_FOUND", miss.code == PtpCodec.RC_NOT_FOUND);

        // ---- 缩略图队列控制 ----
        Client.Reply tq = c.reqBlob(PtpCodec.OP_THUMB_QUEUE_BEGIN, null,
                ascii("/DCIM/DSC00001.ARW\n/DCIM/DSC00002.ARW"));
        check("缩略图队列 BEGIN 受理", tq.code == PtpCodec.RC_OK);
        check("Handler 收到 BEGIN 与两条路径",
                stub.lastThumbOp == PtpCodec.OP_THUMB_QUEUE_BEGIN
                        && stub.lastThumbPaths != null && stub.lastThumbPaths.length == 2);
        Client.Reply tp = c.req(PtpCodec.OP_THUMB_QUEUE_PAUSE, null);
        check("缩略图队列 PAUSE 受理", tp.code == PtpCodec.RC_OK
                && stub.lastThumbOp == PtpCodec.OP_THUMB_QUEUE_PAUSE);

        // ---- 配对操作在 P1 明确不支持 ----
        Client.Reply pair = c.req(PtpCodec.OP_PAIR_BEGIN, null);
        check("PAIR_BEGIN 在 P1 回 NOT_SUPPORTED",
                pair.code == PtpCodec.RC_NOT_SUPPORTED);

        // ---- 未知操作码 ----
        Client.Reply un = c.req(0x7FF0, null);
        check("未知操作码 → NOT_SUPPORTED", un.code == PtpCodec.RC_NOT_SUPPORTED);

        serverTail(server, c, stub, guid16, protoPort, filePort);
    }

    private static long u64(int[] p, int at) {
        if (p == null || p.length < at + 2) {
            return -1;
        }
        return ((long) p[at] & 0xFFFFFFFFL) | (((long) p[at + 1]) << 32);
    }

    /** 发一次 UDP 探测，返回应答（超时/异常 → null）。 */
    private static PtpCodec.Probe udpProbe(int port, byte[] guid, String name, int timeoutMs) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(timeoutMs);
            byte[] req = PtpCodec.probe(true, guid, name, 0, 0, false, false);
            s.send(new DatagramPacket(req, req.length,
                    InetAddress.getByName("127.0.0.1"), port));
            byte[] buf = new byte[1024];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            byte[] raw = new byte[in.getLength()];
            System.arraycopy(in.getData(), in.getOffset(), raw, 0, in.getLength());
            PtpCodec.Msg m = PtpCodec.read(new ByteArrayInputStream(raw));
            if (m == null || m.type != PtpCodec.T_PROBE_RESP) {
                return null;
            }
            return PtpCodec.parseProbe(m.body);
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) {
                s.close();
            }
        }
    }

    /** 字节数组里是否含指定 ASCII 子串。 */
    private static boolean contains(byte[] hay, String needle) {
        if (hay == null) {
            return false;
        }
        byte[] n = ascii(needle);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** ③ 续：事件推送、文件端口 token 传输、单客户端语义、有序关闭。 */
    private static void serverTail(PtpIpServer server, Client c, Stub stub,
                                   byte[] guid16,
                                   int protoPort, int filePort) throws Exception {
        // ---- GET_OBJECT → 一次性 token + 实得文件端口 ----
        Client.Reply go = c.reqBlob(PtpCodec.OP_GET_OBJECT,
                new int[]{PtpCodec.KIND_THUMB, 0, 0, -1}, ascii("/DCIM/DSC00001.ARW"));
        check("GET_OBJECT 回 RC_OK", go.code == PtpCodec.RC_OK);
        check("GET_OBJECT 回 token + 实得文件端口",
                go.params != null && go.params.length == 2 && go.params[1] == filePort);
        long token = go.params[0] & 0xFFFFFFFFL;

        // ---- 事件连接：挂载后接收服务端主动推送 ----
        Client ev = new Client();
        ev.connectEvent("127.0.0.1", protoPort, c.connNo);
        check("事件连接挂载成功（服务端认得 controlConnNo 并回了 Ack）",
                ev.sock != null && ev.out != null && ev.connNo == c.connNo);
        server.pushEvent(PtpCodec.EV_THUMB_PROGRESS, 7, new int[]{3, 10});
        PtpCodec.Ev e1 = ev.readEvent();
        check("事件连接收到推送事件码",
                e1 != null && e1.code == PtpCodec.EV_THUMB_PROGRESS);
        check("事件事务号与参数正确",
                e1 != null && e1.txId == 7 && e1.params != null
                        && e1.params.length == 2 && e1.params[0] == 3 && e1.params[1] == 10);

        // ---- 文件端口：token 一次性传输 ----
        byte[] obj = c.fetchFile(filePort, token);
        check("文件端口取回内容与源逐字节一致",
                obj != null && Arrays.equals(obj, stub.thumb));
        byte[] again = c.fetchFile(filePort, token);
        check("token 一次性（二次使用被拒）", again == null);

        // ---- 断点续传：offset + length ----
        Client.Reply go2 = c.reqBlob(PtpCodec.OP_GET_OBJECT,
                new int[]{PtpCodec.KIND_THUMB, 5, 0, 4}, ascii("/DCIM/DSC00001.ARW"));
        long token2 = go2.params[0] & 0xFFFFFFFFL;
        byte[] part = c.fetchFile(filePort, token2);
        boolean sliceOk = part != null && part.length == 4;
        if (sliceOk) {
            for (int i = 0; i < 4; i++) {
                if (part[i] != stub.thumb[5 + i]) {
                    sliceOk = false;
                }
            }
        }
        check("断点续传 offset/length 生效", sliceOk);

        // ---- 已删除的 EXIT_APP：号段不再受理（"传输完成自动关相机端"功能已两端移除）----
        Client.Reply ex = c.req(0x9030, null);
        check("已删除的 EXIT_APP 操作码返回 RC_NOT_SUPPORTED",
                ex.code == PtpCodec.RC_NOT_SUPPORTED);

        // ---- 单客户端语义：同 deviceId 踢旧 ----
        Client c2 = new Client();
        c2.connect("127.0.0.1", protoPort, guid16);
        check("同 deviceId 重连成功（旧会话被踢）", c2.connNo > 0);
        check("旧控制连接已被服务端关闭", c.isClosedByPeer());
        Client.Reply ping2 = c2.req(PtpCodec.OP_PING, null);
        check("新会话可正常操作", ping2.code == PtpCodec.RC_OK);

        // ---- 不同 deviceId → Init Fail(BUSY)，绝不打断在跑会话 ----
        byte[] other = new byte[16];
        for (int i = 0; i < 16; i++) {
            other[i] = (byte) (0x11 + i);
        }
        Client c3 = new Client();
        boolean rejected = false;
        try {
            c3.connect("127.0.0.1", protoPort, other);
        } catch (IOException ie) {
            rejected = true;
        }
        check("不同 deviceId 被拒（Init Fail BUSY）", rejected);
        Client.Reply ping3 = c2.req(PtpCodec.OP_PING, null);
        check("拒绝后原会话不受影响", ping3.code == PtpCodec.RC_OK);

        // ---- 有序关闭 ----
        ev.close();
        server.stop();
        check("stop 后 isRunning()=false", !server.isRunning());
    }

    // ============================================================
    // ④ 传输进行中，控制面必须照常应答
    //
    // 来自用户实测反馈："传输文件时点击文件页面无法加载文件"。手机端那半边的
    // 修法是把**控制面**（目录/心跳/元数据）与**数据面**（整对象搬运）分成两条
    // 线程（`ObjectRepository` 的 ioExecutor / xferExecutor）。
    //
    // 这里守的是相机端这半边的前提：一条文件连接正在流式传大对象时，同一条控制
    // 连接上的 LIST_DIR / PING / STAT 必须仍在毫秒级得到应答。相机端要是做不到，
    // 手机端怎么分线程都没用 —— 目录请求会在 8s 的请求超时上撞死。
    //
    // 靠的是"每连接一线程"：控制连接与文件连接各跑各的线程，两者不共用锁
    // （send() 的监视器是每个 Conn 自己的，令牌表 synchronized(pending) 只覆盖登记）。
    // ============================================================

    private static void concurrencyChecks() throws Exception {
        byte[] guid16 = new byte[16];
        for (int i = 0; i < 16; i++) {
            guid16[i] = (byte) (0xC0 + i);
        }
        Stub stub = new Stub(guid16);
        PtpIpServer server = new PtpIpServer(stub);
        server.start();
        int protoPort = server.getProtoPort();
        int filePort = server.getFilePort();

        Client c = new Client();
        final Client fc = c;
        try {
            c.connect("127.0.0.1", protoPort, guid16);
            check("并发段：控制连接建立", c.connNo > 0);

            Client.Reply go = c.reqBlob(PtpCodec.OP_GET_OBJECT,
                    new int[]{PtpCodec.KIND_ORIGINAL, 0, 0, -1}, ascii(Stub.SLOW_PATH));
            check("慢对象 GET_OBJECT 受理（RC_OK）", go.code == PtpCodec.RC_OK);
            long token = go.params[0] & 0xFFFFFFFFL;

            // 后台线程开始搬这个 8 MB 的对象（独立文件端口 socket）
            final int fport = filePort;
            final byte[][] got = new byte[1][];
            Thread xfer = new Thread(new Runnable() {
                public void run() {
                    got[0] = fc.fetchFile(fport, token);
                }
            }, "test-slow-xfer");
            xfer.setDaemon(true);
            xfer.start();

            // 等传输真的"在跑"（传输窗口 ≈ 2.5s，这里只等 400ms）
            Thread.sleep(400);
            check("并发段：传输窗口有效（对象仍在传输中）", xfer.isAlive());

            long t0 = System.currentTimeMillis();
            Client.Reply ls = c.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/DCIM"));
            long listMs = System.currentTimeMillis() - t0;
            check("传输期间 LIST_DIR 仍受理（文件页能读到目录）",
                    ls.code == PtpCodec.RC_OK && ls.blob != null && ls.blob.length > 0);
            check("传输期间 LIST_DIR 未被对象传输挡住（实测 " + listMs + "ms < 1000ms）",
                    listMs < 1000);

            t0 = System.currentTimeMillis();
            Client.Reply pg = c.req(PtpCodec.OP_PING, null);
            long pingMs = System.currentTimeMillis() - t0;
            check("传输期间心跳仍受理（不会误判失联）",
                    pg.code == PtpCodec.RC_OK && pingMs < 1000);

            t0 = System.currentTimeMillis();
            Client.Reply st = c.reqBlob(PtpCodec.OP_STAT, null, ascii(Stub.SLOW_PATH));
            long statMs = System.currentTimeMillis() - t0;
            check("传输期间 STAT 仍受理（续传前校验可用）",
                    st.code == PtpCodec.RC_OK && statMs < 1000);

            xfer.join(60000);
            check("中途插入的控制请求没有打断对象传输",
                    got[0] != null && got[0].length == stub.slow.length);
            check("并发传输回来的字节与源逐字节一致",
                    got[0] != null && Arrays.equals(got[0], stub.slow));
        } finally {
            server.stop();
            c.close();
        }
    }

    // ============================================================
    // ⑤ 多设备：占用拒绝 + 连接状态 + 配对解除推送
    //
    // 需求原话（多设备适配）：
    //   "应该做好被占用的提示 配对状态正在进行配对过程时或者相机端已经被连接时
    //    如果有其他设备发送连接或配对请求应拒绝 并返回设备被占用"
    //   "已连接时一方解除配对 → 通过协议通知对方"
    //   "相机端应该在主屏幕写上手机端已连接或手机端未连接"
    //
    // 这三条都落在相机端：拒绝码、状态查询、事件推送。手机端的反应（清本地记录、
    // 断开、上屏提示）在 Kotlin 侧，桌面这道门覆盖不到，但它依赖的线上契约在这里钉死。
    // ============================================================

    private static void multiDeviceChecks() throws Exception {
        byte[] guidA = new byte[16];
        for (int i = 0; i < 16; i++) {
            guidA[i] = (byte) (0xE0 + i);
        }
        byte[] guidB = new byte[16];
        for (int i = 0; i < 16; i++) {
            guidB[i] = (byte) (0x31 + i);
        }
        byte[] guidC = new byte[16];
        for (int i = 0; i < 16; i++) {
            guidC[i] = (byte) (0x71 + i);
        }
        String idA = PtpCodec.hex(Arrays.copyOf(guidA, 8));

        Stub stub = new Stub(guidA);
        StubPairing ph = new StubPairing();
        PtpIpServer server = new PtpIpServer(stub);
        server.setPairingHandler(ph);
        server.start();
        int protoPort = server.getProtoPort();

        Client a = new Client();
        Client ev = null;
        try {
            check("多设备段：空闲时无已连接客户端", server.connectedClientCount() == 0);
            check("多设备段：空闲时连接状态为未连接", server.connectedClientName() == null);

            // ---- ① 第一台进入配对流程（停在等 PAIR_EXCHANGE 的位置 = 占用中）----
            a.connectInitOnly("127.0.0.1", protoPort, guidA, "Phone-A");
            PtpCodec.OpBlob b1 = a.plainReqBlob(PtpCodec.OP_PAIR_BEGIN,
                    0x41, ascii("Phone-A"));
            check("第一台 PAIR_BEGIN 受理（RC_OK）", b1.code == PtpCodec.RC_OK);
            check("相机进入配对中（占用标志为真）", server.isPairingInProgress());

            // ---- ② 第二台在此刻发起配对 → 明确回"设备被占用" ----
            check("配对进行中第二台被拒：Init Fail(BUSY)",
                    Client.initFailReason("127.0.0.1", protoPort, guidB) == PtpCodec.FAIL_BUSY);

            // ---- ③ 第一台配对成功 → 同连接进控制循环 ----
            PtpCodec.OpBlob b2 = a.plainReqBlob(PtpCodec.OP_PAIR_EXCHANGE,
                    0x42, ascii(ph.code));
            check("第一台配对成功（PAIR_EXCHANGE 回 RC_OK）", b2.code == PtpCodec.RC_OK);
            check("配对结束后占用标志释放", !server.isPairingInProgress());

            check("已连接：connectedClientCount = 1", server.connectedClientCount() == 1);
            check("已连接：connectedClientName 是手机设备名",
                    "Phone-A".equals(server.connectedClientName()));
            check("已连接：connectedClientIdHex 是手机设备码",
                    idA.equalsIgnoreCase(server.connectedClientIdHex()));

            // ---- ④ 相机已被连接时，第三台的连接/配对请求 → 设备被占用 ----
            check("已连接时其他设备的请求被拒：Init Fail(BUSY)",
                    Client.initFailReason("127.0.0.1", protoPort, guidC) == PtpCodec.FAIL_BUSY);
            check("被拒的第三台没有影响已连接会话",
                    a.req(PtpCodec.OP_PING, null).code == PtpCodec.RC_OK);

            // ---- ⑤ 解除配对推送（已连接时"通知对方"的送达路径）----
            ev = new Client();
            ev.connectEvent("127.0.0.1", protoPort, a.connNo);
            server.pushPairRemoved();
            PtpCodec.Ev e = ev.readEvent();
            check("解除配对推送送达事件连接（EV_PAIRED_REMOVED）",
                    e != null && e.code == PtpCodec.EV_PAIRED_REMOVED);

            // ---- ⑥ 手机端主动解除配对（已认证会话内的 OP_PAIR_REMOVE）----
            check("手机端 OP_PAIR_REMOVE 受理",
                    a.req(PtpCodec.OP_PAIR_REMOVE, null).code == PtpCodec.RC_OK);
            check("OP_PAIR_REMOVE 后相机侧记录已删",
                    !ph.isDevicePaired(Arrays.copyOf(guidA, 8)));

            // ---- ⑦ 相机端退出：拆连接**之前**先推"相机端已退出" ----
            //   ★ 六条退出路径全部收口在 stop()，所以通知写在 stop() 里才不会漏；
            //   写在 MainActivity 的某一条路径上，另外五条就会静默失联（手机端要等
            //   3 次心跳 ≈ 9 秒才发现，界面期间一直显示"已连接"）。
            server.pushAppExiting();
            PtpCodec.Ev ex = ev.readEvent();
            check("相机端退出通知送达（EV_APP_EXITING）",
                    ex != null && ex.code == PtpCodec.EV_APP_EXITING);

            // ---- ⑦' 切换连接方式：先推"正在切换"，并抑制随后的"已退出" ----
            //   ★ 切换方式同样会断（无线电要重配），但原因不是"相机退出软件" ——
            //   只发 EV_APP_EXITING 的话手机端会显示"相机端已退出"这句**假话**，
            //   用户会跑去相机上看它是不是真关了。所以切换前先发带参数的
            //   EV_MODE_SWITCHING，之后 stop() 那条退出通知要被抑制掉。
            server.pushModeSwitching(PtpCodec.MODE_CODE_HOTSPOT);
            PtpCodec.Ev sev = ev.readEvent();
            check("切换连接方式通知送达（EV_MODE_SWITCHING，参数=相机热点）",
                    sev != null && sev.code == PtpCodec.EV_MODE_SWITCHING
                            && sev.params != null && sev.params.length == 1
                            && sev.params[0] == PtpCodec.MODE_CODE_HOTSPOT);
            // 断言落在"stop() 之后这里读到的是 EOF（null）而不是事件"上：
            //   读到事件 = 抑制失效，"相机端已退出"会把刚说的那句话覆盖掉；
            //   读不到也不会等超时 —— 连接已被 stop() 关掉，recv() 立刻 EOF。
            server.stop();
            check("切换方式后 stop() 不再补发「相机端已退出」（该说的已经说过了）",
                    ev.readEvent() == null);
            check("stop() 之后协议端口归零", server.getProtoPort() == 0);
        } finally {
            if (ev != null) {
                ev.close();
            }
            server.stop();
            a.close();
        }
    }

    /** 配对处理器桩：窗口常开、码固定、按设备码记账（只为验证"占用"与推送）。 */
    static final class StubPairing implements PtpIpServer.PairingHandler {
        final String code = "123456";
        final java.util.HashSet<String> paired = new java.util.HashSet<String>();
        /** 设备码 hex → 表里记着的名字（改名断言用）。 */
        final java.util.HashMap<String, String> names = new java.util.HashMap<String, String>();

        public boolean isDevicePaired(byte[] g) {
            return paired.contains(PtpCodec.hex(g));
        }

        public boolean isPairingOpen() {
            return true;
        }

        public String pairingCode() {
            return code;
        }

        public void onPaired(byte[] g, String peerDeviceName) {
            paired.add(PtpCodec.hex(g));
            names.put(PtpCodec.hex(g), peerDeviceName);
        }

        /** 已配对设备连上来时对名字：记下这次带来的名字（真实现里就是改名）。 */
        public void onPairedDeviceSeen(byte[] g, String peerDeviceName) {
            if (!paired.contains(PtpCodec.hex(g))) {
                return;
            }
            names.put(PtpCodec.hex(g), peerDeviceName);
        }

        public void onPairingFailed() {
        }

        public void onPairingAborted() {
        }

        public void onUnpair(byte[] g) {
            paired.remove(PtpCodec.hex(g));
        }
    }

    // ============================================================
    // 测试用 Handler 桩：全部数据在内存，不做任何磁盘访问
    // ============================================================

    // ============================================================
    // ④ 配对全流程 + 端到端链路
    //
    // 与 ③ 不同，这一段用的是**真实的** PtpCameraHandler + PairingStore，
    // 只有 Platform（电量/型号/镜头…）是假的。所以它一次验证两件事：
    //   ① 配对协议 —— BEGIN → EXCHANGE → COMMIT → 同连接认证 → 免配对重连 → 解除配对
    //   ② 业务接线 —— LIST_DIR / STAT / GET_OBJECT 经真实文件端口取回字节
    // 这条链路是"以后任何协议改动把端到端打断就立刻变红"的那道网。
    // ============================================================

    /** 平台能力桩：相机侧唯一的"假"数据来源（真机上由 MainActivity 用 Context 实现）。 */
    static final class StubPlatform implements PtpCameraHandler.Platform {
        public int batteryPct() {
            return 77;
        }

        public String model() {
            return "ILCE-6300";
        }

        public String serial() {
            return "6512345";
        }

        public String firmware() {
            return "3.10";
        }

        public String lens() {
            return "E PZ 16-50mm F3.5-5.6 OSS";
        }

        public String mode() {
            return "hotspot";
        }

        public String ssid() {
            return "DIRECT-test:ILCE-6300";
        }

        // ===== 固定信息（真值取自 A6300 实机：地区 CHINA、平台 2.3、安卓 4.1.2/16） =====

        public String region() {
            return "CHINA";
        }

        public String apiVersion() {
            return "2.3";
        }

        public String androidVersion() {
            return "4.1.2";
        }

        public int androidSdk() {
            return 16;
        }

        public long sdTotalBytes() {
            return 31914983424L;   // 29.7 GiB
        }

        public long sdUsedBytes() {
            return 12884901888L;   // 12 GiB
        }
    }

    private static void pairingChecks() {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "sonyconnect-e2e-" + System.nanoTime());
        File dir = new File(base, "data");     // PairingStore 的落盘目录
        File root = new File(base, "root");    // 相机"SD 卡"根
        PtpIpServer server = null;
        try {
            new File(root, "DCIM").mkdirs();
            byte[] payload = new byte[4096];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 13 + 7);
            }
            writeFile(new File(root, "DCIM" + File.separator + "DSC00001.BIN"), payload);

            PairingStore ps = new PairingStore(dir);
            PtpCameraHandler handler = new PtpCameraHandler(root, null, ps,
                    new StubPlatform());
            server = new PtpIpServer(handler);
            server.setPairingHandler(handler);
            server.start();
            final int port = server.getProtoPort();
            final int fport = server.getFilePort();
            check("服务器已绑定协议端口与文件端口", port > 0 && fport > 0);

            byte[] phoneId = new byte[8];
            for (int i = 0; i < 8; i++) {
                phoneId[i] = (byte) (0x51 + i);
            }
            byte[] phoneGuid16 = new byte[16];
            System.arraycopy(phoneId, 0, phoneGuid16, 0, 8);
            byte[] otherGuid16 = new byte[16];
            for (int i = 0; i < 8; i++) {
                otherGuid16[i] = (byte) (0x61 + i);
            }

            // ---- 窗口未开：未配对设备必须被拒 ----
            check("配对窗口未开时未配对设备被拒 Init Fail(NOT_PAIRED)",
                    Client.initFailReason("127.0.0.1", port, phoneGuid16)
                            == PtpCodec.FAIL_NOT_PAIRED);

            // ---- 开窗 ----
            String code = ps.openWindow(System.currentTimeMillis());
            check("配对窗口已开启且码为 6 位", code != null && code.matches("[0-9]{6}"));
            check("窗口开启时探测位 pairingMode 为真", handler.isPairingMode());
            check("窗口开启时探测位 paired 仍为假（还没配对成功）",
                    !handler.hasPairedInitiator());

            // ---- ① PAIR_BEGIN ----
            Client c = new Client();
            c.connectInitOnly("127.0.0.1", port, phoneGuid16, "SonyConnect-Test");
            check("窗口开启时未配对设备进入配对阶段（拿到 connNo）", c.connNo > 0);

            PtpCodec.OpBlob b1 = c.plainReqBlob(PtpCodec.OP_PAIR_BEGIN, 0x41,
                    ascii("Xiaomi 15"));
            check("PAIR_BEGIN 回 RC_OK", b1.code == PtpCodec.RC_OK);
            check("PAIR_BEGIN 应答 = devIdC(8)",
                    b1.blob != null && b1.blob.length == 8);
            byte[] devIdC = new byte[8];
            System.arraycopy(b1.blob, 0, devIdC, 0, 8);
            check("应答里的 devIdC 与相机设备码一致", PtpCodec.eq(devIdC, ps.deviceId()));

            // ---- ② 错误配对码必须被拒（明文比对：验证的就是那 6 位数字本身）----
            String wrong = code.equals("000000") ? "111111" : "000000";
            PtpCodec.OpBlob bad = c.plainReqBlob(PtpCodec.OP_PAIR_EXCHANGE, 0x42,
                    ascii(wrong));
            check("错误配对码 → RC_PAIRING_FAILED", bad.code == PtpCodec.RC_PAIRING_FAILED);
            check("失败后窗口侧计次为 1", ps.failures() == 1);
            check("失败后仍未落表", !ps.contains(phoneId));
            c.close();

            // ---- 重来一次，这次用对码 ----
            Client c2 = new Client();
            c2.connectInitOnly("127.0.0.1", port, phoneGuid16, "SonyConnect-Test");
            PtpCodec.OpBlob b1b = c2.plainReqBlob(PtpCodec.OP_PAIR_BEGIN, 0x41,
                    ascii("Xiaomi 15"));
            check("重试 PAIR_BEGIN 仍受理", b1b.code == PtpCodec.RC_OK);
            PtpCodec.OpBlob ok2 = c2.plainReqBlob(PtpCodec.OP_PAIR_EXCHANGE, 0x42,
                    ascii(code));
            check("正确配对码 → RC_OK", ok2.code == PtpCodec.RC_OK);

            // ---- 落表 / 关窗 / 记录手机设备名 ----
            check("配对已落表", ps.contains(phoneId));
            check("配对表记录了手机设备名（相机端要显示这个）",
                    "Xiaomi 15".equals(ps.find(phoneId).peerName));
            check("配对后窗口自动关闭", !ps.isOpen(System.currentTimeMillis()));
            check("配对后探测位 paired 变为真", handler.hasPairedInitiator());

            // ---- 同一条连接直接进控制循环（不许要求手机重连）----
            Client.Reply ping0 = c2.req(PtpCodec.OP_PING, null);
            check("配对后同一条连接立即可用（无需重连）", ping0.code == PtpCodec.RC_OK);

            // ---- PING：唯一实时包 ----
            Client.Reply ping = c2.req(PtpCodec.OP_PING, null);
            check("PING 电量 77", ping.code == PtpCodec.RC_OK
                    && ping.params != null && ping.params[0] == 77);
            check("PING 镜头位为 1", ping.params != null && ping.params[1] == 1);
            check("PING 与电量同包带回镜头名", contains(ping.blob, "E PZ 16-50mm"));

            // ---- DEVICE_INFO：静态快照 ----
            Client.Reply info = c2.req(PtpCodec.OP_DEVICE_INFO, null);
            check("DEVICE_INFO 含机型/序列号/SSID",
                    contains(info.blob, "ILCE-6300")
                            && contains(info.blob, "6512345")
                            && contains(info.blob, "DIRECT-test"));
            check("DEVICE_INFO 不再重复下发实时字段（电量/镜头）",
                    !contains(info.blob, "batteryPct")
                            && !contains(info.blob, "batteryRemainMin")
                            && !contains(info.blob, "lens"));
            // 固定信息（地区 / Java API 版本 / 安卓版本 / SD 容量）必须随快照下发：
            // 这几项手机端的相机详情页与存储卡卡片全靠它，掉了会静默显示"—"
            check("DEVICE_INFO 含地区与 Java API 版本",
                    contains(info.blob, "\"region\":\"CHINA\"")
                            && contains(info.blob, "\"apiVersion\":\"2.3\""));
            check("DEVICE_INFO 含安卓版本与 SDK",
                    contains(info.blob, "\"androidVersion\":\"4.1.2\"")
                            && contains(info.blob, "\"androidSdk\":16"));
            check("DEVICE_INFO 含 SD 总容量与已用（数值型，非字符串）",
                    contains(info.blob, "\"sdTotal\":31914983424")
                            && contains(info.blob, "\"sdUsed\":12884901888")
                            // ★ 数值型契约：手机端 JSONObject.optLong 读字符串会失败回落 -1，
                            //   加了引号就是"看起来有、实际显示不出来"的暗坑
                            && !contains(info.blob, "\"sdTotal\":\""));

            // ---- LIST_DIR ----
            Client.Reply lsRoot = c2.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/"));
            check("LIST_DIR / 看到 DCIM", lsRoot.code == PtpCodec.RC_OK
                    && contains(lsRoot.blob, "DCIM"));
            Client.Reply lsDcim = c2.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/DCIM"));
            check("LIST_DIR /DCIM 看到文件且 dir=false",
                    contains(lsDcim.blob, "DSC00001.BIN")
                            && contains(lsDcim.blob, "\"dir\":false"));
            check("LIST_DIR 条目带 size", contains(lsDcim.blob, "\"size\":4096"));
            check("LIST_DIR 条目带 mtime", contains(lsDcim.blob, "\"mtime\":"));
            Client.Reply lsMiss = c2.reqBlob(PtpCodec.OP_LIST_DIR, null, ascii("/NOPE"));
            check("LIST_DIR 不存在的目录 → NOT_FOUND",
                    lsMiss.code == PtpCodec.RC_NOT_FOUND);

            // ---- 路径逃逸：真 handler 的第二道防线 ----
            Client.Reply esc = c2.reqBlob(PtpCodec.OP_STAT, null,
                    ascii("/DCIM/../../etc/passwd"));
            check("真 handler 下路径逃逸仍被拒", esc.code == PtpCodec.RC_ACCESS_DENIED);

            // ---- STAT：下载前取原始文件长度 ----
            Client.Reply st = c2.reqBlob(PtpCodec.OP_STAT, null,
                    ascii("/DCIM/DSC00001.BIN"));
            check("STAT 返回 4096 字节",
                    st.code == PtpCodec.RC_OK && u64(st.params, 0) == 4096L);

            // ---- GET_OBJECT：真文件端口取字节（端到端）----
            Client.Reply go = c2.reqBlob(PtpCodec.OP_GET_OBJECT,
                    new int[]{PtpCodec.KIND_ORIGINAL, 0, 0, -1},
                    ascii("/DCIM/DSC00001.BIN"));
            check("GET_OBJECT 受理并回令牌 + 文件端口", go.code == PtpCodec.RC_OK
                    && go.params != null && go.params.length == 2);
            // ★ 线上令牌是**单个 32 位参数**（params[0]），端口在 params[1]。
            //   别按 64 位去拼 —— 那会把端口当成高位，得到"令牌无效"。
            //   手机端 PtpIpClient.getObject 也是这么读的（params[0] and 0xFFFFFFFF）。
            long token = go.params[0] & 0xFFFFFFFFL;
            int realFilePort = go.params[1];
            byte[] got = c2.fetchFile(realFilePort, token);
            check("端到端：经文件端口取回原文件逐字节一致",
                    got != null && Arrays.equals(got, payload));
            check("服务端口令一次性（二次使用取不到）",
                    c2.fetchFile(realFilePort, token) == null);

            // ---- 断点续传切片 ----
            Client.Reply go2 = c2.reqBlob(PtpCodec.OP_GET_OBJECT,
                    new int[]{PtpCodec.KIND_ORIGINAL, 100, 0, 200},
                    ascii("/DCIM/DSC00001.BIN"));
            check("GET_OBJECT 带 offset/length 受理", go2.code == PtpCodec.RC_OK);
            byte[] slice = c2.fetchFile(go2.params[1], go2.params[0] & 0xFFFFFFFFL);
            boolean sliceOk = slice != null && slice.length == 200;
            if (sliceOk) {
                for (int i = 0; i < 200; i++) {
                    if (slice[i] != payload[100 + i]) {
                        sliceOk = false;
                        break;
                    }
                }
            }
            check("续传切片内容 = payload[100..300)", sliceOk);

            // 缩略图档位对 .BIN 不支持 → NOT_FOUND（不是崩溃）
            Client.Reply gothumb = c2.reqBlob(PtpCodec.OP_GET_OBJECT,
                    new int[]{PtpCodec.KIND_THUMB, 0, 0, -1},
                    ascii("/DCIM/DSC00001.BIN"));
            check("不支持的类型取缩略图 → NOT_FOUND",
                    gothumb.code == PtpCodec.RC_NOT_FOUND);

            // ---- 已配对设备重连：窗口关着也放行，且直接进认证 ----
            check("已配对设备被 allowInitiator 放行", handler.allowInitiator(phoneId));
            Client c3 = new Client();
            c3.connectInitOnly("127.0.0.1", port, phoneGuid16, "SonyConnect-Test");
            check("已配对设备在窗口关闭时也能连（不再需要配对）", c3.connNo > 0);
            Client.Reply ping3 = c3.req(PtpCodec.OP_PING, null);
            check("已配对设备重连后立即可用", ping3.code == PtpCodec.RC_OK);
            check("已配对设备重连时把表里的名字更新成了本次带来的",
                    "SonyConnect-Test".equals(ps.find(phoneId).peerName));

            // ---- 手机改名后重连：表里那份名字要跟着更新 ----
            //   名字是配对那一刻抄进表的，而手机随时可能在系统设置里改名
            //   （本机实测就是"SonyConnect 2411DRN47C" → "Redmi 14R 5G"），
            //   不跟新的话相机端配对列表会一直显示旧名。只改名字，不动准入。
            //   ★ 同一设备码的新连接会**踢掉**旧的那条（单客户端语义），所以后面
            //     这一段都用 c4，c3 到此已经死了。
            long pairedAtBeforeRename = ps.find(phoneId).pairedAt;
            Client c4 = new Client();
            c4.connectInitOnly("127.0.0.1", port, phoneGuid16, "Redmi 14R 5G");
            check("改名重连后表里的名字被更新",
                    "Redmi 14R 5G".equals(ps.find(phoneId).peerName));
            check("改名不影响准入（重连后照样可用）",
                    c4.req(PtpCodec.OP_PING, null).code == PtpCodec.RC_OK);
            check("改名后配对时间不被重置（pairedAt 仍是首次那次的）",
                    ps.find(phoneId).pairedAt == pairedAtBeforeRename);

            // ---- 未配对设备在同一时刻仍被拒（不会因为有人配对过就放开）----
            check("有已配对设备时未配对的新设备仍被拒",
                    Client.initFailReason("127.0.0.1", port, otherGuid16)
                            == PtpCodec.FAIL_NOT_PAIRED);

            // ---- OP_PAIR_REMOVE：手机主动解除配对（走当前活着的那条连接）----
            Client.Reply rm = c4.req(PtpCodec.OP_PAIR_REMOVE, null);
            check("OP_PAIR_REMOVE 受理", rm.code == PtpCodec.RC_OK);
            check("解除后配对表里没有它了", !ps.contains(phoneId));
            c4.close();
            c3.close();

            // ---- 解除之后又变回未配对 ----
            check("解除配对后该手机也需重新配对（Init Fail NOT_PAIRED）",
                    Client.initFailReason("127.0.0.1", port, phoneGuid16)
                            == PtpCodec.FAIL_NOT_PAIRED);

            c2.close();
        } catch (Throwable t) {
            System.out.println("[FAIL] 配对全流程抛出异常: " + t);
            t.printStackTrace(System.out);
            failed++;
        } finally {
            if (server != null) {
                server.stop();
            }
            check("停止后 isRunning 为假且端口已归零",
                    server != null && !server.isRunning() && server.getProtoPort() == 0);
            deleteTree(base);
        }
    }

    private static void writeFile(File f, byte[] data) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(f);
            os.write(data);
        } catch (Exception e) {
            System.out.println("[WARN] 写测试文件失败: " + e);
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) {
                    deleteTree(kids[i]);
                }
            }
        }
        f.delete();
    }

    static final class Stub implements PtpIpServer.Handler {
        final byte[] guid;
        final byte[] thumb;

        /**
         * "慢对象"：并发断言的传输窗口。
         *
         * 内核的传输循环按 {@code PtpCodec.CHUNK}(128 KiB) 一块读，所以这里每次
         * 分块读人工延时 [SLOW_MS_PER_READ] → 8 MB 摊成约 2.5 秒的真流式传输。
         * 只有这样，"控制面请求落在传输进行中"才是真的被压住的，而不是碰巧跑在前面。
         */
        static final String SLOW_PATH = "/DCIM/SLOW.ARW";
        static final int SLOW_MS_PER_READ = 40;
        final byte[] slow;

        volatile int lastThumbOp = -1;
        volatile String[] lastThumbPaths;

        Stub(byte[] guid) {
            this.guid = guid;
            // 512 字节可辨识内容，便于逐字节比对与切片校验
            byte[] t = new byte[512];
            for (int i = 0; i < t.length; i++) {
                t[i] = (byte) (i * 7 + 3);
            }
            this.thumb = t;
            // 8 MB 慢对象：64 块 × 40ms ≈ 2.5 秒
            byte[] s = new byte[8 * 1024 * 1024];
            for (int i = 0; i < s.length; i++) {
                s[i] = (byte) (i * 31 + 11);
            }
            this.slow = s;
        }

        private static void sleepQuietly(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private static boolean known(String path) {
            return "/DCIM/DSC00001.ARW".equals(path) || "/DCIM/DSC00002.ARW".equals(path);
        }

        public byte[] guid() {
            return guid;
        }

        public String deviceName() {
            return "ILCE-6300 T";
        }

        public byte[] deviceInfo() {
            return ascii("{\"model\":\"ILCE-6300\",\"serial\":\"6512345\","
                    + "\"fw\":\"36134_07.2018061901\",\"batteryRemainMin\":137}");
        }

        public int batteryPct() {
            return 77;
        }

        public boolean hasLens() {
            return true;
        }

        public String lensName() {
            return "E PZ 16-50mm F3.5-5.6 OSS";
        }

        public boolean allowInitiator(byte[] g) {
            return true;
        }

        public boolean isPairingMode() {
            return true;
        }

        public boolean hasPairedInitiator() {
            return false;
        }

        public byte[] readObject(String path, int kind, long offset, int length) {
            if (SLOW_PATH.equals(path)) {
                // 人工延时 = 传输窗口；内核按 CHUNK 一块调这里
                sleepQuietly(SLOW_MS_PER_READ);
                if (offset < 0 || offset >= slow.length) {
                    return new byte[0];
                }
                int availS = (int) (slow.length - offset);
                int nS = (length < 0) ? availS : Math.min(length, availS);
                byte[] so = new byte[nS];
                System.arraycopy(slow, (int) offset, so, 0, nS);
                return so;
            }
            if (!known(path) || offset < 0 || offset >= thumb.length) {
                return new byte[0];
            }
            int avail = (int) (thumb.length - offset);
            int n = (length < 0) ? avail : Math.min(length, avail);
            byte[] o = new byte[n];
            System.arraycopy(thumb, (int) offset, o, 0, n);
            return o;
        }

        /**
         * 刻意返回 null：本 stub 用来守住服务端的**回退路径**（无句柄时逐块
         * readObject）。句柄路径由配对段里用真 PtpCameraHandler 的端到端用例覆盖，
         * 两条路都有断言压着。
         */
        public PtpIpServer.Handler.ObjectReader openObject(String path, int kind) {
            return null;
        }

        public long objectSize(String path, int kind) {
            if (SLOW_PATH.equals(path)) {
                return slow.length;
            }
            return known(path) ? thumb.length : -1L;
        }

        public long objectMtime(String path) {
            return 1700000000000L;
        }

        public byte[] listDir(String path) {
            if (!"/DCIM".equals(path)) {
                return null;
            }
            return ascii("DSC00001.ARW\nDSC00002.ARW\n");
        }

        public void onThumbControl(int op, String[] paths) {
            lastThumbOp = op;
            lastThumbPaths = paths;
        }


    }

    // ============================================================
    // 迷你 PTP/IP 测试客户端
    //
    // 独立实现，线序与相机端 PtpIpServer / 手机端 PtpIpClient.kt 逐字节对齐：
    //   Length(4) | Type(4) | 载荷
    // 服务端若偏一位，这里立刻红 —— 这就是本测试类的价值。
    //
    // ★ 加解密层已移除，所以这里不再有会话密钥/封包/序号；准入靠配对表。
    // ============================================================

    static final class Client {

        Socket sock;
        InputStream in;
        OutputStream out;
        int connNo;
        String peerName;
        int txCounter;
        byte[] myGuid;
        byte[] cameraGuid16;

        /** 收的一帧。 */
        static final class Frame {
            int type;
            byte[] plain;
        }

        /** 操作应答的公共视图（blob 形式与普通形式统一）。 */
        static final class Reply {
            int dataPhase;
            int code;
            int txId;
            int[] params;
            byte[] blob;
        }

        // ---------- 收发 ----------

        void sendRaw(byte[] framed) throws IOException {
            out.write(framed);
            out.flush();
        }

        /** 发一个完整帧（明文；链路上已无封装层）。 */
        void send(byte[] framed) throws IOException {
            sendRaw(framed);
        }

        static int txIdOf(int type, byte[] body) {
            if (type == PtpCodec.T_OPERATION_REQ || type == PtpCodec.T_OPERATION_RSP) {
                return body.length >= 10 ? PtpCodec.i32(body, 6) : 0;
            }
            if (type == PtpCodec.T_EVENT) {
                return body.length >= 6 ? PtpCodec.i32(body, 2) : 0;
            }
            if (type == PtpCodec.T_START_DATA || type == PtpCodec.T_DATA
                    || type == PtpCodec.T_END_DATA || type == PtpCodec.T_CANCEL) {
                return body.length >= 4 ? PtpCodec.i32(body, 0) : 0;
            }
            return 0;
        }

        PtpCodec.Msg recvMsg() throws IOException {
            PtpCodec.Msg m = PtpCodec.read(in);
            if (m == null) {
                throw new EOFException("对端关闭连接");
            }
            return m;
        }

        /** 收一帧（明文）。 */
        Frame recv() throws IOException {
            PtpCodec.Msg m = recvMsg();
            Frame f = new Frame();
            f.type = m.type;
            f.plain = m.body;
            return f;
        }

        // ---------- 控制连接 ----------

        /** Init Cmd Req → Ack（已配对设备到此就进控制循环了）。 */
        void connect(String host, int port, byte[] guid) throws IOException {
            connectInitOnly(host, port, guid, "SonyConnect-Test");
        }

        /** 只做 Init Cmd 握手：配对测试用它拿到"窗口期的半截连接"。 */
        void connectInitOnly(String host, int port, byte[] guid, String friendly)
                throws IOException {
            myGuid = guid;
            sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, port), 5000);
            sock.setSoTimeout(8000);
            in = sock.getInputStream();
            out = sock.getOutputStream();

            sendRaw(PtpCodec.initCmdReq(guid, friendly, (1 << 16)));

            PtpCodec.Msg ack = recvMsg();
            if (ack == null) {
                throw new IOException("Init Cmd 对端无应答");
            }
            if (ack.type == PtpCodec.T_INIT_FAIL) {
                throw new IOException("Init Fail reason=" + PtpCodec.i32(ack.body, 0));
            }
            if (ack.type != PtpCodec.T_INIT_CMD_ACK) {
                throw new IOException("期望 Init Cmd Ack，收到 type=0x"
                        + Integer.toHexString(ack.type));
            }
            connNo = PtpCodec.i32(ack.body, 0);
            cameraGuid16 = new byte[16];
            System.arraycopy(ack.body, 4, cameraGuid16, 0, 16);
            int[] next = new int[1];
            peerName = PtpCodec.readName(ack.body, 20, next);
        }

        /**
         * 探测"这个 deviceId 现在能不能连上"：返回 Init Fail 的理由码，
         * 没被拒返回 -1，其它异常返回 -2。把"应当被拒"写成断言而不是异常。
         */
        static int initFailReason(String host, int port, byte[] guid) {
            Client probe = new Client();
            try {
                probe.connectInitOnly(host, port, guid, "Reject-Probe");
                probe.close();
                return -1;
            } catch (IOException e) {
                probe.close();
                String m = e.getMessage();
                int at = m == null ? -1 : m.indexOf("reason=");
                if (at < 0) {
                    return -2;
                }
                try {
                    return Integer.parseInt(m.substring(at + 7).trim());
                } catch (Exception nfe) {
                    return -2;
                }
            }
        }

        /**
         * 一对一的明文操作（配对专用：这条路不经过请求/响应事务表）。
         * <p>响应形式自适应：成功应答多为 DP_NONE（{@code opRsp}），带载荷的才是
         * DP_DATA_IN（{@code opRspBlob}）。两种都要能吃 —— 配对失败走的就是前者。
         */
        PtpCodec.OpBlob plainReqBlob(int opCode, int tx, byte[] blob) throws IOException {
            sendRaw(PtpCodec.opReqBlob(opCode, tx, blob));
            PtpCodec.Msg m = recvMsg();
            if (m == null) {
                throw new IOException("配对阶段对端断开");
            }
            if (PtpCodec.i32(m.body, 0) == PtpCodec.DP_DATA_IN) {
                return PtpCodec.parseOpBlob(m.body);
            }
            PtpCodec.Op o = PtpCodec.parseOp(m.body);
            PtpCodec.OpBlob out = new PtpCodec.OpBlob();
            out.dataPhase = o.dataPhase;
            out.code = o.code;
            out.txId = o.txId;
            out.params = o.params;
            out.blob = new byte[0];
            return out;
        }

        /** 握手期明文请求：只发不收，用于"预期对方直接断开"的场景。 */
        void plainSend(int opCode, int tx, byte[] blob) throws IOException {
            sendRaw(PtpCodec.opReqBlob(opCode, tx, blob));
        }


        /** 事件连接：Init Event Req → Ack，之后只收不发。 */
        void connectEvent(String host, int port, int controlConnNo) throws IOException {
            sock = new Socket();
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, port), 5000);
            sock.setSoTimeout(8000);
            in = sock.getInputStream();
            out = sock.getOutputStream();

            sendRaw(PtpCodec.initEventReq(controlConnNo));
            PtpCodec.Msg ack = recvMsg();
            if (ack.type == PtpCodec.T_INIT_FAIL) {
                throw new IOException("事件连接被拒 reason=" + PtpCodec.i32(ack.body, 0));
            }
            if (ack.type != PtpCodec.T_INIT_EVENT_ACK) {
                throw new IOException("期望 Init Event Ack，收到 type=0x"
                        + Integer.toHexString(ack.type));
            }
            // 事件连接挂在这条控制会话上（等同于服务端 owner.connNo 的语义）
            connNo = controlConnNo;
        }

        /** 读一条事件；非事件包 / 异常 → null。 */
        PtpCodec.Ev readEvent() {
            try {
                Frame f = recv();
                if (f.type != PtpCodec.T_EVENT) {
                    return null;
                }
                return PtpCodec.parseEvent(f.plain);
            } catch (Exception e) {
                return null;
            }
        }

        // ---------- 操作 ----------

        private Reply parseReply(byte[] body) throws IOException {
            Reply r = new Reply();
            r.dataPhase = PtpCodec.i32(body, 0);
            if (r.dataPhase == PtpCodec.DP_DATA_IN) {
                PtpCodec.OpBlob o = PtpCodec.parseOpBlob(body);
                r.code = o.code;
                r.txId = o.txId;
                r.params = o.params;
                r.blob = o.blob;
            } else {
                PtpCodec.Op o = PtpCodec.parseOp(body);
                r.code = o.code;
                r.txId = o.txId;
                r.params = o.params;
                r.blob = null;
            }
            return r;
        }

        /** 普通操作（无内联 blob 的响应）。 */
        Reply req(int opCode, int[] params) throws IOException {
            int tx = ++txCounter;
            send(PtpCodec.opReq(PtpCodec.DP_NONE, opCode, tx, params));
            Frame f = recv();
            Reply r = parseReply(f.plain);
            if (r.txId != tx) {
                throw new IOException("事务号不符：期望 " + tx + "，收到 " + r.txId);
            }
            return r;
        }

        /** 带路径 / blob 的操作请求（响应形式自适应）。 */
        Reply reqBlob(int opCode, int[] params, byte[] blob) throws IOException {
            int tx = ++txCounter;
            send(PtpCodec.opReqBlobParams(opCode, tx, params, blob));
            Frame f = recv();
            Reply r = parseReply(f.plain);
            if (r.txId != tx) {
                throw new IOException("事务号不符：期望 " + tx + "，收到 " + r.txId);
            }
            return r;
        }

        // ---------- 文件端口 ----------

        /**
         * 走文件端口取对象：DATA_OPEN → DATA_OPEN_ACK → Start/Data/End（全程明文）。
         * 任何失败返回 null（供"一次性 token"断言）。
         */
        byte[] fetchFile(int filePort, long token) {
            Socket fs = null;
            try {
                fs = new Socket();
                fs.setTcpNoDelay(true);
                fs.connect(new InetSocketAddress("127.0.0.1", filePort), 5000);
                fs.setSoTimeout(8000);
                InputStream fin = fs.getInputStream();
                OutputStream fout = fs.getOutputStream();

                byte[] open = PtpCodec.dataOpen(myGuid, connNo, token);
                fout.write(open);
                fout.flush();

                PtpCodec.Msg ack = PtpCodec.read(fin);
                if (ack == null || ack.type != PtpCodec.T_DATA_OPEN_ACK) {
                    return null;
                }

                ByteArrayOutputStream body = new ByteArrayOutputStream();
                long total = -1;
                while (true) {
                    PtpCodec.Msg m = PtpCodec.read(fin);
                    if (m == null) {
                        break;
                    }
                    if (m.type == PtpCodec.T_START_DATA) {
                        total = PtpCodec.totalOf(m.body);
                    } else if (m.type == PtpCodec.T_DATA) {
                        body.write(m.body, 4, m.body.length - 4);
                    } else if (m.type == PtpCodec.T_END_DATA) {
                        body.write(m.body, 4, m.body.length - 4);
                        break;
                    } else {
                        return null;
                    }
                }
                if (total < 0 || total != body.size()) {
                    return null;
                }
                return body.toByteArray();
            } catch (Exception e) {
                return null;
            } finally {
                if (fs != null) {
                    try {
                        fs.close();
                    } catch (IOException ignored) {
                        // 测试收尾，忽略
                    }
                }
            }
        }

        // ---------- 收尾 ----------

        /** 服务端是否已关闭本连接（用于"踢旧"断言）。 */
        boolean isClosedByPeer() {
            try {
                sock.setSoTimeout(1500);
                PtpCodec.Msg m = PtpCodec.read(in);
                return m == null;
            } catch (java.net.SocketTimeoutException te) {
                return false;
            } catch (Exception e) {
                return true;
            }
        }

        void close() {
            try {
                if (sock != null) {
                    sock.close();
                }
            } catch (IOException ignored) {
                // 测试收尾，忽略
            }
        }
    }

    // ============================================================
    // 断言与工具
    // ============================================================

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name);
        if (ok) passed++;
        else failed++;
    }

    /** ASCII 字面量 → 字节（与服务端对同一字符串的编码必须一致）。 */
    static byte[] ascii(String s) {
        byte[] o = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            o[i] = (byte) (s.charAt(i) & 0xFF);
        }
        return o;
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (int i = 0; i < parts.length; i++) n += parts[i].length;
        byte[] o = new byte[n];
        int off = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, o, off, parts[i].length);
            off += parts[i].length;
        }
        return o;
    }
}
