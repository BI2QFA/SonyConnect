package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.transfer.DownloadService
import com.bi2qfa.sonyconnect.transfer.TransferItem
import com.bi2qfa.sonyconnect.transfer.TransferStore
import com.bi2qfa.sonyconnect.ui.FloatingNavInset
import com.bi2qfa.sonyconnect.ui.components.HintText
import com.bi2qfa.sonyconnect.ui.components.segmentShape
import com.bi2qfa.sonyconnect.ui.components.segmentSurfaceColor
import com.bi2qfa.sonyconnect.ui.theme.Motion
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
    var entries by remember { mutableStateOf<List<ObjectRepository.FtpEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var gridView by remember { mutableStateOf(false) }

    
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var preparing by remember { mutableStateOf(false) }

    
    var fullscreenPath by remember { mutableStateOf<String?>(null) }

    
    
    androidx.activity.compose.BackHandler(enabled = dir != "/") {
        dir = ObjectRepository.parentOf(dir)
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
            val list = withContext(Dispatchers.IO) { ObjectRepository.list(h, d) }
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
                        id = it.path, name = it.name, path = it.path,
                        size = it.size, mtime = it.timestamp,
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
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ObjectRepository.breadcrumbOf(dir).forEachIndexed { idx, (name, path) ->
                            if (idx > 0) {
                                Text(
                                    "›",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            Text(
                                name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                color = if (path == dir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    
                                    
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { dir = path }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                            )
                        }
                    }
                    IconButton(onClick = { selectAll() }, enabled = entries.isNotEmpty()) {
                        Icon(
                            painterResource(R.drawable.ic_select_all),
                            contentDescription = "全选",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { invertSelection() }, enabled = entries.isNotEmpty()) {
                        Icon(
                            painterResource(R.drawable.ic_select_inverse),
                            contentDescription = "反选",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { gridView = !gridView }) {
                        Icon(
                            painterResource(if (gridView) R.drawable.ic_list else R.drawable.ic_grid),
                            contentDescription = "切换显示模式",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            
            
            AnimatedVisibility(
                visible = selecting,
                enter = fadeIn(Motion.effectsFast()) + expandVertically(Motion.spatialDefault()),
                exit = fadeOut(Motion.effectsFast()) + shrinkVertically(Motion.spatialFast()),
            ) {
                Surface(
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "已选 ${selected.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
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
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            
            
            if (host == null && !loading) {
                HintText("未连接相机，无法浏览文件")
            }

            if (gridView) {
                LazyVerticalGrid(
                    
                    
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = FloatingNavInset, top = 4.dp),
                ) {
                    gridItems(entries, key = { it.path }) { entry ->
                        GridCell(
                            modifier = Modifier.animateItem(
                                placementSpec = Motion.spatialDefault(),
                                fadeInSpec = Motion.effectsDefault(),
                                fadeOutSpec = Motion.effectsFast(),
                            ),
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
                
                
                
                
                
                
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = FloatingNavInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    val count = entries.size
                    itemsIndexed(entries, key = { _, e -> e.path }) { index, entry ->
                        EntryRow(
                            
                            
                            modifier = Modifier.animateItem(
                                placementSpec = Motion.spatialDefault(),
                                fadeInSpec = Motion.effectsDefault(),
                                fadeOutSpec = Motion.effectsFast(),
                            ),
                            shape = segmentShape(index, count),
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

        
        
        
        AnimatedVisibility(
            visible = selecting && selected.isNotEmpty(),
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
                onClick = { startTransfer() },
                modifier = Modifier
                    .padding(20.dp)
                    
                    .padding(bottom = FloatingNavInset),
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
    entries: List<ObjectRepository.FtpEntry>,
    selected: List<String>,
): List<ObjectRepository.FtpEntry> {
    val h = host ?: return emptyList()
    val out = mutableListOf<ObjectRepository.FtpEntry>()
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

private fun expandDir(host: String, dir: String, cap: Int): List<ObjectRepository.FtpEntry> {
    val out = mutableListOf<ObjectRepository.FtpEntry>()
    val queue = ArrayDeque(listOf(dir))
    var depth = 0
    while (queue.isNotEmpty() && out.size < cap && depth < 4) {
        val d = queue.removeFirst()
        depth++
        val list = try {
            ObjectRepository.list(host, d)
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
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    entry: ObjectRepository.FtpEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val selected = selecting && isSelected
    Row(
        modifier
            .fillMaxWidth()
            
            .clip(shape)
            
            
            
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else segmentSurfaceColor()
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            Spacer(Modifier.width(4.dp))
        }
        ThumbOrPlaceholder(entry, size = 56)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            
            val meta = if (entry.isDir) formatTime(entry.timestamp)
            else listOf(formatSize(entry.size), formatTime(entry.timestamp))
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    modifier: Modifier = Modifier,
    entry: ObjectRepository.FtpEntry,
    selecting: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier
            .padding(4.dp)
            
            
            
            
            .clip(CardDefaults.shape)
            
            .combinedClickable(
                onClick = if (selecting) onToggle else onClick,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            
            containerColor = if (selecting && isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                
                
                
                
                val isImage = entry.ext in setOf("jpg", "jpeg", "arw")
                val bmp = if (isImage) ThumbStore.smallThumb(entry.path) else null
                val st = ThumbStore.states[entry.path]
                when {
                    bmp != null -> Image(
                        bmp,
                        contentDescription = entry.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.5f),
                    )
                    isImage && st == ThumbStore.ThumbState.LOADING -> Box(
                        Modifier.fillMaxWidth().aspectRatio(1.5f),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    else -> GridPlaceholder(
                        
                        when {
                            entry.isDir -> R.drawable.ic_folder
                            isImage -> R.drawable.ic_image_placeholder
                            else -> R.drawable.ic_file_generic
                        }
                    )
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                )
                
                val when_ = formatTime(entry.timestamp)
                if (when_.isNotBlank()) {
                    Text(
                        when_,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = 10.dp,
                            end = 10.dp,
                            top = 2.dp,
                            bottom = 10.dp,
                        ),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                }
            }
            if (selecting && isSelected) {
                
                
                
                val pop by animateFloatAsState(
                    targetValue = if (selecting && isSelected) 1f else 0f,
                    animationSpec = Motion.spatialFast(),
                    label = "gridCheck",
                )
                if (pop > 0.01f) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp)
                            .scale(pop)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}












@Composable
private fun GridPlaceholder(iconRes: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun ThumbOrPlaceholder(entry: ObjectRepository.FtpEntry, size: Int) {
    val isImage = entry.ext in setOf("jpg", "jpeg", "arw")
    val bmp = if (isImage) ThumbStore.smallThumb(entry.path) else null
    val st = ThumbStore.states[entry.path]
    Box(
        Modifier.width(size.dp).height(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(
                bmp,
                contentDescription = entry.name,
                modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.medium),
            )
            isImage && st == ThumbStore.ThumbState.LOADING ->
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            
            
            
            else -> Box(
                Modifier
                    .size(size.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(
                        when {
                            entry.isDir -> R.drawable.ic_folder
                            isImage -> R.drawable.ic_image_placeholder
                            else -> R.drawable.ic_file_generic
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        
        
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
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
