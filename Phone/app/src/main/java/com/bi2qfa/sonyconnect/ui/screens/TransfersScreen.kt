package com.bi2qfa.sonyconnect.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.core.StorageSink
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.transfer.DownloadService
import com.bi2qfa.sonyconnect.transfer.TransferItem
import com.bi2qfa.sonyconnect.transfer.TransferState
import com.bi2qfa.sonyconnect.transfer.TransferStore
import com.bi2qfa.sonyconnect.ui.FloatingNavInset
import com.bi2qfa.sonyconnect.ui.SlimProgress
import com.bi2qfa.sonyconnect.ui.components.Segment
import com.bi2qfa.sonyconnect.ui.components.HintText
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.RowIconAction
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Motion












@Composable
fun TransfersScreen() {
    val context = LocalContext.current
    val items = TransferStore.items
    val running = TransferStore.batchRunning

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { DownloadService.start(context) }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }

    fun openFile(item: TransferItem) {
        val path = item.path
        
        
        val rel = StorageSink.localRelPath(item.deviceDir, path)
        val uri = StorageSink.openUriOrNull(context, SettingsRepo.downloadTreeUri, rel)
        if (uri == null) {
            android.widget.Toast.makeText(
                context, "无法打开：文件不存在或已被移动", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, StorageSink.mimeOf(path.substringAfterLast('/')))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(view, "选择打开方式"))
        }.onFailure {
            android.widget.Toast.makeText(
                context, "没有可以打开此文件的应用", android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            
            
            
            
            
            
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatChip("等待", TransferStore.countBy(TransferState.QUEUED))
                    StatChip("完成", TransferStore.countBy(TransferState.DONE))
                    StatChip("失败", TransferStore.countBy(TransferState.FAILED))
                }
                
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_drop), contentDescription = "任务菜单")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        
                        
                        
                        
                        
                        
                        
                        
                        shape = MenuDefaults.shape,
                        containerColor = MenuDefaults.containerColor,
                    ) {
                        DropdownMenuItem(
                            text = { Text("删除已完成的任务") },
                            onClick = {
                                menuOpen = false
                                TransferStore.clearFinished()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "删除所有任务",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                confirmClearAll = true
                            },
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                HintText("还没有传输任务：在文件页长按选中文件后点「开始传输」", center = false)
            }

            
            
            
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = FloatingNavInset,
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                val count = items.size
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    val done = item.state == TransferState.DONE
                    
                    
                    Segment(
                        index = index,
                        count = count,
                        
                        onClick = if (done) ({ openFile(item) }) else null,
                        
                        modifier = Modifier.animateItem(
                            placementSpec = Motion.spatialDefault(),
                            fadeInSpec = Motion.effectsDefault(),
                            fadeOutSpec = Motion.effectsFast(),
                        ),
                    ) {
                        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconCircle(
                                    when (item.state) {
                                        TransferState.DONE -> R.drawable.ic_check
                                        TransferState.FAILED -> R.drawable.ic_info
                                        else -> R.drawable.ic_thumb_download
                                    },
                                    when (item.state) {
                                        TransferState.DONE -> IconTints.Green
                                        TransferState.FAILED -> IconTints.Pink
                                        else -> IconTints.Purple
                                    },
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    
                                    
                                    
                                    
                                    
                                    val label = stateLabel(item)
                                    val sub = if (label.isNotBlank()) label
                                    else if (item.mtime > 0) formatMtime(item.mtime) else ""
                                    if (sub.isNotBlank()) {
                                        Text(
                                            sub,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = when (item.state) {
                                                TransferState.FAILED ->
                                                    MaterialTheme.colorScheme.error
                                                TransferState.DONE ->
                                                    MaterialTheme.colorScheme.primary
                                                else ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                                if (item.state != TransferState.RUNNING) {
                                    RowIconAction(
                                        R.drawable.ic_trash,
                                        "删除任务",
                                        onClick = { TransferStore.remove(item) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val frac = if (item.size > 0) {
                                (item.doneBytes.toFloat() / item.size).coerceIn(0f, 1f)
                            } else 0f
                            Box(Modifier.padding(end = 8.dp)) {
                                SlimProgress(
                                    fraction = frac,
                                    color = if (item.state == TransferState.FAILED)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${formatSize(item.doneBytes.coerceIn(0, item.size))} / ${formatSize(item.size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        
        
        AnimatedVisibility(
            visible = items.any { it.state != TransferState.DONE },
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = scaleIn(
                animationSpec = Motion.spatialDefault(),
                initialScale = 0.7f,
            ) + fadeIn(Motion.effectsFast()),
            exit = scaleOut(
                animationSpec = Motion.spatialFast(),
                targetScale = 0.7f,
            ) + fadeOut(Motion.effectsFast()),
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (running) {
                        DownloadService.stopCurrent(context)
                    } else if (ConnectionCenter.state != ConnectionCenter.State.CONNECTED) {
                        
                        android.widget.Toast.makeText(
                            context, "相机未连接，无法开始传输", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        TransferStore.resetFailedForStart()
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            DownloadService.start(context)
                        }
                    }
                },
                modifier = Modifier
                    .padding(20.dp)
                    
                    
                    .padding(bottom = FloatingNavInset),
            ) {
                Icon(
                    painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_start),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (running) "停止传输" else "开始传输")
            }
        }

        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text("删除所有任务") },
                text = { Text("将删除全部传输任务记录（不会删除本机已下载的文件）。确定继续吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearAll = false
                        TransferStore.clearAll()
                        
                        
                        DownloadService.stopCurrent(context)
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
                },
            )
        }
    }
}


@Composable
private fun StatChip(label: String, count: Int) {    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge)
        }
    }
}





private fun stateLabel(item: TransferItem): String = when (item.state) {
    TransferState.QUEUED -> "等待传输"
    TransferState.RUNNING -> "传输中"
    TransferState.DONE -> ""
    TransferState.FAILED -> "失败（开始传输时自动续传）"
}


private fun formatMtime(ms: Long): String {
    if (ms <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}

private fun formatSize(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
