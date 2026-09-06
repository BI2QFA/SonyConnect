package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

object SettingsRepo {

    private const val KEY_TREE = "downloadTreeUri"
    private const val KEY_SEED = "seed"
    private const val KEY_DYNAMIC = "dynamic"
    private const val KEY_DARK = "dark"
    private const val KEY_DEVICES = "deviceHistory"
    private const val KEY_PREFERRED = "preferredDevice"
    private const val KEY_AUTO_EXIT = "autoExitAfterTransfer"

    lateinit var prefs: SharedPreferences
        private set

    var downloadTreeUri by mutableStateOf("")
        private set
    var seedIndex by mutableIntStateOf(0)
        private set
    var dynamicColor by mutableStateOf(true)
        private set
    var darkMode by mutableIntStateOf(0)
        private set

    var deviceHistory by mutableStateOf<List<String>>(emptyList())
        private set

    var preferredDevice by mutableStateOf("")
        private set

    var autoExitAfterTransfer by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        downloadTreeUri = prefs.getString(KEY_TREE, "") ?: ""
        seedIndex = prefs.getInt(KEY_SEED, 0)
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true)
        darkMode = prefs.getInt(KEY_DARK, 0)
        deviceHistory = (prefs.getString(KEY_DEVICES, "") ?: "")
            .split('\n').filter { it.contains('|') }
        preferredDevice = prefs.getString(KEY_PREFERRED, "") ?: ""
        autoExitAfterTransfer = prefs.getBoolean(KEY_AUTO_EXIT, false)
    }

    fun recordDevice(model: String, serial: String) {
        if (model.isBlank() || serial.isBlank()) return
        val entry = "$model|$serial"
        if (deviceHistory.contains(entry)) return
        deviceHistory = (deviceHistory + entry).takeLast(10)
        prefs.edit().putString(KEY_DEVICES, deviceHistory.joinToString("\n")).apply()
    }

    fun updatePreferredDevice(v: String) {
        preferredDevice = v
        prefs.edit().putString(KEY_PREFERRED, v).apply()
    }

    fun updateAutoExitAfterTransfer(v: Boolean) {
        autoExitAfterTransfer = v
        prefs.edit().putBoolean(KEY_AUTO_EXIT, v).apply()
    }

    fun deviceLabel(entry: String): String {
        val p = entry.split('|', limit = 2)
        return if (p.size == 2) "${p[0]} SN:${p[1]}" else p[0]
    }

    fun updateDownloadTreeUri(v: String) {
        downloadTreeUri = v
        prefs.edit().putString(KEY_TREE, v).apply()
    }

    fun updateSeed(v: Int) {
        seedIndex = v
        prefs.edit().putInt(KEY_SEED, v).apply()
    }

    fun updateDynamicColor(v: Boolean) {
        dynamicColor = v
        prefs.edit().putBoolean(KEY_DYNAMIC, v).apply()
    }

    fun updateDarkMode(v: Int) {
        darkMode = v
        prefs.edit().putInt(KEY_DARK, v).apply()
    }

    fun downloadDirLabel(): String {
        if (downloadTreeUri.isBlank()) return ""
        return try {
            val id = android.provider.DocumentsContract.getTreeDocumentId(
                android.net.Uri.parse(downloadTreeUri)
            )
            val volume = id.substringBefore(':', "")
            val rest = id.substringAfter(':', "")
            val volumeName = when (volume) {
                "primary" -> "内部存储"
                "home" -> "主目录"
                "download" -> "下载"
                else -> volume
            }
            if (rest.isBlank()) volumeName else "$volumeName/$rest"
        } catch (e: Exception) {
            downloadTreeUri
        }
    }
}
