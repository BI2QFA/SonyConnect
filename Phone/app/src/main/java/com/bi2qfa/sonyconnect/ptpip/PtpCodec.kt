package com.bi2qfa.sonyconnect.ptpip

import java.io.EOFException
import java.io.IOException
import java.io.InputStream







object PtpCodec {

    
    const val HEADER_LEN = 8

    
    const val MAX_PACKET = 1024 * 1024

    



    const val CHUNK = 128 * 1024

    
    const val T_INIT_CMD_REQ = 0x0001
    const val T_INIT_CMD_ACK = 0x0002
    const val T_INIT_EVENT_REQ = 0x0003
    const val T_INIT_EVENT_ACK = 0x0004
    const val T_INIT_FAIL = 0x0005
    const val T_OPERATION_REQ = 0x0006
    const val T_OPERATION_RSP = 0x0007
    const val T_EVENT = 0x0008
    const val T_START_DATA = 0x0009
    const val T_DATA = 0x000A
    const val T_END_DATA = 0x000B
    const val T_CANCEL = 0x000C
    const val T_PROBE_REQ = 0x000D
    const val T_PROBE_RESP = 0x000E

    
    const val T_DATA_OPEN = 0x0040
    const val T_DATA_OPEN_ACK = 0x0041

    
    const val DP_NONE = 0
    const val DP_DATA_IN = 1
    const val DP_DATA_OUT = 2

    
    const val FAIL_REJECTED = 0x01
    const val FAIL_UNSUPPORTED = 0x02
    const val FAIL_BUSY = 0x03
    
    const val FAIL_NOT_PAIRED = 0x04

    
    const val OP_PAIR_BEGIN = 0x9001
    const val OP_PAIR_EXCHANGE = 0x9002
    const val OP_PAIR_ABORT = 0x9004
    
    const val OP_PAIR_REMOVE = 0x9005
    
    const val OP_PING = 0x9012
    const val OP_DEVICE_INFO = 0x9013
    const val OP_LIST_DIR = 0x9020
    const val OP_STAT = 0x9021
    const val OP_GET_OBJECT = 0x9022
    const val OP_THUMB_QUEUE_BEGIN = 0x9023
    const val OP_THUMB_QUEUE_PAUSE = 0x9024
    const val OP_THUMB_QUEUE_RESUME = 0x9025
    const val OP_THUMB_QUEUE_CANCEL = 0x9026

    const val OP_REC_ENTER = 0x9030
    const val OP_REC_LEAVE = 0x9031
    const val OP_REC_GET_STATE = 0x9032
    const val OP_LV_START = 0x9033
    const val OP_LV_STOP = 0x9034
    const val OP_SHOOT = 0x9035
    const val OP_AF_HALF = 0x9036
    const val OP_AF_CANCEL = 0x9037
    const val OP_ZOOM = 0x9038
    const val OP_SET_PROP = 0x9039
    const val OP_TOUCH_AF = 0x903A
    const val OP_MOVIE_START = 0x903B
    const val OP_MOVIE_STOP = 0x903C
    
    
    

    
    const val EV_THUMB_PROGRESS = 0x9041

    





    const val EV_PAIRED_REMOVED = 0x9042

    








    const val EV_APP_EXITING = 0x9043

    







    const val EV_MODE_SWITCHING = 0x9044
    const val EV_REC = 0x9045

    
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TX = 0x2004
    const val RC_NOT_SUPPORTED = 0x2005
    const val RC_ACCESS_DENIED = 0x2006
    const val RC_NOT_FOUND = 0x2007
    const val RC_DEVICE_BUSY = 0x2008
    const val RC_PAIRING_FAILED = 0x2009
    const val RC_CANCELED = 0x200B

    
    const val KIND_THUMB = 0
    const val KIND_PREVIEW = 1
    const val KIND_ORIGINAL = 2

    
    const val VENDOR_TAG = "SonyConnect/2.0"

    
    
    

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun i64(b: ByteArray, off: Int): Long {
        val lo = i32(b, off).toLong() and 0xFFFFFFFFL
        val hi = i32(b, off + 4).toLong() and 0xFFFFFFFFL
        return lo or (hi shl 32)
    }

    fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun put32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun put64(b: ByteArray, off: Int, v: Long) {
        put32(b, off, (v and 0xFFFFFFFFL).toInt())
        put32(b, off + 4, ((v ushr 32) and 0xFFFFFFFFL).toInt())
    }

    
    
    

    fun header(type: Int, payloadLen: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        put32(h, 0, HEADER_LEN + payloadLen)
        put32(h, 4, type)
        return h
    }

    fun frame(type: Int, payload: ByteArray?): ByteArray {
        val n = payload?.size ?: 0
        val out = ByteArray(HEADER_LEN + n)
        put32(out, 0, out.size)
        put32(out, 4, type)
        if (n > 0) System.arraycopy(payload!!, 0, out, HEADER_LEN, n)
        return out
    }

    
    class Msg(val type: Int, val body: ByteArray)

    









    @Throws(IOException::class)
    fun read(input: InputStream): Msg? {
        val h = ByteArray(HEADER_LEN)
        
        
        
        if (readHeader(input, h) == 0) return null

        val len = i32(h, 0)
        val type = i32(h, 4)
        if (len < HEADER_LEN || len > MAX_PACKET) {
            throw IOException("坏包长度: $len (type=$type)")
        }
        val body = ByteArray(len - HEADER_LEN)
        if (body.isNotEmpty()) readFully(input, body, 0, body.size)
        return Msg(type, body)
    }

    



    @Throws(IOException::class)
    private fun readHeader(input: InputStream, h: ByteArray): Int {
        var got = 0
        while (got < h.size) {
            val r = input.read(h, got, h.size - got)
            if (r < 0) break
            got += r
        }
        if (got in 1 until h.size) {
            throw IOException("包头截断: 还差 ${h.size - got} 字节")
        }
        return got
    }

