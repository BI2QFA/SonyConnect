package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ftp.FtpRepository
import com.bi2qfa.sonyconnect.transfer.DownloadService
import com.bi2qfa.sonyconnect.transfer.TransferItem
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    onGoTransfers: () -> Unit,
    dirState: androidx.compose.runtime.MutableState<String>,
) {
    val context = LocalContext.current
    val host = ConnectionCenter.host
    val scope = rememberCoroutineScope()

    var dir by dirState
    var entries by remember { mutableStateOf<List<FtpRepository.FtpEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var gridView by remember { mutableStateOf(false) }

    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var preparing by remember { mutableStateOf(false) }

    var fullscreenPath by remember { mutableStateOf<String?>(null) }

    androidx.activity.compose.BackHandler(enabled = dir != "/") {
        dir = FtpRepository.parentOf(dir)
    }
    androidx.activity.compose.BackHandler(enabled = selecting) {
        selecting = false
        selected.clear()
    }

    suspend fun load(d: String) {
        val h = host ?: return
        loading = true
        loadError = null
        try {
            val list = withContext(Dispatchers.IO) { FtpRepository.list(h, d) }
            entries = list

            val imagePaths = list.filter {
                !it.isDir && it.ext in setOf("jpg", "jpeg", "arw")
            }.map { it.path }
            ThumbStore.request(imagePaths)
        } catch (e: kotlinx.coroutines.CancellationException) {

            throw e
        } catch (e: Exception) {
            android.util.Log.d("FilesScreen", "目录读取失败: ${e.message}")
            loadError = "目录读取失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(dir, host) { load(dir) }

    fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    fun longPress(path: String) {
        if (selecting) {
            selecting = false
            selected.clear()
        } else {
            selecting = true
            toggle(path)
        }
    }

    fun selectAll() {
        selecting = true
        selected.clear()
        selected.addAll(entries.map { it.path })
    }

    fun invertSelection() {
        selecting = true
        val current = selected.toHashSet()
        selected.clear()
        for (e in entries) if (e.path !in current) selected.add(e.path)
    }

    fun startTransfer() {
        if (preparing) return
        preparing = true
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                collectSelectedFiles(host, entries, selected)
            }
            preparing = false
            val hostNow = ConnectionCenter.host
            if (files.isNotEmpty() && hostNow != null) {
                TransferStore.enqueueAll(files.map {
                    TransferItem(
                        id = it.path, name = it.name, path = it.path, size = it.size,
                    )
                })
                DownloadService.start(context)
                selecting = false
                selected.clear()
                onGoTransfers()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FtpRepository.breadcrumbOf(dir).forEachIndexed { idx, (name, path) ->
                        if (idx > 0) Text(" / ", style = MaterialTheme.typography.labelSmall)
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            color = if (path == dir) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { dir = path }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                IconButton(onClick = { selectAll() }, enabled = entries.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_select_all), contentDescription = "全选")
                }
                IconButton(onClick = { invertSelection() }, enabled = entries.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_select_inverse), contentDescription = "反选")
                }
                IconButton(onClick = { gridView = !gridView }) {
                    Icon(
                        painterResource(if (gridView) R.drawable.ic_list else R.drawable.ic_grid),
                        contentDescription = "切换显示模式",
                    )
                }
            }

            if (selecting) {
                Text(
                    "已选 ${selected.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            loadError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (gridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                ) {
                    gridItems(entries, key = { it.path }) { entry ->
                        GridCell(
                            entry = entry,
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggle = { toggle(entry.path) },
                            onLongClick = { longPress(entry.path) },
                            onClick = {
                                if (entry.isDir) dir = entry.path
                                else if (entry.ext in setOf("jpg", "jpeg", "arw"))
                                    fullscreenPath = entry.path
                            },
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        EntryRow(
                            entry = entry,
                            selecting = selecting,
                            isSelected = selected.contains(entry.path),
                            onToggleSelect = { toggle(entry.path) },
                            onClick = {
                                if (entry.isDir) dir = entry.path
                                else if (entry.ext in setOf("jpg", "jpeg", "arw"))
                                    fullscreenPath = entry.path
                            },
                            onLongClick = { longPress(entry.path) },
                        )
                    }
                }
            }
        }

        if (selecting && selected.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { startTransfer() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                if (preparing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painterResource(R.drawable.ic_start),
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("开始传输 (${selected.size})")
            }
        }
    }

    fullscreenPath?.let { path ->
        Dialog(onDismissRequest = { fullscreenPath = null }) {
            FullscreenPreview(path = path) { fullscreenPath = null }
        }
    }
}

