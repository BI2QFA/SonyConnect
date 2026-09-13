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
    
    
    private const val KEY_LAST_DEVICE = "lastConnectedDevice" 

    lateinit var prefs: SharedPreferences
        private set

    var downloadTreeUri by mutableStateOf("")
        private set
    var seedIndex by mutableIntStateOf(0)
        private set
    var dynamicColor by mutableStateOf(true)
        private set
    var darkMode by mutableIntStateOf(2) 
        private set

    











    var lastConnectedDevice by mutableStateOf("")
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        downloadTreeUri = prefs.getString(KEY_TREE, "") ?: ""
        seedIndex = prefs.getInt(KEY_SEED, 0)
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true)
        
        
        darkMode = prefs.getInt(KEY_DARK, 2)
        lastConnectedDevice = prefs.getString(KEY_LAST_DEVICE, "") ?: ""
    }

    




    fun updateLastDevice(guidHex: String) {
        if (guidHex.isBlank() || guidHex.equals(lastConnectedDevice, ignoreCase = true)) return
        lastConnectedDevice = guidHex
        prefs.edit().putString(KEY_LAST_DEVICE, guidHex).apply()
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
