package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.protocol.Scanner

@Composable
fun DisconnectedScreen() {
    val scanning = ConnectionCenter.scanning
    val results = ConnectionCenter.scanResults
    val error = ConnectionCenter.lastError
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        ConnectionCenter.startAutoScan(context)
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "未连接设备",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            "请确保手机与相机处于同一网络\n（相机热点或同一局域网内）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        val dots = androidx.compose.runtime.produceState(1) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                value = value % 3 + 1
            }
        }
        Button(
            onClick = { ConnectionCenter.startAutoScan(context, force = true) },
            enabled = !scanning,
        ) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("扫描中" + ".".repeat(dots.value))
            } else {
                Text("扫描设备")
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (results.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("发现设备 · 点击选择要连接的相机", style = MaterialTheme.typography.titleMedium)

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(results) { c ->
                    Card(
                        onClick = { ConnectionCenter.connectTo(context, c.host) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(c.model, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(c.host)
                                    if (c.serial.isNotBlank()) append(" · SN:").append(c.serial)
                                    append(" · 电量 ")
                                    append(if (c.batteryPct >= 0) "${c.batteryPct}%" else "—")
                                    if (c.lens.isNotBlank()) append(" · ").append(c.lens)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
