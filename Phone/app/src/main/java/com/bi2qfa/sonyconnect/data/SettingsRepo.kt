package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * 全局设置（SharedPreferences + Compose 状态单一事实源）。
 * 连接参数不落盘在本代（新协议每次扫描/扫码发现），只存外观与下载目录。
 */
object SettingsRepo {

    private const val KEY_TREE = "downloadTreeUri"
    private const val KEY_SEED = "seed"
    private const val KEY_DYNAMIC = "dynamic"
    private const val KEY_DARK = "dark"
    // ★ 键名保持 "lastConnectedDevice" 不改（改名会让已装机用户的记录凭空消失，
    //   而这个值现在承担的是"上次选中的设备"，语义已经变过一次，见 updateLastDevice）
    private const val KEY_LAST_DEVICE = "lastConnectedDevice" // 设备码 hex

    lateinit var prefs: SharedPreferences
        private set

    var downloadTreeUri by mutableStateOf("")
        private set
    var seedIndex by mutableIntStateOf(0)
        private set
    var dynamicColor by mutableStateOf(true)
        private set
    var darkMode by mutableIntStateOf(2) // 0 跟随系统 / 1 浅色 / 2 深色（默认深色，见 theme）
        private set

    /**
     * **上一次选中的**相机设备码（多设备适配：进软件就自动连它）。
     *
     * ★★ 语义是"选中过"而不是"连上过"（用户定版）：以前只在握手成功时才写，
     *   于是"在设置里选了 B、但 B 当时没开机 → 退出 → 下次又回到 A"，
     *   与用户的意图（我选了 B）相反。现在 [ConnectionCenter.switchTo] 在第一行
     *   就落盘，不再看连接结果。
     *
     * 只存设备码不存地址：Wi-Fi 模式下相机地址由 DHCP 分配、会变，而设备码不变。
     * （早先还有一个"优先连接设备（型号|序列号）"下拉框 —— 那是"扫描到就自动连"，
     * 与本项职责重叠且已无调用点，随连接界面重写一并删除。）
     */
    var lastConnectedDevice by mutableStateOf("")
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        downloadTreeUri = prefs.getString(KEY_TREE, "") ?: ""
        seedIndex = prefs.getInt(KEY_SEED, 0)
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true)
        // ★ 深浅色的**默认值是深色（2）**：MD3E 那套观感是按深色给的（用户定版参考图
        //   就是深色）。没主动改过的用户直接看到深色；设置里仍可切回跟随系统/浅色。
        darkMode = prefs.getInt(KEY_DARK, 2)
        lastConnectedDevice = prefs.getString(KEY_LAST_DEVICE, "") ?: ""
    }

    /**
     * 记下"上次选中的那台"（选中 / 连接 / 配对成功时调用；只认设备码）。
     *
     * 与连接是否成功无关 —— 见 [lastConnectedDevice] 的说明。
     */
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

    /**
     * 下载目录的友好显示名（用户反馈：直接摆 content:// URI 不是人话）。
     * tree document id 形如 "primary:sony" → "内部存储/sony"。
     */
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
            downloadTreeUri // 解析不了就原样显示
        }
    }
}
