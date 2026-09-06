package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.ui.SlimProgress
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.DeviceStore

private val ConnectedGreen = Color(0xFF2FA757)

@Composable
fun HomeScreen() {
    val info = DeviceStore.info
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    info?.model?.ifBlank { "Sony 相机" } ?: "Sony 相机",
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (!info?.serial.isNullOrBlank()) {
                    Text(
                        "序列号  ${info!!.serial}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!info?.firmware.isNullOrBlank()) {
                    Text(
                        "固件版本  ${info!!.firmware}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BatteryRow(info?.batteryPct ?: -1, info?.batteryRemainMin ?: -1)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "镜头",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    info?.lens?.ifBlank { null } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {

                    Box(
                        Modifier.size(10.dp).background(ConnectedGreen, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("已连接", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    buildString {
                        append("方式：")
                        append(when (info?.mode) { "wifi" -> "Wi-Fi 网络"; else -> "相机热点" })
                        if (!info?.ssid.isNullOrBlank()) append(" · ").append(info!!.ssid)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = { ConnectionCenter.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text("断开连接", modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun BatteryRow(pct: Int, remainMin: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (pct >= 0) "电量 $pct%" else "电量 —",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (pct >= 0) {
            SlimProgress(fraction = pct.coerceIn(0, 100) / 100f)
        }
        if (remainMin > 0) {
            Text(
                "约可拍摄 $remainMin 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
