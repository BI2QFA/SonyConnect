package com.bi2qfa.sonyconnect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.SettingsRepo

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SettingsRepo.updateDownloadTreeUri(uri.toString())
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("返回") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件下载目录", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        SettingsRepo.downloadTreeUri.isBlank() ->
                            "未选择：将保存到应用目录（Android/data/com.bi2qfa.sonyconnect）"
                        else -> SettingsRepo.downloadDirLabel()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Button(onClick = { treePicker.launch(null) }) { Text("选择目录") }
                    if (SettingsRepo.downloadTreeUri.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { SettingsRepo.updateDownloadTreeUri("") }) { Text("恢复默认") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("优先连接设备", style = MaterialTheme.typography.titleMedium)
                Text(
                    "扫描到选中的设备时自动连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreferredDevicePicker()
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "传输完成后自动关闭相机客户端",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = SettingsRepo.autoExitAfterTransfer,
                    onCheckedChange = { SettingsRepo.updateAutoExitAfterTransfer(it) },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("界面颜色风格", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("莫奈取色", Modifier.weight(1f))
                    Switch(
                        checked = SettingsRepo.dynamicColor,
                        onCheckedChange = { SettingsRepo.updateDynamicColor(it) },
                    )
                }
                if (!SettingsRepo.dynamicColor) {
                    Text("调色盘", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            Color(0xFF1565C0), Color(0xFF7E57C2),
                            Color(0xFF2E7D32), Color(0xFFEF6C00),
                        ).forEachIndexed { idx, color ->
                            Box(
                                Modifier.size(40.dp)
                                    .background(color, CircleShape)
                                    .border(
                                        width = if (SettingsRepo.seedIndex == idx) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                    .clickable { SettingsRepo.updateSeed(idx) },
                            )
                        }
                    }
                }
                Text("深浅色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, v) ->
                        FilterChip(
                            selected = SettingsRepo.darkMode == v,
                            onClick = { SettingsRepo.updateDarkMode(v) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text("SonyConnect ${versionNameOf(context)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "配套相机端 SonyConnect 1.0 使用\n开发者：BI2QFA",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PreferredDevicePicker() {
    var expanded by remember { mutableStateOf(false) }
    val selected = SettingsRepo.preferredDevice
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (selected.isBlank()) "未选择" else SettingsRepo.deviceLabel(selected),
                modifier = Modifier.weight(1f),
                color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Icon(
                painterResource(com.bi2qfa.sonyconnect.R.drawable.ic_drop),
                contentDescription = "选择设备",
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("未选择") },
                onClick = {
                    expanded = false
                    SettingsRepo.updatePreferredDevice("")
                },
            )
            if (SettingsRepo.deviceHistory.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider()
            }
            SettingsRepo.deviceHistory.forEach { d ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(SettingsRepo.deviceLabel(d)) },
                    onClick = {
                        expanded = false
                        SettingsRepo.updatePreferredDevice(d)
                    },
                )
            }
        }
    }
}

private fun versionNameOf(context: android.content.Context): String = try {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    pm.versionName ?: ""
} catch (e: Exception) {
    ""
}
