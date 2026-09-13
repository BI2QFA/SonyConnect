package com.bi2qfa.sonyconnect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bi2qfa.sonyconnect.data.IdentityRepo
import com.bi2qfa.sonyconnect.ui.theme.useDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.ui.components.Chevron
import com.bi2qfa.sonyconnect.ui.components.GroupCard
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.RowIconAction
import com.bi2qfa.sonyconnect.ui.components.Segment
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow
import com.bi2qfa.sonyconnect.ui.components.SwitchRow
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Seeds








enum class SettingsPage(val title: String) {
    Hub("设置"),
    Cameras("已配对相机"),
    Files("文件保存位置"),
    Appearance("外观"),
    About("关于"),
    
    Reward("打赏"),
}










@Composable
fun SettingsScreen(
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    onOpenPairing: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        when (page) {
            SettingsPage.Hub -> SettingsHub(onNavigate)
            SettingsPage.Cameras -> CamerasPage(onOpenPairing)
            SettingsPage.Files -> FilesPage()
            SettingsPage.Appearance -> AppearancePage()
            
            SettingsPage.About -> AboutPage(onNavigate)
            SettingsPage.Reward -> RewardPage()
        }
    }
}







@Composable
private fun SettingsHub(onNavigate: (SettingsPage) -> Unit) {
    
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "相机",
            support = "已配对相机、配对新相机",
            leading = { IconCircle(R.drawable.ic_camera, IconTints.Blue) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Cameras) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "文件",
            support = "文件保存位置",
            leading = { IconCircle(R.drawable.ic_nav_files, IconTints.Amber) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Files) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 2,
            title = "外观",
            support = "跟随系统主题色、调色盘、深浅色",
            leading = { IconCircle(R.drawable.ic_palette_dots, IconTints.Pink) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Appearance) },
        )
        SettingsRow(
            index = 1, count = 2,
            title = "关于",
            support = "版本信息、开发者、打赏",
            leading = { IconCircle(R.drawable.ic_info, IconTints.Cyan) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.About) },
        )
    }
}


