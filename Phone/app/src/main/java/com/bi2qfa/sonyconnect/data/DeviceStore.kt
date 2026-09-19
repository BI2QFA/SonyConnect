package com.bi2qfa.sonyconnect.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/**
 * 相机设备信息（主界面显示）——PTP/IP 版。
 *
 * 与旧版差异：模型从 `protocol.ConnectClient.CameraInfo` 换成 [CameraInfo]（独立数据类，
 * 不再依赖将被删除的协议客户端），增量更新入口从 `applyHeartbeat(batteryPct, lens)`
 * 换成 [applyPing]——新协议 `OP_PING` 一次往返同时带回电量与"是否装镜头"，
 * 不再有独立的 lens 轮询。
 */
object DeviceStore {

    var info by mutableStateOf<CameraInfo?>(null)
        private set

    /**
     * 全量覆盖（`OP_DEVICE_INFO` 结果；传 null 表示清空，等价 [clear]）。
     *
     * ★★ **电量与镜头必须保住**：相机那份 JSON 里根本没有这两个字段
     *   （只有 `name/model/serial/firmware/region/apiVersion/androidVersion/androidSdk/
     *   sdTotal/sdUsed/mode/ssid`，相机端还有测试专门盯住
     *   "DEVICE_INFO 不再重复下发实时字段"），它们是 `OP_PING` 的产物。
     *   早先这里直接 `info = i` 整块替换 —— 会把"连上那一瞬刚补的实时信息"清成未知，
     *   用户看到的就是**连上之后还要等几秒才出电量/镜头**（等的是第一个 3 秒心跳）。
     *   所以这里按字段合并：快照里没有（或仍是未知/空）的实时字段保留旧值。
     *
     * ★ **固定信息同理**（地区 / Java API 版本 / 安卓版本 / SD 容量）：连接流程是
     *   "先落 placeholder、再拉全量"，而 placeholder 这几项按定义就是空的。
     *   不保的话每次重连都会把详情页和存储卡闪成 "—"；更糟的是全量拉取失败时
     *   （相机忙、这一次请求超时）**已经读到过的地区会永久丢掉**，而这几项在
     *   手机侧**没有任何本地兜底**（型号/SN 还能从配对表补）。
     *   同一个 guid 下这些值不可能变，保留旧值一定比清空正确。
     *
     * 换了相机（guid 不同）就绝不继承：那是上一台的读数，比"未知"更坏。
     */
    fun apply(i: CameraInfo?) {
        if (i == null) {
            info = null
            return
        }
        val cur = info
        info = if (cur == null || !cur.guid.equals(i.guid, ignoreCase = true)) {
            i
        } else {
            i.copy(
                batteryPct = if (i.batteryPct in 0..100) i.batteryPct else cur.batteryPct,
                batteryRemainMin = if (i.batteryRemainMin >= 0) i.batteryRemainMin
                else cur.batteryRemainMin,
                lens = if (i.lens.isNotBlank()) i.lens else cur.lens,
                region = if (i.region.isNotBlank()) i.region else cur.region,
                apiVersion = if (i.apiVersion.isNotBlank()) i.apiVersion else cur.apiVersion,
                androidVersion = if (i.androidVersion.isNotBlank()) i.androidVersion
                else cur.androidVersion,
                androidSdk = if (i.androidSdk >= 0) i.androidSdk else cur.androidSdk,
                sdTotalBytes = if (i.sdTotalBytes > 0) i.sdTotalBytes else cur.sdTotalBytes,
                sdUsedBytes = if (i.sdUsedBytes >= 0) i.sdUsedBytes else cur.sdUsedBytes,
            )
        }
    }

    /**
     * 增量：只更新电量与镜头装态，其余字段保持。
     * 由 3s 心跳 `OP_PING` 驱动；两者都无变化时不动 state（避免无谓重组）。
     */
    fun applyPing(batteryPct: Int, lens: String) {
        val cur = info ?: return
        val next = cur.applyPing(batteryPct, lens)
        if (next != cur) info = next
    }

    fun clear() {
        info = null
    }
}
