package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bi2qfa.sonyconnect.crypto.Rand
import com.bi2qfa.sonyconnect.ptpip.PtpCodec

/**
 * 本机身份（方案 §5.1）。
 *
 * - **deviceId**：首次启动用 `SecureRandom` 生成 **8 字节**，此后固定不变；
 *   落 prefs 为 16 位小写十六进制。
 * - **guid16**：PTP/IP `Init Command Request` 的 GUID 字段要 16 字节，
 *   由 deviceId(8B) 补 8 个零组成（**不自己发明字段**，用规范原生位置）。
 * - **friendlyName**：`Init Command Request` 里带给相机的本机名，
 *   默认取机型，用户可在设置里改。
 *
 * 与相机端 `IdentityStore` 一一对应（那侧补零成 16B GUID 落到协议字段）。
 */
object IdentityRepo {

    private const val PREFS = "identity"
    private const val KEY_DEVICE_ID = "deviceId"      // 16 hex
    private const val KEY_FRIENDLY = "friendlyName"

    private const val DEVICE_ID_LEN = 8
    private const val GUID_LEN = 16

    lateinit var prefs: SharedPreferences
        private set

    /** 应用上下文（只有取"设备名称"时用得到；Application 里 init 时赋值）。 */
    private lateinit var appContext: Context

    /** 本机 8 字节 deviceId。 */
    @Volatile
    private var deviceIdBytes: ByteArray = ByteArray(DEVICE_ID_LEN)

    /** 16 位小写十六进制表示（配对表主键、排障显示用）。 */
    @Volatile
    var deviceId: String = ""
        private set

    /** 本机友好名（主界面/相机端日志显示）。改它同时落盘。 */
    var friendlyName by mutableStateOf("")
        private set

    /**
     * 默认友好名 = **本机对外显示的那个名字**（如 `Redmi 14R 5G`）。
     *
     * 取值顺序（都是公开 API，不要权限）：
     * 1. `Settings.Global.DEVICE_NAME` —— 系统"设置 → 关于手机 → 设备名称"里那个名字，
     *    用户自己就是这么称呼这台机器的，而且他在系统里改了名我们这边跟着变才对；
     * 2. `bluetooth_name`（`Settings.Secure`）—— 系统"蓝牙 → 设备名称"，多数 ROM 与上者
     *    同源，上面那条读不到时兜一下；
     * 3. `Build.MODEL` 兜底 —— 它常常是**内部代号**（本机实测 `2411DRN47C`），所以放最后。
     *
     * ★ 不用 `Build.MARKETING_NAME`：它在 AOSP 里是 `@SystemApi`，公开 SDK 里根本不存在
     *   （编译期就是 `Unresolved reference`）。
     *
     * ★ 早先这里是 `"SonyConnect " + Build.MODEL`：前缀是**软件名**，对"这是哪台手机"
     *   没有任何信息量；而相机端配对列表要回答的恰恰是"哪台手机"（用户定版：去掉前缀 +
     *   用真机型名）。本机实测 `settings get global device_name` = `Redmi 14R 5G` ✔
     */
    private fun defaultFriendlyName(context: Context): String {
        systemDeviceName(context)?.let { return it }
        val model = runCatching { Build.MODEL }.getOrNull()?.trim().orEmpty()
        return if (model.isEmpty()) "手机" else model
    }

    /** 系统里那个"设备名称"（先 `Global.device_name`，再 `Secure.bluetooth_name`）。 */
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

    /**
     * 历史版本自动生成的默认名（`SonyConnect <机型>`）。
     *
     * 这种名字是**程序写上去的**，不是用户设的（本项目没有改名界面），所以升级时可以直接
     * 换成本机的真名字 —— 不换的话，老用户升上来相机端会一直显示 `SonyConnect 2411DRN47C`
     * 那个旧名（这正是用户实测反馈的那条）。
     */
    private fun isLegacyDefault(name: String): Boolean = name.startsWith("SonyConnect ")

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appContext = context.applicationContext

        var hex = prefs.getString(KEY_DEVICE_ID, null)
        if (hex == null || hex.length != DEVICE_ID_LEN * 2) {
            val fresh = Rand.bytes(DEVICE_ID_LEN)
            hex = PtpCodec.hex(fresh)
            // 首次启动落盘；此后固定不变
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

    /** 8 字节 deviceId 的副本（勿把内部数组交出去）。 */
    fun deviceIdBytes(): ByteArray = deviceIdBytes.copyOf()

    /** 16 字节 GUID：deviceId 后补 8 个零。 */
    fun guid16(): ByteArray {
        val g = ByteArray(GUID_LEN)
        System.arraycopy(deviceIdBytes, 0, g, 0, DEVICE_ID_LEN)
        return g
    }

}
