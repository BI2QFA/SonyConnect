package com.bi2qfa.sonyconnect.data

import org.json.JSONObject

/**
 * 相机设备信息（PTP/IP 版）—— **取代 `protocol.ConnectClient.CameraInfo`**。
 *
 * 旧模型有 18 个字段，其中 6 个（password / ip / ftpPort / connectPort /
 * thumbState / thumbDone / thumbTotal / infoSource）是旧 FTP + 私有 TCP 协议的**路径性残留**：
 * FTP 端口、明文热点密码、缩略图队列游标都随协议一起废弃，UI 从未消费。
 * 新模型只保留 UI 真实消费的业务字段 + PTP/IP 连接标识。
 *
 * ## 数据来源（三条路，互不覆盖）
 * | 字段 | 来源 |
 * |---|---|
 * | [guid] / [name] / [protoPort] | 连接上下文（Init Command Ack + 配对表），手机侧本地填写 |
 * | model / serial / firmware / region / apiVersion / androidVersion / androidSdk / sdTotalBytes / sdUsedBytes / mode / ssid | 相机 `OP_DEVICE_INFO` 厂商 JSON |
 * | batteryPct / lens 有效性 | 相机 `OP_PING` 增量（3s 心跳，见 [applyPing]） |
 *
 * ★ **固定信息 vs 实时信息**：`OP_PING` 只带电量与镜头两个真会变的量；其余
 * 一律随 `OP_DEVICE_INFO` 一个会话拉一次。地区 / 安卓版本 / Java API 版本属于
 * 相机上一辈子不变的常量，SD 容量口径上会变但与其余字段同为"一会话一快照"的
 * 语义（重连即刷新）—— 不为它单开一条实时通道（用户定版："不用实时更新"）。
 *
 * ## 未知值约定
 * 电量与剩余分钟统一用 **-1** 表示未知（与相机端 `DEVICE_INFO` 的缺省一致），
 * UI 侧按 `>= 0` 判有效；不要用 0 当未知（0% 是合法电量）。
 * 字节数（SD 容量/已用）同理用 [UNKNOWN_SIZE]，判定统一走 [sdKnown]。
 * 字符串（地区/版本等）用**空串**表示未读到，UI 侧显示 "—"。
 */
