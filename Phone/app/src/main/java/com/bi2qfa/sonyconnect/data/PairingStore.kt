package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已配对相机表 —— 手机端存一份，相机端另存一份，两侧互为镜像。
 *
 * **这张表就是准入依据**：加解密与双向认证已整体移除，两端在 Init 阶段都只做
 * 一件事 —— 查"这台设备在不在我的表里"。所以"配对"这一步的产物就是一条表记录。
 *
 * 存储：整表序列化成一条 JSON 数组字符串放 prefs（**不引入新依赖**）——
 * 与相机端 `SJson` 单条字符串策略对称。解析对未知字段宽容，旧版本留下的记录
 * （含已废弃的 key/suite/enc 字段）照样读得出来，不需要重新配对。
 */
object PairingStore {

    private const val PREFS = "pairing"
    private const val KEY_TABLE = "pairedCameras"

    lateinit var prefs: SharedPreferences
        private set

    /** 已配对相机（Compose 状态；按 lastSeenAt 倒序，最近用的在前）。 */
    var cameras by mutableStateOf<List<PairedCamera>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cameras = decode(prefs.getString(KEY_TABLE, null))
    }

    /** 一台已配对相机。字段与 §5.2 表格一一对应。 */
    data class PairedCamera(
        /** 对端 deviceId（16 hex）——主键。 */
        val peerDeviceId: String,
        /** 对端友好名（相机：`ILCE-6300 05186914`）。 */
        val peerName: String,
        val peerModel: String = "",
        val peerSerial: String = "",
        val pairedAt: Long = 0L,
        val lastSeenAt: Long = 0L,
        val lastIp: String = "",
        val lastPort: Int = 0,
        /** 对端协议代次，便于将来演进。 */
        val protoVersion: Int = 0,
    ) {
        /**
         * **显示用型号**（不含序列号）。
         *
         * ★ 为什么不直接用 [peerModel]：老记录里它可能是**空**的（配对成功那一刻
         *   `DEVICE_INFO` 没拉到，只落了友好名），也可能是**组合串**——相机端有的版本
         *   会把友好名写成 "ILCE-6300 SN:05186914"，各处兜底到 peerName 时就会把
         *   整串当型号顶出来（用户实测：相机信息页"型号"一行显示组合串、"序列号"
         *   一行却是 "—"）。所以这里统一做**拆分推导**：先取 peerModel，
         *   空了从 peerName 里把型号抠出来；peerModel 若自带 SN 就顺手拆掉。
         *   组合串 "ILCE-6300 SN:05186914" 的**完整形态**只在"辨别设备"的场合
         *   （绑定设备列表 / 设置页配对列表）由调用方自己拼。
         */
        val displayModel: String
            get() = when {
                peerModel.isNotBlank() -> splitModelSerial(peerModel).first
                peerName.isNotBlank() -> splitModelSerial(peerName).first
                else -> ""
            }

        /**
         * **显示用序列号**。peerSerial 为空时从 peerModel / peerName 的组合串里
         * 拆出来（拆法见 [splitModelSerial]）；都没有就给空串，由 UI 显示 "—"。
         */
        val displaySerial: String
            get() = peerSerial.ifBlank {
                splitModelSerial(if (peerModel.isNotBlank()) peerModel else peerName).second ?: ""
            }

        // data class 含 ByteArray：equals/hashCode 需按其内容比较，否则去重/查找会失效
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

    // ============================================================
    // 查询
    // ============================================================

    fun all(): List<PairedCamera> = cameras

    fun find(peerDeviceId: String): PairedCamera? =
        cameras.firstOrNull { it.peerDeviceId.equals(peerDeviceId, ignoreCase = true) }

    fun contains(peerDeviceId: String): Boolean = find(peerDeviceId) != null

    /** 最近一次成功连接的相机（自动连接优先目标）。 */
    fun mostRecent(): PairedCamera? = cameras.firstOrNull()

    // ============================================================
    // 变更
    // ============================================================

    /** 新增或覆盖（按 peerDeviceId）。已存在则保留原 pairedAt。 */
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

    /** 解除配对（手机端主动）。返回是否真的删掉了。 */
    fun remove(peerDeviceId: String): Boolean {
        val next = cameras.filterNot { it.peerDeviceId.equals(peerDeviceId, ignoreCase = true) }
        if (next.size == cameras.size) return false
        commit(next)
        return true
    }

    /** 连接成功后刷新"最近可见"信息（不改密钥）。 */
    fun touch(peerDeviceId: String, ip: String, port: Int, now: Long = System.currentTimeMillis()) {
        val cur = find(peerDeviceId) ?: return
        upsert(cur.copy(lastSeenAt = now, lastIp = ip, lastPort = port))
    }

    /** 记录对端协议代次（首次连接后发现与配对时不同步时校正）。 */
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

    // ============================================================
    // 序列化（整表一条 JSON 数组字符串；字段名短）
    // ============================================================

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

/**
 * 从一段可能被污染的字符串里拆出 **(型号, 序列号?)**。
 *
 * 相机端不同版本写过两种格式（用户实测踩到的就是它们）：
 * - `ILCE-6300 SN:05186914` —— 友好名 / model 字段带 `SN:` 前缀；
 * - `ILCE-6300 05186914` —— 友好名直接把序列号缀在后面（配对表注释里的老格式）。
 *
 * 识别规则（**宁可少拆不可错拆**）：
 * - 先认 `SN:`/`SN：` 前缀（忽略大小写）；
 * - 再认"纯数字且 ≥6 位"的尾巴 —— 序列号是这个形态；型号（`ILCE-6300`、
 *   `SonyConnect ILCE-6300`）带字母和连字符，永远不会被误当成序列号。
 *
 * @return first = 型号（去掉 SN 尾巴后的部分），second = 拆出来的序列号（没有则 null）
 */
internal fun splitModelSerial(raw: String): Pair<String, String?> {
    val s = raw.trim()
    if (s.isEmpty()) return "" to null
    SN_PREFIX_RX.find(s)?.let {
        return it.groupValues[1].trim().trimEnd('-', '·', ' ') to it.groupValues[2]
    }
    TRAILING_SERIAL_RX.find(s)?.let {
        return it.groupValues[1].trim() to it.groupValues[2]
    }
    return s to null
}

/** `ILCE-6300 SN:05186914` / `ILCE-6300 SN：05186914`（SN 前缀形态）。 */
private val SN_PREFIX_RX =
    Regex("""^(.*?)\s*SN[:：]\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)

/** `ILCE-6300 05186914`（尾部纯数字 ≥6 位的形态）。 */
private val TRAILING_SERIAL_RX = Regex("""^(.*\S)\s+(\d{6,})\s*$""")