    @Throws(IOException::class)
    fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var got = 0
        while (got < len) {
            val r = input.read(buf, off + got, len - got)
            if (r < 0) throw EOFException("包体截断: 还差 ${len - got} 字节")
            got += r
        }
    }

    
    
    

    fun nameLen(s: String): Int = 1 + s.length * 2

    fun writeName(buf: ByteArray, off: Int, s: String): Int {
        val chars = minOf(s.length, 255)
        buf[off] = chars.toByte()
        for (i in 0 until chars) {
            val c = s[i].code
            buf[off + 1 + i * 2] = (c and 0xFF).toByte()
            buf[off + 1 + i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return 1 + chars * 2
    }

    
    fun readName(b: ByteArray, off: Int, nextOff: IntArray?): String {
        val chars = u8(b, off)
        val sb = StringBuilder(chars)
        for (i in 0 until chars) {
            val lo = b[off + 1 + i * 2].toInt() and 0xFF
            val hi = b[off + 1 + i * 2 + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
        }
        nextOff?.let { if (it.isNotEmpty()) it[0] = off + 1 + chars * 2 }
        return sb.toString()
    }

    
    
    

    fun initCmdReq(guid: ByteArray, friendlyName: String, protoVer: Int): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl + 4)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        put32(p, 16 + nl, protoVer)
        return frame(T_INIT_CMD_REQ, p)
    }

    fun initCmdAck(connNo: Int, guid: ByteArray, friendlyName: String, protoVer: Int): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(4 + 16 + nl + 4)
        put32(p, 0, connNo)
        System.arraycopy(guid, 0, p, 4, 16)
        writeName(p, 20, friendlyName)
        put32(p, 20 + nl, protoVer)
        return frame(T_INIT_CMD_ACK, p)
    }

    fun initEventReq(connNo: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, connNo)
        return frame(T_INIT_EVENT_REQ, p)
    }

    fun initEventAck(): ByteArray = frame(T_INIT_EVENT_ACK, ByteArray(0))

    fun initFail(reason: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, reason)
        return frame(T_INIT_FAIL, p)
    }

    fun opReq(dataPhase: Int, opCode: Int, txId: Int, params: IntArray?): ByteArray =
        frame(T_OPERATION_REQ, opBody(dataPhase, opCode, txId, params))

    fun opRsp(dataPhase: Int, respCode: Int, txId: Int, params: IntArray?): ByteArray =
        frame(T_OPERATION_RSP, opBody(dataPhase, respCode, txId, params))

    private fun opBody(dataPhase: Int, code: Int, txId: Int, params: IntArray?): ByteArray {
        val n = params?.size ?: 0
        val p = ByteArray(10 + n * 4)
        put32(p, 0, dataPhase)
        put16(p, 4, code)
        put32(p, 6, txId)
        for (i in 0 until n) put32(p, 10 + i * 4, params!![i])
        return p
    }

    
    class Op {
        var dataPhase = 0
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
    }

    @Throws(IOException::class)
    fun parseOp(body: ByteArray): Op {
        if (body.size < 10) throw IOException("操作包过短: ${body.size}")
        val o = Op()
        o.dataPhase = i32(body, 0)
        o.code = u16(body, 4)
        o.txId = i32(body, 6)
        val n = (body.size - 10) / 4
        o.params = IntArray(n)
        for (i in 0 until n) o.params[i] = i32(body, 10 + i * 4)
        return o
    }

    


    fun opReqBlob(opCode: Int, txId: Int, blob: ByteArray?): ByteArray =
        opReqBlobParams(opCode, txId, null, blob)

    









    fun opReqBlobParams(opCode: Int, txId: Int, params: IntArray?, blob: ByteArray?): ByteArray {
        val n = params?.size ?: 0
        val bl = blob?.size ?: 0
        val p = ByteArray(4 + 2 + 4 + 4 + n * 4 + 4 + bl)
        put32(p, 0, DP_DATA_IN)
        put16(p, 4, opCode)
        put32(p, 6, txId)
        put32(p, 10, n)
        for (i in 0 until n) put32(p, 14 + i * 4, params!![i])
        val o = 14 + n * 4
        put32(p, o, bl)
        if (bl > 0) System.arraycopy(blob!!, 0, p, o + 4, bl)
        return frame(T_OPERATION_REQ, p)
    }

    






    fun opRspBlob(respCode: Int, txId: Int, params: IntArray?, blob: ByteArray?): ByteArray {
        val n = params?.size ?: 0
        val bl = blob?.size ?: 0
        val p = ByteArray(4 + 2 + 4 + 4 + n * 4 + 4 + bl)
        put32(p, 0, DP_DATA_IN)
        put16(p, 4, respCode)
        put32(p, 6, txId)
        put32(p, 10, n)
        for (i in 0 until n) put32(p, 14 + i * 4, params!![i])
        val o = 14 + n * 4
        put32(p, o, bl)
        if (bl > 0) System.arraycopy(blob!!, 0, p, o + 4, bl)
        return frame(T_OPERATION_RSP, p)
    }

    
    class OpBlob {
        var dataPhase = 0
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
        
        var blob: ByteArray = ByteArray(0)
    }

    @Throws(IOException::class)
    fun parseOpBlob(body: ByteArray): OpBlob {
        if (body.size < 18) throw IOException("扩展响应包过短: ${body.size}")
        val o = OpBlob()
        o.dataPhase = i32(body, 0)
        o.code = u16(body, 4)
        o.txId = i32(body, 6)
        val n = i32(body, 10)
        if (n < 0 || 14 + n * 4 + 4 > body.size) throw IOException("扩展响应 paramCount 越界: $n")
        o.params = IntArray(n)
        for (i in 0 until n) o.params[i] = i32(body, 14 + i * 4)
        val bl = i32(body, 14 + n * 4)
        if (bl < 0 || 14 + n * 4 + 4 + bl > body.size) throw IOException("扩展响应 blobLen 越界: $bl")
        o.blob = ByteArray(bl)
        if (bl > 0) System.arraycopy(body, 14 + n * 4 + 4, o.blob, 0, bl)
        return o
    }

    
    fun event(evCode: Int, txId: Int, params: IntArray?): ByteArray {
        val n = params?.size ?: 0
        val p = ByteArray(6 + n * 4)
        put16(p, 0, evCode)
        put32(p, 2, txId)
        for (i in 0 until n) put32(p, 6 + i * 4, params!![i])
        return frame(T_EVENT, p)
    }

    class Ev {
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
    }

    @Throws(IOException::class)
    fun parseEvent(body: ByteArray): Ev {
        if (body.size < 6) throw IOException("事件包过短: ${body.size}")
        val e = Ev()
        e.code = u16(body, 0)
        e.txId = i32(body, 2)
        val n = (body.size - 6) / 4
        e.params = IntArray(n)
        for (i in 0 until n) e.params[i] = i32(body, 6 + i * 4)
        return e
    }

    
    
    

    
    fun startData(txId: Int, total: Long): ByteArray {
        val p = ByteArray(12)
        put32(p, 0, txId)
        put64(p, 4, total)
        return frame(T_START_DATA, p)
    }

    fun dataPacket(txId: Int, data: ByteArray?): ByteArray {
        val n = data?.size ?: 0
        val p = ByteArray(4 + n)
        put32(p, 0, txId)
        if (n > 0) System.arraycopy(data!!, 0, p, 4, n)
        return frame(T_DATA, p)
    }

    fun endData(txId: Int, data: ByteArray?): ByteArray {
        val n = data?.size ?: 0
        val p = ByteArray(4 + n)
        put32(p, 0, txId)
        if (n > 0) System.arraycopy(data!!, 0, p, 4, n)
        return frame(T_END_DATA, p)
    }

    fun cancel(txId: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, txId)
        return frame(T_CANCEL, p)
    }

    fun txIdOf(body: ByteArray): Int = if (body.size >= 4) i32(body, 0) else 0

    @Throws(IOException::class)
    fun totalOf(body: ByteArray): Long {
        if (body.size < 12) throw IOException("Start Data 载荷过短: ${body.size}")
        return i64(body, 4)
    }

    
    
    

    
    fun dataOpen(guid: ByteArray, connNo: Int, token: Long): ByteArray {
        val p = ByteArray(28)
        System.arraycopy(guid, 0, p, 0, 16)
        put32(p, 16, connNo)
        put64(p, 20, token)
        return frame(T_DATA_OPEN, p)
    }

    fun dataOpenAck(): ByteArray = frame(T_DATA_OPEN_ACK, ByteArray(0))

    class DataOpen {
        var guid: ByteArray = ByteArray(16)
        var connNo = 0
        var token = 0L
    }

    @Throws(IOException::class)
    fun parseDataOpen(body: ByteArray): DataOpen {
        if (body.size < 28) throw IOException("DATA_OPEN 载荷过短: ${body.size}")
        val d = DataOpen()
        d.guid = body.copyOfRange(0, 16)
        d.connNo = i32(body, 16)
        d.token = i64(body, 20)
        return d
    }

    
    
    

    
    fun probe(
        request: Boolean,
        guid: ByteArray,
        friendlyName: String,
        protoPort: Int,
        filePort: Int,
        pairingMode: Boolean,
        paired: Boolean,
    ): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl + 8)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        val o = 16 + nl
        put16(p, o, protoPort)
        put16(p, o + 2, filePort)
        p[o + 4] = (if (pairingMode) 1 else 0).toByte()
        p[o + 5] = (if (paired) 1 else 0).toByte()
        put16(p, o + 6, 0)
        return frame(if (request) T_PROBE_REQ else T_PROBE_RESP, p)
    }

    class Probe {
        var guid: ByteArray = ByteArray(16)
        var name: String = ""
        var protoPort = 0
        var filePort = 0
        var pairingMode = false
        var paired = false
    }

    @Throws(IOException::class)
    fun parseProbe(body: ByteArray): Probe {
        if (body.size < 17) throw IOException("探测包过短: ${body.size}")
        val pr = Probe()
        pr.guid = body.copyOfRange(0, 16)
        val next = IntArray(1)
        pr.name = readName(body, 16, next)
        val o = next[0]
        if (o + 8 <= body.size) {
            pr.protoPort = u16(body, o)
            pr.filePort = u16(body, o + 2)
            pr.pairingMode = body[o + 4].toInt() != 0
            pr.paired = body[o + 5].toInt() != 0
        }
        return pr
    }

    
    fun probeCompact(request: Boolean, guid: ByteArray, friendlyName: String): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        return frame(if (request) T_PROBE_REQ else T_PROBE_RESP, p)
    }

    fun hasVendorTag(friendlyName: String?): Boolean =
        friendlyName != null && friendlyName.endsWith(VENDOR_TAG)

    
    
    

    private const val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun unhex(s: String?): ByteArray {
        require(!(s == null || s.length % 2 != 0)) { "非法十六进制串" }
        val out = ByteArray(s!!.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(!(hi < 0 || lo < 0)) { "非法十六进制串" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

}
