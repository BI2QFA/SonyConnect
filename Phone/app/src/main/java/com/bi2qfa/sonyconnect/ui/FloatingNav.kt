package com.bi2qfa.sonyconnect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.delay
























val FloatingNavInset = 112.dp

@Composable
fun FloatingNav(
    modifier: Modifier = Modifier,
    tabIndex: Int,
    onTab: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    
    androidx.activity.compose.BackHandler(enabled = expanded) { expanded = false }

    
    
    
    
    
    
    
    
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val panelBottomPadding = navBottomInset + 88.dp + 12.dp

    Box(modifier) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavPill(tabIndex = tabIndex, onTab = onTab)
            Spacer(Modifier.width(10.dp))
            ControlCenterButton(expanded = expanded) { expanded = !expanded }
        }

        ControlCenterPanel(
            visible = expanded,
            bottomPadding = panelBottomPadding,
            onDismiss = { expanded = false },
            onOpenSettings = {
                expanded = false
                onOpenSettings()
            },
            onSwitchDevice = { guid ->
                expanded = false
                onSwitchDevice(guid)
            },
        )
    }
}










@Composable
private fun NavPill(tabIndex: Int, onTab: (Int) -> Unit) {
    val tabs = listOf(
        Triple(0, R.drawable.ic_nav_home, "主界面"),
        Triple(1, R.drawable.ic_nav_files, "文件"),
        Triple(2, R.drawable.ic_nav_transfers, "传输"),
    )
    
    
    
    
    
    val itemWidth = 64.dp
    val gap = 6.dp
    
    
    
    
    val padH = 10.dp
    val padV = 8.dp
    val indicatorX by animateDpAsState(
        targetValue = padH + (itemWidth + gap) * tabIndex,
        animationSpec = Motion.spatialDefault(),
        label = "navIndicator",
    )
    val indicatorShape = RoundedCornerShape(50)
    Surface(
        shape = indicatorShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .size(width = itemWidth, height = 48.dp)
                    .clip(indicatorShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            
            Row(
                Modifier.padding(horizontal = padH, vertical = padV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                tabs.forEach { (idx, icon, label) ->
                    NavPillItem(
                        selected = tabIndex == idx,
                        iconRes = icon,
                        label = label,
                        onClick = { onTab(idx) },
                    )
                }
            }
        }
    }
}









@Composable
private fun NavPillItem(selected: Boolean, iconRes: Int, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.effectsFast(),
        label = "navIconTint",
    )
    val pop by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = Motion.spatialFast(),
        label = "navIconPop",
    )
    Box(
        Modifier
            
            
            .width(64.dp)
            .height(48.dp)
            .clip(shape)
            
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(26.dp).scale(pop),
            tint = tint,
        )
    }
}







@Composable
private fun ControlCenterButton(expanded: Boolean, onClick: () -> Unit) {
    val batchRunning = TransferStore.batchRunning
    val thumbTotal = ThumbStore.batchTotal
    val thumbTotal2 = thumbTotal
    
    
    
    val downloading = batchRunning
    val thumbsFetching = thumbTotal2 > 0 && ThumbStore.batchDone < thumbTotal2
    val active = downloading || thumbsFetching

    val fraction = when {
        downloading -> {
            val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
            TransferStore.batchDoneBytes.coerceIn(0, total) / total.toFloat()
        }
        thumbsFetching -> ThumbStore.batchDone.toFloat() / thumbTotal2
        else -> 0f
    }

    
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.spatialFast(),
        label = "chevron",
    )
    
    
    
    
    
    
    val container by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = Motion.effectsFast(),
        label = "ccContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = Motion.effectsFast(),
        label = "ccContent",
    )
    
    
    val shownFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.spatialDefault(),
        label = "ringFraction",
    )

    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        FilledIconButton(
            onClick = onClick,
            
            
            modifier = Modifier.size(60.dp).shadow(6.dp, CircleShape),
            shape = CircleShape,
            
            
            
            
            
            
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = contentColor,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_nav_panel),
                contentDescription = if (expanded) "收起控制中心" else "展开控制中心",
                modifier = Modifier
                    .size(26.dp)
                    .rotate(rotation),
            )
        }
        
        
        
        if (active) {
            CircularProgressIndicator(
                progress = { shownFraction },
                modifier = Modifier.size(60.dp),
                strokeWidth = 4.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}


















@Composable
private fun ControlCenterPanel(
    visible: Boolean,
    
    bottomPadding: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: (String) -> Unit,
) {
    
    
    
    
    val tick = produceState(0) {
        while (true) {
            delay(1000)
            value++
        }
    }
    
    val origin = TransformOrigin(1f, 1f)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(Motion.effectsFast()),
            exit = fadeOut(Motion.effectsFast()),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = scaleIn(
                animationSpec = Motion.spatialDefault(),
                initialScale = 0.86f,
                transformOrigin = origin,
            ) + fadeIn(Motion.effectsDefault()),
            exit = scaleOut(
                animationSpec = Motion.spatialFast(),
                targetScale = 0.9f,
                transformOrigin = origin,
            ) + fadeOut(Motion.effectsFast()),
        ) {
            Card(
                Modifier
                    
                    .padding(end = 12.dp, bottom = bottomPadding)
                    .widthIn(min = 260.dp, max = 330.dp)
                    
                    .animateContentSize(Motion.spatialDefault()),
                shape = RoundedCornerShape(28.dp),
                
                
                
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                
                
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                
                if (TransferStore.batchRunning || ThumbStore.batchTotal > 0) {
                    ProgressSection()
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                
                PanelRow(
                    iconRes = R.drawable.ic_settings_gear,
                    title = "设置",
                    subtitle = null,
                    onClick = onOpenSettings,
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                
                DeviceSection(onSwitchDevice = onSwitchDevice)
            }
            }
        }
    }
}

