package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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

    // ===== 2.6：自动传输预览图 + 持久缓存上限（见 PLAN-AutoPreview-Cache.md）=====

    private const val KEY_AUTO_PREVIEW = "autoPreviewFetch"
    private const val KEY_CACHE_LIMIT = "cacheLimitBytes"

    /** 缓存上限默认值（用户定版）：512 MB。 */
    const val DEFAULT_CACHE_LIMIT = 512L * 1024 * 1024

    /** 设置页可选档位（用户定版四档）。 */
    val CACHE_LIMIT_OPTIONS = longArrayOf(
        256L * 1024 * 1024,
        512L * 1024 * 1024,
        1024L * 1024 * 1024,
        2048L * 1024 * 1024,
    )

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

    /**
     * 自动传输预览图（2.6，默认**关**）。
     *
     * 开启后每次连接建立会把整份预热名单交给 [ThumbStore.startAutoFetch]：
     * 一张图"先小图、再大预览"传完再下一张（用户定版策略）。
     */
    var autoPreviewFetch by mutableStateOf(false)
        private set

    /** 图像缓存（小图+预览合计）字节上限，设置页四档可选。 */
    var cacheLimitBytes by mutableLongStateOf(DEFAULT_CACHE_LIMIT)
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
        autoPreviewFetch = prefs.getBoolean(KEY_AUTO_PREVIEW, false)
        cacheLimitBytes = prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT)
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
     * 开关自动传输预览图。
     *
     * ★ 2.6（用户定版）：**开关一改就以当前状态重启传输流程** ——
     *   开 → 立即按全量名单重摆自动队列（已缓存的周期瞬间跳过，只补缺的）；
     *   关 → 立即停掉自动队列、只重摆还缺的小图。
     *   原来只有"关"这一步（清队列），"开"要等下一次连接才生效 —— 传输过程中
     *   反复开关会出现"关了还在传 / 开了没反应"的错位。
     */
    fun updateAutoPreviewFetch(v: Boolean) {
        autoPreviewFetch = v
        prefs.edit().putBoolean(KEY_AUTO_PREVIEW, v).apply()
        ThumbStore.restartAutoFetch()
    }

    /** 上限调小后立刻收敛占用（[ThumbStore.onLimitChanged] 起后台线程修剪）。 */
    fun updateCacheLimitBytes(v: Long) {
        cacheLimitBytes = v
        prefs.edit().putLong(KEY_CACHE_LIMIT, v).apply()
        ThumbStore.onLimitChanged()
    }

    /** 档位的显示文案："512 MB" / "1 GB" / "2 GB"。 */
    fun formatCacheLimit(v: Long): String {
        val gb = v / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB" else "%.1f GB".format(gb)
        } else {
            "${v / (1024 * 1024)} MB"
        }
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