@Composable
private fun CamerasPage(onOpenPairing: () -> Unit) {
    val context = LocalContext.current
    
    var pendingUnpair by remember { mutableStateOf<String?>(null) }
    val cameras = PairingStore.cameras

    pendingUnpair?.let { id ->
        val cam = PairingStore.find(id)
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("解除配对？") },
            text = {
                Text(
                    
                    "将解除与「${if (cam != null) labelOf(cam) else id}」的配对。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnpair = null
                    ConnectionCenter.unpair(id)
                }) {
                    Text("解除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpair = null }) { Text("取消") }
            },
        )
    }

    if (cameras.isEmpty()) {
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = "尚无已配对相机",
                support = "在相机上进入配对模式",
                leading = { IconCircle(R.drawable.ic_camera, IconTints.Pink) },
            )
        }
    } else {
        SettingsGroup {
            cameras.forEachIndexed { idx, cam ->
                val connectedGuid = ConnectionCenter.camera?.guidHex
                val connected = cam.peerDeviceId.equals(connectedGuid, ignoreCase = true)
                
                
                
                
                
                
                
                
                
                Segment(
                    index = idx, count = cameras.size,
                    onClick = { ConnectionCenter.switchTo(context, cam.peerDeviceId) },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconCircle(
                            R.drawable.ic_camera,
                            if (connected) IconTints.Green else IconTints.Blue,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            
                            
                            
                            Text(
                                cam.peerModel.ifBlank { cam.peerName }.ifBlank { "未知相机" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            
                            
                            
                            
                            
                            
                            Text(
                                buildString {
                                    append(if (connected) "已连接" else "未连接")
                                    if (cam.peerSerial.isNotBlank()) {
                                        append(" · SN:").append(cam.peerSerial)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val code = ConnectionCenter.shortCode(cam.peerDeviceId)
                            if (code.isNotEmpty()) {
                                Text(
                                    "设备码 $code",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        RowIconAction(
                            R.drawable.ic_trash,
                            "解除配对",
                            onClick = { pendingUnpair = cam.peerDeviceId },
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "配对新相机",
            support = "输入配对码与相机配对",
            leading = { IconCircle(R.drawable.ic_add, IconTints.Green) },
            trailing = { Chevron() },
            onClick = onOpenPairing,
        )
    }
}


@Composable
private fun FilesPage() {
    val context = LocalContext.current
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

    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "文件下载目录",
            support = when {
                SettingsRepo.downloadTreeUri.isBlank() ->
                    "当前：应用目录（Android/data/com.bi2qfa.sonyconnect）"
                else -> "当前：" + SettingsRepo.downloadDirLabel()
            },
            leading = { IconCircle(R.drawable.ic_nav_files, IconTints.Amber) },
            trailing = { Chevron() },
            onClick = { treePicker.launch(null) },
        )
    }
    if (SettingsRepo.downloadTreeUri.isNotBlank()) {
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = "恢复默认目录",
                support = "恢复到默认下载目录，已经下载的文件不会被移动或删除",
                leading = { IconCircle(R.drawable.ic_refresh, IconTints.Cyan) },
                onClick = { SettingsRepo.updateDownloadTreeUri("") },
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearancePage() {
    SettingsGroup {
        SwitchRow(
            index = 0, count = 1,
            title = "跟随系统主题色",
            support = "可用时使用设备动态配色",
            checked = SettingsRepo.dynamicColor,
            onCheckedChange = { SettingsRepo.updateDynamicColor(it) },
        )
    }
    if (!SettingsRepo.dynamicColor) {
        
        
        SettingsGroup {
            SettingsRow(
                index = 0, count = 2,
                title = "调色盘",
                support = SeedNames.getOrElse(SettingsRepo.seedIndex) { "" },
                leading = { IconCircle(R.drawable.ic_palette_dots, IconTints.Pink) },
            )
            Segment(index = 1, count = 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 76.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Seeds.forEachIndexed { idx, seed ->
                        SeedSwatch(
                            color = seed.swatch,
                            selected = SettingsRepo.seedIndex == idx,
                            onClick = { SettingsRepo.updateSeed(idx) },
                        )
                    }
                }
            }
        }
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 2,
            title = "深浅色",
            support = when (SettingsRepo.darkMode) {
                1 -> "当前：浅色"
                2 -> "当前：深色"
                else -> "当前：跟随系统"
            },
            leading = { IconCircle(R.drawable.ic_contrast, IconTints.Cyan) },
        )
        Segment(index = 1, count = 2) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)
                    options.forEachIndexed { idx, (label, value) ->
                        SegmentedButton(
                            selected = SettingsRepo.darkMode == value,
                            onClick = { SettingsRepo.updateDarkMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
        }
    }
}


@Composable
private fun AboutPage(onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    GroupCard {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("SonyConnect", style = MaterialTheme.typography.headlineSmall)
            Text(
                "版本 ${versionNameOf(context)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "配套相机端 SonyConnect 2.0 使用\n开发者：BI2QFA",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            
            
            
            Text(
                "本机设备码：${ConnectionCenter.shortCode(IdentityRepo.deviceId)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "打赏",
            
            support = "给开发者买杯咖啡吧",
            leading = { IconCircle(R.drawable.ic_reward, IconTints.Amber) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Reward) },
        )
    }
}








@Composable
private fun RewardPage() {
    val dark = useDarkTheme()
    Column(Modifier.fillMaxSize()) {
        SettingsGroup {
            GroupCard {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painterResource(
                            if (dark) R.drawable.reward_qr_dark else R.drawable.reward_qr_light
                        ),
                        contentDescription = "赞赏码",
                        
                        
                        
                        
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                    Text(
                        "给开发者买杯咖啡吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


@Composable
private fun SeedSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 30.dp else 34.dp)
                .background(color, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else Modifier
                ),
        )
    }
}

private val SeedNames = listOf("蓝", "紫", "绿", "琥珀")







private fun labelOf(cam: PairingStore.PairedCamera): String {
    val model = cam.peerModel.ifBlank { cam.peerName }
    if (model.isBlank()) return cam.peerDeviceId
    return if (cam.peerSerial.isNotBlank()) "$model SN:${cam.peerSerial}" else model
}

private fun versionNameOf(context: android.content.Context): String = try {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    pm.versionName ?: ""
} catch (e: Exception) {
    ""
}