data class CameraInfo(
    /** 相机设备标识，16 位小写 hex——配对表主键，等同配对记录里的 `peerDeviceId`。 */
    val guid: String,
    /** 相机友好名（Init Command Ack 回；如 `SonyConnect ILCE-6300`）。 */
    val name: String,
    val model: String,
    val serial: String,
    val firmware: String,
    /**
     * 地区（backup region）。相机端取自 `sys.dest`（A6300 上唯一可用的地区键），
     * 现读作 `COMMON` / `CHINA`；空串 = 未读到。
     */
    val region: String,
    /**
     * 相机侧 Java API 版本（相机端 `version.platform`，如 `2.3`）；空串 = 未读到。
     *
     * ★ 相机上这个值是"整串"下发的（不劈成硬件版本/API 版本两段），所以这里原样收着、
     *   原样显示 —— 手机端不替相机解释这个格式。
     */
    val apiVersion: String,
    /** 相机系统安卓版本（如 `4.1.2`）；空串 = 未读到。 */
    val androidVersion: String,
    /** 相机系统安卓 SDK 级别（如 16）；-1 = 未读到。 */
    val androidSdk: Int,
    /** 相机内 SD 卡总容量（字节）；-1 = 未读到。 */
    val sdTotalBytes: Long,
    /** 相机内 SD 卡已用空间（字节）；-1 = 未读到。 */
    val sdUsedBytes: Long,
    /** 镜头名；空串 = 未装镜头或读取失败。 */
    val lens: String,
    /** 电量百分比 0..100；-1 = 未知。 */
    val batteryPct: Int,
    /** 剩余可拍摄时间（分钟）；-1 = 未知。 */
    val batteryRemainMin: Int,
    /** 相机自身工作模式，如 `hotspot` / `wifi`。 */
    val mode: String,
    /** 相机热点 SSID（展示用；新协议不再下发明文密码）。 */
    val ssid: String,
    /** 协议端口（控制 + 事件连接；UDP 探测同号）。 */
    val protoPort: Int,
    /** 文件端口（数据连接）。P5 双端口时与 [protoPort] 不同，P1 允许相同。 */
    val filePort: Int,
) {

    /**
     * 存储卡用量是否可信。总容量与已用必须同时有效才算数 ——
     * 只给一个的话比例算不出来，界面上就该显示"—"而不是画一条假的进度条。
     */
    val sdKnown: Boolean get() = sdTotalBytes > 0 && sdUsedBytes in 0..sdTotalBytes

    /** 已用比例 0..1；未知时为 0（调用方先看 [sdKnown]）。 */
    val sdUsedFraction: Float
        get() = if (sdKnown) (sdUsedBytes.toFloat() / sdTotalBytes).coerceIn(0f, 1f) else 0f

    /**
     * 心跳增量合并——`OP_PING` 是**唯一的实时包**，一次往返带回电量与镜头名
     * （实测数据都在这里，静态的型号/序列号/固件/地区/版本/容量只在连接时拉一次
     * `DEVICE_INFO`）。
     *
     * 语义：
     * - 电量：仅当对方给出合法值（0..100）才覆盖，-1 视为"本次没读到"，保留旧值；
     * - 镜头：**以本包为准**（空串 = 未装镜头或读取失败 → 清空）。
     *   相机端 `DeviceInfo.getLens` 带 2.5s TTL 缓存，3s 一次的 PING 每次都拿到新值，
     *   所以换镜头几秒内就会反映到界面。
     */
    fun applyPing(batteryPct: Int, lens: String): CameraInfo = copy(
        batteryPct = if (batteryPct in 0..100) batteryPct else this.batteryPct,
        lens = lens,
    )

    companion object {

        /** 未知数值哨兵（电量 / 剩余分钟）。 */
        const val UNKNOWN: Int = -1

        /** 未知**字节数**哨兵（SD 容量 / 已用）。与 [UNKNOWN] 同值，但语义不同，分开写。 */
        const val UNKNOWN_SIZE: Long = -1L

        /**
         * 由相机 `OP_DEVICE_INFO` 的厂商 JSON 构造。
         *
         * JSON 契约（字段名与相机端 `PtpCameraHandler.deviceInfo()` 逐字对齐，
         * 缺省即未知/空）：
         * ```json
         * {"name":"SonyConnect ILCE-6300","model":"ILCE-6300","serial":"1234567",
         *  "firmware":"3.10","region":"CHINA","apiVersion":"2.3",
         *  "androidVersion":"4.1.2","androidSdk":16,"sdTotal":31914983424,"sdUsed":12884901888,
         *  "mode":"hotspot","ssid":"DIRECT-xxxx:ILCE-6300"}
         * ```
         *
         * ★ `sdTotal` / `sdUsed` / `androidSdk` 是**数值**（相机端用 `SJson.member`
         *   的 long 重载写出去），所以这里必须用 `optLong`/`optInt` 读。相机端已经
         *   把「未知」编码成 `-1`，因此缺省值同样取 `-1` —— 两边一致，
         *   不必区分"键不存在"和"键为 -1"。
         *
         * [guid] / [name] / [protoPort] / [filePort] 不属于相机上报内容，
         * 由手机侧连接上下文传入（[guid] 来自配对表，端口来自探测应答）。
         *
         * @return json 为 null（请求失败）时返回 null，由调用方决定是否保留旧值
         */
        fun fromDeviceInfo(
            json: JSONObject?,
            guid: String,
            fallbackName: String,
            protoPort: Int,
            filePort: Int,
        ): CameraInfo? {
            if (json == null) return null
            val reportedName = json.optString("name", "")
            // ★ 相机端有的版本把序列号拼进 model（"ILCE-6300 SN:05186914"）而 serial
            //   留空 —— 在**数据入口**拆开，下游（详情页/配对表/下载目录命名）就永远是
            //   干净的两个字段，不存在"显示层再猜一遍"的问题（拆法见 [splitModelSerial]）。
            val (model, serialFromModel) = splitModelSerial(json.optString("model", ""))
            val serial = json.optString("serial", "").ifBlank { serialFromModel ?: "" }
            return CameraInfo(
                guid = guid,
                name = reportedName.ifBlank { fallbackName },
                model = model,
                serial = serial,
                firmware = json.optString("firmware", ""),
                region = json.optString("region", ""),
                apiVersion = json.optString("apiVersion", ""),
                androidVersion = json.optString("androidVersion", ""),
                androidSdk = json.optInt("androidSdk", UNKNOWN),
                sdTotalBytes = json.optLong("sdTotal", UNKNOWN_SIZE),
                sdUsedBytes = json.optLong("sdUsed", UNKNOWN_SIZE),
                lens = json.optString("lens", ""),
                batteryPct = json.optInt("batteryPct", UNKNOWN),
                batteryRemainMin = json.optInt("batteryRemainMin", UNKNOWN),
                mode = json.optString("mode", ""),
                ssid = json.optString("ssid", ""),
                protoPort = protoPort,
                filePort = filePort,
            )
        }

        /**
         * 尚未取到 `DEVICE_INFO` 时的占位——只有连接标识，业务字段留空。
         * 用途：连上瞬间就让 UI 有名字可显示，随后被 [fromDeviceInfo] 覆盖。
         */
        fun placeholder(
            guid: String,
            name: String,
            protoPort: Int,
            filePort: Int,
        ): CameraInfo = CameraInfo(
            guid = guid,
            name = name,
            model = "",
            serial = "",
            firmware = "",
            region = "",
            apiVersion = "",
            androidVersion = "",
            androidSdk = UNKNOWN,
            sdTotalBytes = UNKNOWN_SIZE,
            sdUsedBytes = UNKNOWN_SIZE,
            lens = "",
            batteryPct = UNKNOWN,
            batteryRemainMin = UNKNOWN,
            mode = "",
            ssid = "",
            protoPort = protoPort,
            filePort = filePort,
        )
    }
}
