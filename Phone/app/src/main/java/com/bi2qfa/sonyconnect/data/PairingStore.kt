package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject











object PairingStore {

    private const val PREFS = "pairing"
    private const val KEY_TABLE = "pairedCameras"

    lateinit var prefs: SharedPreferences
        private set

    
    var cameras by mutableStateOf<List<PairedCamera>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cameras = decode(prefs.getString(KEY_TABLE, null))
    }

    
    data class PairedCamera(
        
        val peerDeviceId: String,
        
        val peerName: String,
        val peerModel: String = "",
        val peerSerial: String = "",
        val pairedAt: Long = 0L,
        val lastSeenAt: Long = 0L,
        val lastIp: String = "",
        val lastPort: Int = 0,
        
        val protoVersion: Int = 0,
    ) {
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PairedCamera) return false
            return peerDeviceId == other.peerDeviceId &&
                peerName == other.peerName &&
                peerModel == other.peerModel &&
                peerSerial == other.peerSerial &&
                pairedAt == other.pairedAt &&
                lastSeenAt == other.lastSeenAt &&
                lastIp == other.lastIp &&
                lastPort == other.lastPort &&
                protoVersion == other.protoVersion
        }

        override fun hashCode(): Int {
            var r = peerDeviceId.hashCode()
            r = 31 * r + peerName.hashCode()
            r = 31 * r + peerModel.hashCode()
            r = 31 * r + peerSerial.hashCode()
            r = 31 * r + pairedAt.hashCode()
            r = 31 * r + lastSeenAt.hashCode()
            r = 31 * r + lastIp.hashCode()
            r = 31 * r + lastPort
            r = 31 * r + protoVersion
            return r
        }
    }

    
    
    

    fun all(): List<PairedCamera> = cameras

    fun find(peerDeviceId: String): PairedCamera? =
        cameras.firstOrNull { it.peerDeviceId.equals(peerDeviceId, ignoreCase = true) }

    fun contains(peerDeviceId: String): Boolean = find(peerDeviceId) != null

    
    fun mostRecent(): PairedCamera? = cameras.firstOrNull()

    
    
    

    
    fun upsert(camera: PairedCamera) {
        val key = camera.peerDeviceId.lowercase()
        val existing = find(key)
        val merged = if (existing != null && camera.pairedAt == 0L) {
            camera.copy(pairedAt = existing.pairedAt)
        } else {
            camera.copy(peerDeviceId = key)
        }
        val next = cameras.filterNot { it.peerDeviceId.equals(key, ignoreCase = true) } + merged
        commit(next)
    }

    
    fun remove(peerDeviceId: String): Boolean {
        val next = cameras.filterNot { it.peerDeviceId.equals(peerDeviceId, ignoreCase = true) }
        if (next.size == cameras.size) return false
        commit(next)
        return true
    }

    
    fun touch(peerDeviceId: String, ip: String, port: Int, now: Long = System.currentTimeMillis()) {
        val cur = find(peerDeviceId) ?: return
        upsert(cur.copy(lastSeenAt = now, lastIp = ip, lastPort = port))
    }

    
    fun updateProtoVersion(peerDeviceId: String, protoVersion: Int) {
        val cur = find(peerDeviceId) ?: return
        if (cur.protoVersion == protoVersion) return
        upsert(cur.copy(protoVersion = protoVersion))
    }

    private fun commit(next: List<PairedCamera>) {
        cameras = next.sortedByDescending { it.lastSeenAt }
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_TABLE, encode(cameras)).apply()
        }
    }

    
    
    

    internal fun encode(list: List<PairedCamera>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.peerDeviceId)
                    .put("name", c.peerName)
                    .put("model", c.peerModel)
                    .put("serial", c.peerSerial)
                    .put("pairedAt", c.pairedAt)
                    .put("lastSeenAt", c.lastSeenAt)
                    .put("lastIp", c.lastIp)
                    .put("lastPort", c.lastPort)
                    .put("proto", c.protoVersion)
            )
        }
        return arr.toString()
    }

    internal fun decode(raw: String?): List<PairedCamera> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PairedCamera>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            out.add(
                PairedCamera(
                    peerDeviceId = id.lowercase(),
                    peerName = o.optString("name", ""),
                    peerModel = o.optString("model", ""),
                    peerSerial = o.optString("serial", ""),
                    pairedAt = o.optLong("pairedAt", 0L),
                    lastSeenAt = o.optLong("lastSeenAt", 0L),
                    lastIp = o.optString("lastIp", ""),
                    lastPort = o.optInt("lastPort", 0),
                    protoVersion = o.optInt("proto", 0),
                )
            )
        }
        return out.sortedByDescending { it.lastSeenAt }
    }
}
