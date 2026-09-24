package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.ui.components.SectionHeader
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow

/**
 * 相机详情（点主页那张相机卡进来）。
 *
 * 用户定版的清单：**型号 / 序列号 / 地区 / 固件版本 / 安卓版本 / Java API 版本**。
 * 主页那边只留电量，其余都收到这里 —— 所以这一页的职责就是把"这台相机到底是什么"
 * 一次说清，不再分层。
 *
 * 版式：**字段名在左、值在右**（`title` + `trailing`），就是 Android"关于手机"和
 * 索尼相机自身菜单里那一套。这里刻意**不用** `IconCircle` 领头 —— 那是"菜单入口"的
 * 记号（表示点进去还有东西），而这一页是**规格表**，每行都没有下一层，
 * 给它配上箭头/圆片反而会让人以为还能点。
 *
 * 已连接时值来自相机 `OP_DEVICE_INFO`；未连接时型号/序列号从**配对表**兜底
 * （其余项没有本地来源，显示 "—"）。和主页同一条纪律：宁可显示 "—" 也不留空、
 * 更不编造。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraDetailScreen() {
    val info = DeviceStore.info
    // 未连接时的兜底来源：本次目标那台（本次要连的，或上次连过的那台）
    val target = ConnectionCenter.displayGuid()?.let { PairingStore.find(it) }

    // ★ 型号 / 序列号走 [PairingStore.PairedCamera.displayModel/displaySerial] 推导，
    //   不再直接读 peerModel/peerName：老记录里型号可能是空的、友好名可能带着
    //   "ILCE-6300 SN:05186914" 组合串 —— 直接兜底到 peerName 就是用户截图里
    //   "型号一行显示组合串、序列号一行却是 —"的成因。两行从此各归各位。
    val model = info?.model?.takeIf { it.isNotBlank() }
        ?: target?.displayModel?.takeIf { it.isNotBlank() }
        ?: "—"
    val serial = info?.serial?.takeIf { it.isNotBlank() }
        ?: target?.displaySerial?.takeIf { it.isNotBlank() }
        ?: "—"

    val region = info?.region?.takeIf { it.isNotBlank() } ?: "—"
    val firmware = info?.firmware?.takeIf { it.isNotBlank() } ?: "—"
    val apiVersion = info?.apiVersion?.takeIf { it.isNotBlank() } ?: "—"

    // 安卓版本按用户要求**带上 SDK**（"android version(包括sdk版本)"）。
    // 两个来源任何一个缺失都不写半个括弧，直接落到有值的那个形态。
    val androidVersion = info?.androidVersion?.takeIf { it.isNotBlank() }
    val androidSdk = info?.androidSdk ?: -1
    val androidText = when {
        androidVersion != null && androidSdk > 0 -> "$androidVersion（SDK $androidSdk）"
        androidVersion != null -> androidVersion
        androidSdk > 0 -> "SDK $androidSdk"
        else -> "—"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 这一页没有悬浮导航（从主页推进来的二级页，导航让位），所以只留常规收尾
            .padding(bottom = 28.dp),
    ) {
        SectionHeader("设备")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 4,
                title = "型号",
                trailing = { SpecValue(model) },
            )
            SettingsRow(
                index = 1, count = 4,
                title = "序列号",
                trailing = { SpecValue(serial) },
            )
            SettingsRow(
                index = 2, count = 4,
                title = "地区",
                trailing = { SpecValue(region) },
            )
            SettingsRow(
                index = 3, count = 4,
                title = "固件版本",
                trailing = { SpecValue(firmware) },
            )
        }

        SectionHeader("系统")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 2,
                title = "安卓版本",
                trailing = { SpecValue(androidText) },
            )
            SettingsRow(
                index = 1, count = 2,
                title = "Java API 版本",
                trailing = { SpecValue(apiVersion) },
            )
        }

        // 底部那条说明按用户要求删掉了（"地区…由相机在连接时上报"这一段）
        Box(Modifier.padding(bottom = 8.dp))
    }
}

/** 规格表右侧的值。样式与 `ListItem` 的标题同号，读起来才是"标签—值"两列。 */
@Composable
private fun SpecValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