private fun collectSelectedFiles(
    host: String?,
    entries: List<FtpRepository.FtpEntry>,
    selected: List<String>,
): List<FtpRepository.FtpEntry> {
    val h = host ?: return emptyList()
    val out = mutableListOf<FtpRepository.FtpEntry>()
    for (path in selected) {
        val entry = entries.firstOrNull { it.path == path } ?: continue
        if (entry.isDir) {
            out.addAll(expandDir(h, entry.path, 500))
        } else {
            out.add(entry)
        }
    }
    return out.distinctBy { it.path }
}

private fun expandDir(host: String, dir: String, cap: Int): List<FtpRepository.FtpEntry> {
    val out = mutableListOf<FtpRepository.FtpEntry>()
    val queue = ArrayDeque(listOf(dir))
    var depth = 0
    while (queue.isNotEmpty() && out.size < cap && depth < 4) {
        val d = queue.removeFirst()
        depth++
        val list = try {
            FtpRepository.list(host, d)
        } catch (e: Exception) {
            continue
        }
        for (e in list) {
            if (e.isDir) queue.add(e.path) else out.add(e)
        }
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: FtpRepository.FtpEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            Spacer(Modifier.width(4.dp))
        }
        ThumbOrPlaceholder(entry, size = 52)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)

            val meta = if (entry.isDir) formatTime(entry.timestamp)
            else listOf(formatSize(entry.size), formatTime(entry.timestamp))
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    entry: FtpRepository.FtpEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        Modifier.padding(3.dp)

            .combinedClickable(
                onClick = if (selecting) onToggle else onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                if (entry.isDir) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1.4f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_nav_files),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    val st = ThumbStore.states[entry.path]
                    val bmp = ThumbStore.smallThumb(entry.path)
                    if (bmp != null) {
                        Image(
                            bmp,
                            contentDescription = entry.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.4f),
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1.4f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (st == ThumbStore.ThumbState.LOADING) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    painterResource(R.drawable.ic_image_placeholder),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                if (entry.timestamp > 0) {
                    Text(
                        formatTime(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 4.dp),
                    )
                }
            }
            if (selecting && isSelected) {

                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = "已选",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbOrPlaceholder(entry: FtpRepository.FtpEntry, size: Int) {
    if (entry.isDir) {
        Box(
            Modifier.width(size.dp).height(size.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_nav_files),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    val isImage = entry.ext in setOf("jpg", "jpeg", "arw")
    val bmp = if (isImage) ThumbStore.smallThumb(entry.path) else null
    val st = ThumbStore.states[entry.path]
    Box(
        Modifier.width(size.dp).height(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(bmp, contentDescription = entry.name, modifier = Modifier.size(size.dp))
            isImage && st == ThumbStore.ThumbState.LOADING ->
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Icon(
                painterResource(
                    if (isImage) R.drawable.ic_image_placeholder else R.drawable.ic_file_generic
                ),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun FullscreenPreview(path: String, onDismiss: () -> Unit) {
    var bmp by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val b = withContext(Dispatchers.IO) { ThumbStore.fetchPreviewBlocking(path) }
        if (b != null) bmp = b else failed = true
    }
    Box(

        Modifier.fillMaxSize().clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(b, contentDescription = path, modifier = Modifier.fillMaxSize())
            failed -> Text("无法获取预览", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator()
        }
    }
}

private fun formatSize(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}
