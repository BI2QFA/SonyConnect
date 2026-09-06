package com.bi2qfa.sonyconnect.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.StorageSink
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.ui.SlimProgress
import com.bi2qfa.sonyconnect.transfer.DownloadService
import com.bi2qfa.sonyconnect.transfer.TransferState
import com.bi2qfa.sonyconnect.transfer.TransferStore

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

    fun openFile(path: String) {
        val uri = StorageSink.openUriOrNull(context, SettingsRepo.downloadTreeUri, path)
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
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "等待 ${TransferStore.countBy(TransferState.QUEUED)} · " +
                        "完成 ${TransferStore.countBy(TransferState.DONE)} · " +
                        "失败 ${TransferStore.countBy(TransferState.FAILED)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_drop), contentDescription = "任务菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Column(
                        Modifier.fillMaxWidth()

                            .then(
                                if (item.state == TransferState.DONE) {
                                    Modifier.clickable { openFile(item.path) }
                                } else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    stateLabel(item),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (item.state) {
                                        TransferState.FAILED -> MaterialTheme.colorScheme.error
                                        TransferState.DONE -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (item.state != TransferState.RUNNING) {
                                TextButton(onClick = { TransferStore.remove(item) }) {
                                    Text(
                                        "删除",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val frac = if (item.size > 0) {
                            (item.doneBytes.toFloat() / item.size).coerceIn(0f, 1f)
                        } else 0f
                        SlimProgress(
                            fraction = frac,
                            color = if (item.state == TransferState.FAILED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatSize(item.doneBytes.coerceIn(0, item.size))} / ${formatSize(item.size)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (items.any { it.state != TransferState.DONE }) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (running) {
                        DownloadService.stopCurrent(context)
                    } else {
                        TransferStore.resetFailedForStart()
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            DownloadService.start(context)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
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

private fun stateLabel(item: com.bi2qfa.sonyconnect.transfer.TransferItem): String = when (item.state) {
    TransferState.QUEUED -> "等待传输"
    TransferState.RUNNING -> "传输中"
    TransferState.DONE -> "已完成"
    TransferState.FAILED -> "失败（开始传输时自动续传）"
}

private fun formatSize(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
