package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.ui.FloatingNavInset
import com.bi2qfa.sonyconnect.ui.SlimProgress
import com.bi2qfa.sonyconnect.ui.components.Chevron
import com.bi2qfa.sonyconnect.ui.components.ExpressiveButton
import com.bi2qfa.sonyconnect.ui.components.GroupCard
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.SectionHeader
import com.bi2qfa.sonyconnect.ui.components.Segment
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow
import com.bi2qfa.sonyconnect.ui.formatCapacity
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Motion


















@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(onOpenCameraDetail: () -> Unit) {
    val context = LocalContext.current
    val info = DeviceStore.info
    val state = ConnectionCenter.state
    val connecting = ConnectionCenter.connecting
    val connected = state == ConnectionCenter.State.CONNECTED
    
    val target = ConnectionCenter.displayGuid()?.let { PairingStore.find(it) }

    val model = info?.model?.takeIf { it.isNotBlank() }
        ?: target?.peerModel?.takeIf { it.isNotBlank() }
        ?: target?.peerName?.takeIf { it.isNotBlank() }
        ?: "Sony 相机"

    val batteryPct = info?.batteryPct ?: -1
    val remainMin = info?.batteryRemainMin ?: -1

    
    
    val sdKnown = info?.sdKnown == true
    val sdTotal = if (sdKnown) info!!.sdTotalBytes else 0L
    val sdUsed = if (sdKnown) info!!.sdUsedBytes else 0L

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            
            .padding(bottom = FloatingNavInset),
    ) {
        
        SectionHeader("相机")
        GroupCard(onClick = onOpenCameraDetail) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model,
                        style = MaterialTheme.typography.headlineMedium,
                        
                        
                        
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Chevron()
                }
                
                
                
                
                
                
                
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "电量",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Box(Modifier.width(8.dp))
                    
                    
                    
                    
                    
                    
                    
                    
                    Text(
                        if (batteryPct >= 0) "$batteryPct%" else "—",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .alignByBaseline()
                            .graphicsLayer {
                                translationY = if (batteryPct >= 0) 0f else 4.dp.toPx()
                            },
                    )
                    if (remainMin > 0) {
                        Box(Modifier.width(10.dp))
                        Text(
                            "约可拍摄 $remainMin 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            
                            
                            
                            
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .alignByBaseline(),
                        )
                    }
                }
                if (batteryPct >= 0) {
                    SlimProgress(fraction = batteryPct.coerceIn(0, 100) / 100f)
                }
            }
        }

        
        SectionHeader("镜头")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = info?.lens?.takeIf { it.isNotBlank() } ?: "—",
                leading = { IconCircle(R.drawable.ic_lens, IconTints.Purple) },
            )
        }

        
        SectionHeader("存储卡")
        GroupCard {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row {
                    Stat("已用", if (sdKnown) formatCapacity(sdUsed) else "—",
                        Modifier.weight(1f))
                    Stat("剩余", if (sdKnown) formatCapacity(sdTotal - sdUsed) else "—",
                        Modifier.weight(1f))
                    Stat("总容量", if (sdKnown) formatCapacity(sdTotal) else "—",
                        Modifier.weight(1f))
                }
                if (sdKnown) {
                    SlimProgress(fraction = info!!.sdUsedFraction)
                }
            }
        }

        
        SectionHeader("连接")
        
        val error = if (!connected && !connecting) ConnectionCenter.lastError else null
        
        val segments = if (error != null) 3 else 2
        SettingsGroup {
            SettingsRow(
                index = 0, count = segments,
                title = when {
                    connected -> "已连接"
                    connecting -> "正在连接"
                    else -> "未连接"
                },
                support = when {
                    connected -> buildString {
                        
                        
                        
                        
                        
                        append(when (info?.mode) { "wifi" -> "Wi-Fi 网络"; else -> "相机热点" })
                        if (!info?.ssid.isNullOrBlank()) append(" · ").append(info!!.ssid)
                    }
                    
                    
                    
                    connecting -> null
                    else -> "确保相机服务正常运行"
                },
                
                supportMaxLines = 1,
                
                
                
                
                
                
                leading = {
                    Crossfade(
                        targetState = connecting && !connected,
                        animationSpec = Motion.effectsDefault(),
                        label = "connLeading",
                    ) { waiting ->
                        if (waiting) {
                            Box(
                                Modifier.size(44.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator(Modifier.size(34.dp))
                            }
                        } else {
                            IconCircle(
                                R.drawable.ic_wifi,
                                if (connected) IconTints.Green else IconTints.Purple,
                            )
                        }
                    }
                },
            )
            if (error != null) {
                Segment(index = 1, count = 3) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Segment(index = segments - 1, count = segments) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (connected) {
                        ExpressiveButton(
                            "断开连接",
                            { ConnectionCenter.disconnect() },
                            modifier = Modifier.fillMaxWidth(),
                            tonal = true,
                        )
                    } else {
                        ExpressiveButton(
                            if (connecting) "正在连接…" else "重新连接",
                            { ConnectionCenter.reconnect(context) },
                            modifier = Modifier.fillMaxWidth(),
                            
                            enabled = !connecting,
                        )
                    }
                }
            }
        }
        
        Box(Modifier.padding(bottom = 8.dp))
    }
}











@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
