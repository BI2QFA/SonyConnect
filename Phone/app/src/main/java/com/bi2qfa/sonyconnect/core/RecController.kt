package com.bi2qfa.sonyconnect.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bi2qfa.sonyconnect.ptpip.LiveviewClient
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.transfer.DownloadService
import com.bi2qfa.sonyconnect.transfer.TransferItem
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RecState(
    val active: Boolean = false,
    val error: String = "",
    val lens: String = "",
    val focus: String = "idle",
    val recording: Boolean = false,
    val recSeconds: Int = 0,
    val lastShot: String = "",
    val lvPort: Int = 0,
    // 相机端取景/快门诊断（相机 REC_GET_STATE 下发，真机排障用）
    val lvSrc: String = "off",
    val seqFrames: Int = 0,
    val cbFrames: Int = 0,
    val jpgFrames: Int = 0,
    val lvClients: Int = 0,
    val lvSent: Int = 0,
    val shootFired: String = "",
    val shootErr: String = "",
    val seqErr: String = "",
    // 快门真值诊断：onShutter 状态（-1 未收到 / 0 成功 / 1 取消 / 2 错误）、
    // capture 是否真的启动、getInhibitionInfo 抑制位图（0 无抑制 / -1 读不到）
    val shutterSt: Int = -1,
    val capStart: Int = 0,
    val inhibit: Int = 0,
    val iso: String = "",
    val isoAvail: List<String> = emptyList(),
    val fnumber: String = "",
    val shutter: String = "",
    val expComp: String = "",
    val expCompMin: Int = 0,
    val expCompMax: Int = 0,
    val wb: String = "",
    val wbAvail: List<String> = emptyList(),
    val focusMode: String = "",
    val focusModeAvail: List<String> = emptyList(),
    val flash: String = "",
    val flashAvail: List<String> = emptyList(),
    val expMode: String = "",
    val expModeAvail: List<String> = emptyList(),
    val selfTimer: String = "0",
    val selfTimerAvail: List<String> = emptyList(),
)

object RecController {

    var sessionActive by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var rec by mutableStateOf(RecState())
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var lvStatus by mutableStateOf("")
        private set
    var lastShotPath by mutableStateOf<String?>(null)
        private set
    var postview by mutableStateOf<Bitmap?>(null)
        private set

    private var liveview: LiveviewClient? = null
    private var host: String? = null