@Composable
private fun ProgressSection() {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (TransferStore.batchRunning) {
            val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
            val done = TransferStore.batchDoneBytes.coerceIn(0, total)
            val pct = (done * 100 / total).toInt()
            val elapsedMs = (System.currentTimeMillis() - TransferStore.batchStartedAt).coerceAtLeast(1)
            val speed = TransferStore.batchSpeedBps
            val etaSec = if (speed > 0) (total - done) / speed else -1
            
            
            val batchGuid = TransferStore.runningQueue?.guid
            if (batchGuid != null && batchGuid != ConnectionCenter.displayGuid()?.lowercase()) {
                Text(
                    ConnectionCenter.labelOf(batchGuid),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("下载 $pct%", style = MaterialTheme.typography.labelLarge)
            SlimProgress(fraction = done / total.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelMetric("已用", formatDuration(elapsedMs), Modifier.weight(1f))
                PanelMetric(
                    "剩余",
                    if (etaSec >= 0) formatDuration(etaSec * 1000) else "—",
                    Modifier.weight(1f),
                )
                PanelMetric("速度", formatSpeed(speed), Modifier.weight(1.2f))
            }
        } else {
            Text(
                "下载 无进行中的传输",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ThumbStore.batchTotal > 0) {
            val tDone = ThumbStore.batchDone
            val tTotal = ThumbStore.batchTotal
            Text(
                if (tDone >= tTotal) "缩略图加载完成 $tDone/$tTotal"
                else "缩略图 $tDone/$tTotal" + if (ThumbStore.paused) "（已暂停）" else "",
                style = MaterialTheme.typography.labelLarge,
            )
            SlimProgress(fraction = if (tTotal > 0) tDone.toFloat() / tTotal else 0f)
        }
    }
}













@Composable
private fun DeviceSection(onSwitchDevice: (String) -> Unit) {
    val cameras = PairingStore.all()
    
    val selectedGuid = ConnectionCenter.displayGuid()
    val connectedGuid = ConnectionCenter.camera?.guidHex
    val busy = ConnectionCenter.connecting
    Text(
        "已绑定设备",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        
        
        modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 2.dp),
    )
    if (cameras.isEmpty()) {
        Text(
            "尚未绑定任何相机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    cameras.forEach { cam ->
        val selected = cam.peerDeviceId.equals(selectedGuid, ignoreCase = true)
        val connected = cam.peerDeviceId.equals(connectedGuid, ignoreCase = true)
        val label = listOfNotNull(
            cam.peerModel.takeIf { it.isNotBlank() },
            cam.peerName.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "未知相机"
        val serial = cam.peerSerial.takeIf { it.isNotBlank() }
        
        
        
        PanelRow(
            
            iconRes = R.drawable.ic_camera,
            title = label + (serial?.let { " SN:$it" } ?: ""),
            subtitle = when {
                connected -> "已连接"
                selected && busy -> "正在连接"
                
                
                selected -> "未连接"
                else -> "点击切换"
            },
            highlighted = selected,
            
            
            
            
            onClick = if (selected) null else ({ onSwitchDevice(cam.peerDeviceId) }),
        )
    }
}


@Composable
private fun PanelRow(
    iconRes: Int,
    title: String,
    subtitle: String?,
    highlighted: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(20.dp)
    val color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
    else Color.Transparent
    val boxModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
    if (onClick == null) {
        Surface(color = color, shape = shape, modifier = boxModifier) {
            PanelRowContent(iconRes, title, subtitle, highlighted)
        }
        return
    }
    Surface(
        color = color,
        onClick = onClick,
        
        
        
        
        
        shape = shape,
        modifier = boxModifier,
    ) {
        PanelRowContent(iconRes, title, subtitle, highlighted)
    }
}


@Composable
private fun PanelRowContent(
    iconRes: Int,
    title: String,
    subtitle: String?,
    highlighted: Boolean,
) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
}


@Composable
private fun PanelMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m % 60, s % 60)
        else -> "%d:%02d".format(m, s % 60)
    }
}

private fun formatSpeed(bps: Long): String = when {
    bps >= 1 shl 20 -> "%.1f MB/s".format(bps / 1048576.0)
    bps >= 1 shl 10 -> "%.0f KB/s".format(bps / 1024.0)
    bps > 0 -> "$bps B/s"
    else -> "—"
}

