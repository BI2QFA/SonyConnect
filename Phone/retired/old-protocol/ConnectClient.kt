package com.bi2qfa.sonyconnect.protocol

import android.net.Network
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SonyConnect 私有协议客户端（TCP 2122，UTF-8 按行 JSON，一问一答）。
 *
 * 单条长连接：connect() 做 HELLO 握手；之后 heartbeat()/info()/thumb*() 复用。
 * 每个请求独立 soTimeout（心跳 3s），断线/超时由调用方（ConnectionCenter）
 * 统一处理为"回到未连接"。socketFactory 绑定相机所在 Network，
 * 防止热点"无互联网"时流量被甩到蜂窝。
 */
object ConnectClient {

    private const val TAG = "ConnectClient"
    private const val PORT = 2122
    private const val PROTOCOL_VERSION = 1

    @Volatile
    var boundNetwork: Network? = null

    private val ioLock = Any()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** 连接并握手；失败返回 false（内部状态已清理） */
    fun connect(host: String): Boolean {
        synchronized(ioLock) {
            closeQuietly()
            return try {
                // Network 绑定：热点"无互联网"时防止流量被甩到蜂窝
                val s = boundNetwork?.socketFactory?.createSocket() ?: Socket()
                s.connect(InetSocketAddress(host, PORT), 3000)
                s.tcpNoDelay = true
                s.soTimeout = 5000
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                socket = s
                reader = r
                writer = w
                // HELLO 握手
                w.write(JSONObject().put("cmd", "HELLO").put("proto", PROTOCOL_VERSION).toString())
                w.write("\n")
                w.flush()
                val reply = r.readLine() ?: return false
                val json = JSONObject(reply)
                json.optString("rsp") == "HELLO" && json.optInt("proto") == PROTOCOL_VERSION
            } catch (e: Exception) {
                Log.d(TAG, "connect failed: ${e.message}")
                closeQuietly()
                false
            }
        }
    }

    fun isConnected(): Boolean = synchronized(ioLock) { socket?.isConnected == true && !socket!!.isClosed }

    /** 心跳；应答含 app 标识才算活（扫描探测与保活共用）。
     *  相机端 1.5 起心跳应答顺带携带 lens+batteryPct——
     *  手机端"每次电量更新时同步更新镜头"。 */
    fun heartbeat(): HeartbeatState? {
        val json = request("HEARTBEAT") ?: return null
        if (json.optString("rsp") != "HEARTBEAT") return null
        return HeartbeatState(
            batteryPct = if (json.has("batteryPct")) json.optInt("batteryPct", -1) else null,
            lens = if (json.has("lens")) json.optString("lens", "") else null,
        )
    }

    data class HeartbeatState(val batteryPct: Int?, val lens: String?)

    fun close() {
        synchronized(ioLock) { closeQuietly() }
    }

    // ===== 数据结构 =====

    data class CameraInfo(
        val model: String,
        val serial: String,
        val firmware: String,
        val lens: String,
        val batteryPct: Int,
        val batteryRemainMin: Int,
        val mode: String,
        val ssid: String,
        val password: String,
        val ip: String,
        val ftpPort: Int,
        val connectPort: Int,
        val thumbState: String,
        val thumbDone: Int,
        val thumbTotal: Int,
        val infoSource: String,
    )

    fun info(): CameraInfo? {
        val json = request("INFO") ?: return null
        if (json.optString("rsp") != "INFO") return null
        val thumb = json.optJSONObject("thumb") ?: JSONObject()
        return CameraInfo(
            model = json.optString("model", ""),
            serial = json.optString("serial", ""),
            firmware = json.optString("firmware", ""),
            lens = json.optString("lens", ""),
            batteryPct = json.optInt("batteryPct", -1),
            batteryRemainMin = json.optInt("batteryRemainMin", -1),
            mode = json.optString("mode", ""),
            ssid = json.optString("ssid", ""),
            password = json.optString("password", ""),
            ip = json.optString("ip", ""),
            ftpPort = json.optInt("ftpPort", 2121),
            connectPort = json.optInt("connectPort", 2122),
            thumbState = thumb.optString("state", "idle"),
            thumbDone = thumb.optInt("done", 0),
            thumbTotal = thumb.optInt("total", 0),
            infoSource = json.optString("infoSource", ""),
        )
    }

    fun thumbBegin(paths: List<String>): Int {
        val json = JSONObject().put("cmd", "THUMB_BEGIN").put("paths", JSONArray(paths))
        val reply = sendAndRead(json) ?: return 0
        return reply.optInt("accepted", 0)
    }

    fun thumbPause() {
        runCatching { sendAndRead(JSONObject().put("cmd", "THUMB_PAUSE")) }
    }

    fun thumbResume() {
        runCatching { sendAndRead(JSONObject().put("cmd", "THUMB_RESUME")) }
    }

    fun thumbCancel() {
        runCatching { sendAndRead(JSONObject().put("cmd", "THUMB_CANCEL")) }
    }

    /** 请求相机端自动退出（传输完成后的可选动作）；相机先回包再走退出流程 */
    fun exitApp() {
        runCatching { sendAndRead(JSONObject().put("cmd", "EXIT_APP")) }
    }

    // ===== 内部 =====

    private fun request(cmd: String): JSONObject? = sendAndRead(JSONObject().put("cmd", cmd))

    private fun sendAndRead(json: JSONObject): JSONObject? = synchronized(ioLock) {
        val w = writer ?: return null
        val r = reader ?: return null
        val s = socket ?: return null
        return try {
            s.soTimeout = 5000
            w.write(json.toString())
            w.write("\n")
            w.flush()
            val line = r.readLine() ?: run { closeQuietly(); return null }
            JSONObject(line)
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.message}")
            closeQuietly()
            null
        }
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        reader = null
        writer = null
    }
}

// org.json 的 JSONArray 构造辅助（List<String> → JSONArray）
private fun JSONArray(paths: List<String>): org.json.JSONArray {
    val arr = org.json.JSONArray()
    for (p in paths) arr.put(p)
    return arr
}