    suspend fun enter() {
        val h = ConnectionCenter.host ?: run {
            error = "未连接相机"
            return
        }
        busy = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                ObjectRepository.recEnter(h)
            }
            host = h
            sessionActive = true
            refresh()
            startLiveview()
        } catch (t: Throwable) {
            error = t.message
            sessionActive = false
        } finally {
            busy = false
        }
    }

    suspend fun leave() {
        val h = host
        stopLiveview()
        sessionActive = false
        preview = null
        postview = null
        if (h != null) {
            runCatching {
                withContext(Dispatchers.IO) { ObjectRepository.recLeave(h) }
            }
        }
        host = null
        rec = RecState()
    }

    suspend fun refresh() {
        val h = host ?: return
        try {
            val json = withContext(Dispatchers.IO) { ObjectRepository.recState(h) } ?: return
            rec = parse(json)
        } catch (t: Throwable) {
            error = t.message
        }
    }

    suspend fun shoot() {
        val h = host ?: return
        busy = true
        error = null
        try {
            val path = withContext(Dispatchers.IO) { ObjectRepository.recShoot(h) }
            lastShotPath = path
            if (path.isNotBlank()) {
                val jpeg = withContext(Dispatchers.IO) {
                    ObjectRepository.fetchVirtualPreview(h, path)
                }
                if (jpeg != null) {
                    postview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                }
                withContext(Dispatchers.IO) { enqueueOriginal(h, path) }
            }
            refresh()
        } catch (t: Throwable) {
            error = t.message
        } finally {
            busy = false
        }
    }

    suspend fun halfPress(on: Boolean) {
        val h = host ?: return
        runCatching {
            withContext(Dispatchers.IO) { ObjectRepository.recAf(h, on) }
            refresh()
        }.onFailure { error = it.message }
    }

    suspend fun zoom(dir: Int) {
        val h = host ?: return
        runCatching {
            withContext(Dispatchers.IO) { ObjectRepository.recZoom(h, dir, 1) }
        }.onFailure { error = it.message }
    }

    suspend fun setProp(key: String, value: String) {
        val h = host ?: return
        busy = true
        try {
            withContext(Dispatchers.IO) { ObjectRepository.recSetProp(h, key, value) }
            refresh()
        } catch (t: Throwable) {
            error = t.message
        } finally {
            busy = false
        }
    }

    suspend fun touchAf(nx: Float, ny: Float) {
        val h = host ?: return
        val x = (nx.coerceIn(0f, 1f) * 1000).toInt()
        val y = (ny.coerceIn(0f, 1f) * 1000).toInt()
        runCatching {
            withContext(Dispatchers.IO) { ObjectRepository.recTouchAf(h, x, y) }
        }.onFailure { error = it.message }
    }

    suspend fun movie(start: Boolean) {
        val h = host ?: return
        busy = true
        try {
            withContext(Dispatchers.IO) { ObjectRepository.recMovie(h, start) }
            refresh()
        } catch (t: Throwable) {
            error = t.message
        } finally {
            busy = false
        }
    }

    fun dismissPostview() {
        postview = null
    }

    fun reset() {
        stopLiveview()
        sessionActive = false
        busy = false
        preview = null
        postview = null
        host = null
        rec = RecState()
    }

    fun onRecEvent(kind: Int, a: Int, b: Int) {
        when (kind) {
            1 -> rec = rec.copy(focus = if (a == 1 || a == 2) "lock" else if (a == 3 || a == 4) "working" else "idle")
            4 -> rec = rec.copy(recording = a != 0, recSeconds = b)
        }
    }

    private fun enqueueOriginal(host: String, path: String) {
        runCatching {
            val st = ObjectRepository.stat(host, path)
            TransferStore.enqueue(
                TransferItem(
                    id = path,
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = st.size,
                    mtime = st.mtime,
                ),
            )
            ConnectionCenter.appContext()?.let { DownloadService.start(it) }
        }
    }

    private suspend fun startLiveview() {
        val h = host ?: return
        stopLiveview()
        val port = try {
            withContext(Dispatchers.IO) { ObjectRepository.recLvStart(h) }
        } catch (t: Throwable) {
            error = t.message ?: error
            return
        }
        if (port <= 0) return
        val client = LiveviewClient(h, port, ConnectionCenter.boundNetwork)
        liveview = client
        client.start(
            { jpeg ->
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@start
                preview = bmp
            },
        ) { st -> lvStatus = st }
    }

    private fun stopLiveview() {
        liveview?.close()
        liveview = null
        lvStatus = ""
        val h = host
        if (h != null) {
            runCatching { ObjectRepository.recLvStop(h) }
        }
    }

    private fun parse(o: JSONObject): RecState = RecState(
        active = o.optBoolean("active"),
        error = o.optString("error"),
        lens = o.optString("lens"),
        focus = o.optString("focus", "idle"),
        recording = o.optBoolean("recording"),
        recSeconds = o.optInt("recSeconds"),
        lastShot = o.optString("lastShot"),
        lvPort = o.optInt("lvPort"),
        lvSrc = o.optString("lvSrc", "off"),
        seqFrames = o.optInt("seqFrames"),
        cbFrames = o.optInt("cbFrames"),
        jpgFrames = o.optInt("jpgFrames"),
        lvClients = o.optInt("lvClients"),
        lvSent = o.optInt("lvSent"),
        shootFired = o.optString("shootFired"),
        shootErr = o.optString("shootErr"),
        seqErr = o.optString("seqErr"),
        shutterSt = o.optInt("shutterSt", -1),
        capStart = o.optInt("capStart"),
        inhibit = o.optInt("inhibit"),
        iso = o.optString("iso"),
        isoAvail = strList(o, "isoAvail"),
        fnumber = o.optString("fnumber"),
        shutter = o.optString("shutter"),
        expComp = o.optString("expComp"),
        expCompMin = o.optInt("expCompMin"),
        expCompMax = o.optInt("expCompMax"),
        wb = o.optString("wb"),
        wbAvail = strList(o, "wbAvail"),
        focusMode = o.optString("focusMode"),
        focusModeAvail = strList(o, "focusModeAvail"),
        flash = o.optString("flash"),
        flashAvail = strList(o, "flashAvail"),
        expMode = o.optString("expMode"),
        expModeAvail = strList(o, "expModeAvail"),
        selfTimer = o.optString("selfTimer", "0"),
        selfTimerAvail = strList(o, "selfTimerAvail"),
    )

    private fun strList(o: JSONObject, key: String): List<String> {
        val arr: JSONArray = o.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) add(arr.optString(i))
        }
    }
}
