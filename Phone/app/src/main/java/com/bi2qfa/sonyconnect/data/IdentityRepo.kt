package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bi2qfa.sonyconnect.crypto.Rand
import com.bi2qfa.sonyconnect.ptpip.PtpCodec













object IdentityRepo {

    private const val PREFS = "identity"
    private const val KEY_DEVICE_ID = "deviceId"      
    private const val KEY_FRIENDLY = "friendlyName"

    private const val DEVICE_ID_LEN = 8
    private const val GUID_LEN = 16

    lateinit var prefs: SharedPreferences
        private set

    
    private lateinit var appContext: Context

    
    @Volatile
    private var deviceIdBytes: ByteArray = ByteArray(DEVICE_ID_LEN)

    
    @Volatile
    var deviceId: String = ""
        private set

    
    var friendlyName by mutableStateOf("")
        private set

    
















    private fun defaultFriendlyName(context: Context): String {
        systemDeviceName(context)?.let { return it }
        val model = runCatching { Build.MODEL }.getOrNull()?.trim().orEmpty()
        return if (model.isEmpty()) "手机" else model
    }

    
    private fun systemDeviceName(context: Context): String? {
        val keys = arrayOf(
            android.provider.Settings.Global.DEVICE_NAME,
            "bluetooth_name",
        )
        for (k in keys) {
            val v = runCatching {
                val raw = if (k == android.provider.Settings.Global.DEVICE_NAME) {
                    android.provider.Settings.Global.getString(context.contentResolver, k)
                } else {
                    android.provider.Settings.Secure.getString(context.contentResolver, k)
                }
                raw?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (v != null) return v
        }
        return null
    }

    






    private fun isLegacyDefault(name: String): Boolean = name.startsWith("SonyConnect ")

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appContext = context.applicationContext

        var hex = prefs.getString(KEY_DEVICE_ID, null)
        if (hex == null || hex.length != DEVICE_ID_LEN * 2) {
            val fresh = Rand.bytes(DEVICE_ID_LEN)
            hex = PtpCodec.hex(fresh)
            
            prefs.edit().putString(KEY_DEVICE_ID, hex).apply()
        }
        deviceId = hex.lowercase()
        deviceIdBytes = PtpCodec.unhex(deviceId).let {
            if (it.size == DEVICE_ID_LEN) it else ByteArray(DEVICE_ID_LEN)
        }

        val saved = prefs.getString(KEY_FRIENDLY, null)
        friendlyName = if (saved.isNullOrBlank() || isLegacyDefault(saved)) {
            defaultFriendlyName(context).also {
                prefs.edit().putString(KEY_FRIENDLY, it).apply()
            }
        } else {
            saved
        }
        android.util.Log.d("IdentityRepo", "本机友好名：$friendlyName（设备码 $deviceId）")
    }

    
    fun deviceIdBytes(): ByteArray = deviceIdBytes.copyOf()

    
    fun guid16(): ByteArray {
        val g = ByteArray(GUID_LEN)
        System.arraycopy(deviceIdBytes, 0, g, 0, DEVICE_ID_LEN)
        return g
    }

}
