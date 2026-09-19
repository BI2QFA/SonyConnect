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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bi2qfa.sonyconnect.ui.components.MsIcon
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 文件页：
 * - 小缩略图列表 / 大缩略图网格两种显示模式；未取到缩略图的文件显示占位图标
 * - 单击文件 → 全屏缩略图（大预览）；长按 → 选择模式（列表与九宫格一致）
 * - 全选 / 反选（用户定版）；刷新已删（进目录即自动加载）
 * - 选择模式右下角 = 「开始传输(n)」FAB，样式与传输页一致
 * - 浏览位置（当前目录）由 AppRoot 提升持有：切页再回来仍在原位置
 *
 * MD3E 版式：路径与动作收进一条**药丸工具条**（28dp 圆角的整块面），列表行用
 * 行内缩的圆角按压块 + 16dp 圆角缩略图。列表**不套大卡片** —— 一屏十几行套进卡片后，
 * 卡片底边会伸到悬浮药丸导航底下被压住，四角反而看不出是张卡（试过，不如不套）。
 */
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
    var loadingMore by remember { mutableStateOf(false) }
    var nextOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var gridView by remember { mutableStateOf(false) }

    // 传输选择模式
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var preparing by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 全屏预览
    var fullscreenEntry by remember { mutableStateOf<ObjectRepository.FtpEntry?>(null) }

    // 返回键层级化（用户定版）：文件页返回=上一层目录；选择模式中返回=先退
    // 选择。后注册的 BackHandler 优先生效，选择模式因此盖过目录层级。
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
            val page = withContext(Dispatchers.IO) { ObjectRepository.listPage(h, d, 0, 256) }
            entries = page.entries
            nextOffset = page.nextOffset
            hasMore = page.hasMore
            // 本目录图片文件按需请求小缩略图
            val imageKeys = page.entries.filter {
                !it.isDir && it.ext in setOf("jpg", "jpeg", "arw")
            }.map {
                ThumbStore.ObjectCacheKey(
                    cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
                    path = it.path,
                    size = it.size,
                    mtime = it.timestamp,
                )
            }
            ThumbStore.request(imageKeys)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 切页/换目录导致的取消不是错误，放行（此前取消异常的 message
            // 会被当成错误显示成带 scope 字样的红字）
            throw e
        } catch (e: Exception) {
            android.util.Log.d("FilesScreen", "目录读取失败: ${e.message}")
            loadError = "目录读取失败"
        } finally {
            loading = false
        }
    }

    suspend fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        val h = host ?: return
        loadingMore = true
        try {
            val page = withContext(Dispatchers.IO) {
                ObjectRepository.listPage(h, dir, nextOffset, 256)
            }
            val existing = entries.mapTo(HashSet()) { it.path }
            entries = entries + page.entries.filter { existing.add(it.path) }
            nextOffset = page.nextOffset
            hasMore = page.hasMore
            val imageKeys = page.entries.filter {
                !it.isDir && it.ext in setOf("jpg", "jpeg", "arw")
            }.map {
                ThumbStore.ObjectCacheKey(
                    cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
                    path = it.path,
                    size = it.size,
                    mtime = it.timestamp,
                )
            }
            ThumbStore.request(imageKeys)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.d("FilesScreen", "loadMore failed: ${e.message}")
            loadError = "目录读取失败"
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(dir, host) { load(dir) }

    fun toggle(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    /** 长按（用户定版）：未在选择模式→进入并选中该项；已在选择模式→退出 */
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
        if (preparing || host == null) return
        val activeHost = host
        preparing = true
        scanProgress = "正在扫描…"
        scanJob = scope.launch {
            try {
                collectAndEnqueue(
                    host = activeHost,
                    entries = entries,
                    selected = selected.toList(),
                    enqueue = { batch ->
                        TransferStore.enqueueAll(batch.map {
                            TransferItem(
                                id = it.path, name = it.name, path = it.path,
                                size = it.size, mtime = it.timestamp,
                            )
                        })
                    },
                    onProgress = { dirs, found ->
                        withContext(Dispatchers.Main) {
                            scanProgress = "已扫描 $dirs 个目录 · 已加入 $found 个文件"
                        }
                    },
                )
                preparing = false
                scanJob = null
                scanProgress = ""
                if (ConnectionCenter.host != null) {
                    DownloadService.start(context)
                    selecting = false
                    selected.clear()
                    onGoTransfers()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消：已经分批入队的任务保留，尚未入队的丢弃。
                preparing = false
                scanJob = null
                scanProgress = ""
                throw e
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (preparing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        scanProgress.ifBlank { "正在扫描…" },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    TextButton(onClick = {
                        scanJob?.cancel()
                        scanJob = null
                        preparing = false
                        scanProgress = ""
                    }) { Text("取消") }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            // ===== 路径 + 动作：一整条 28dp 圆角的药丸工具条 =====
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
                                    // 先裁成小圆角再 clickable：水波纹才是圆角块而不是方角块；
                                    // padding 放在 clickable **之后**，让可点区域含留白
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { dir = path }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                            )
                        }
                    }
                    val selAllSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { selectAll() },
                        enabled = entries.isNotEmpty(),
                        interactionSource = selAllSource,
                    ) {
                        MsIcon(
                            icon = MsIcon.SELECT_ALL,
                            contentDescription = "全选",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            interactionSource = selAllSource,
                        )
                    }
                    val inverseSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { invertSelection() },
                        enabled = entries.isNotEmpty(),
                        interactionSource = inverseSource,
                    ) {
                        MsIcon(
                            icon = MsIcon.SELECT_INVERSE,
                            contentDescription = "反选",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            interactionSource = inverseSource,
                        )
                    }
                    val viewSource = remember { MutableInteractionSource() }
                    IconButton(onClick = { gridView = !gridView }, interactionSource = viewSource) {
                        MsIcon(
                            icon = if (gridView) MsIcon.LIST else MsIcon.GRID,
                            contentDescription = "切换显示模式",
                            modifier = Modifier.size(22.dp),
                            size = 22.dp,
                            interactionSource = viewSource,
                        )
                    }
                }
            }

            // 选中提示条：进/出选择模式时**展开收起**（带弹簧），下面的列表跟着平滑
            // 下移/上移，而不是"啪"地多出一行把内容顶下去
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
            // 未连接时的空态：用户定版"未连接也留在主界面、导航栏可用"，所以文件页
            // 也会在没连接时被打开。没有这一行就是个空白网格，用户只会以为目录选错了。
            if (host == null && !loading) {
                HintText("未连接相机，无法浏览文件")
            }

            if (gridView) {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val nearEnd by androidx.compose.runtime.derivedStateOf {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= entries.lastIndex - 4
                }
                LaunchedEffect(nearEnd, hasMore) {
                    if (nearEnd && hasMore) loadMore()
                }
                LazyVerticalGrid(
                    // ★ 一排两张（用户定版：原九宫格三列）。列表项要显示"文件名 + 修改时间"，
                    //   三列时每格不到 110dp，文件名和日期都会被截成省略号，看不到完整信息。
                    columns = GridCells.Fixed(2),
                    state = gridState,
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
                                    fullscreenEntry = entry
                            },
                        )
                    }
                }
            } else {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val nearEnd by androidx.compose.runtime.derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= entries.lastIndex - 4
                }
                LaunchedEffect(nearEnd, hasMore) {
                    if (nearEnd && hasMore) loadMore()
                }
                // ★ 「几个小块拼成一个大块」（用户定版，照已配对相机那一页的样子）：
                //   行与行之间留库自带的分段缝（`SegmentedGap`），每行的圆角由它在组里的
                //   位置决定（首段上圆、末段下圆、中间两角方），拼起来是**一整块被切开的卡**。
                //   顺带把选中高亮一并治了：高亮是画在每一段自己那块面上的，段间有缝，
                //   相邻两项同时选中也不会连成一片（旧版是等宽圆角块紧挨着，两块高亮
                //   一挨上边缘就糊在一起 —— 用户反馈"太丑了、边缘连到一起"）。
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
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
                            // 列表项的增删与挪位交给弹簧（换目录时整片列表重排，
                            // 有它才是"滑过去"而不是"跳过去"）
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
                                    fullscreenEntry = entry
                            },
                            onLongClick = { longPress(entry.path) },
                        )
                    }
                }
            }
        }

        // 「开始传输」FAB：出现/消失走弹簧（缩放 + 淡变）。FAB 是个"凭空多出来的动作"，
        // 硬切进来会显得突兀；从 0.7 倍弹到 1 倍、同时淡入，读起来是"它长出来了"。
        // 位移/缩放用 spatial、淡变用 effects —— MD3E 的分工。
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
                    // 同上：别被悬浮导航的圆形按钮压住
                    .padding(bottom = FloatingNavInset),
            ) {
                if (preparing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    // FAB 拿不到交互源（见 TransfersScreen 同一处的说明），图标自己检测按下
                    MsIcon(
                        icon = MsIcon.START,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("开始传输 (${selected.size})")
            }
        }
    }

    // 全屏大预览
    //
    // ★ 预览要能在**同目录的图片之间左右滑动**，所以它拿的是"这个目录里所有可预览的
    //   文件 + 从哪一张开始"，而不是单独一张（见 FullscreenPreview 的注释）。
    //   可预览 = 扩展名属于图片（与进入预览的判断同一套），跳过文件夹。
    fullscreenEntry?.let { entry ->
        val gallery = remember(entries) {
            entries.filter { it.ext in PREVIEWABLE_EXT }
        }
        val startIndex = gallery.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        Dialog(
            onDismissRequest = { fullscreenEntry = null },
            // usePlatformDefaultWidth=false：默认弹窗左右各留边距，
            // 那种宽度下 Pager 的翻页手势会显得很局促，图片也看不全
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FullscreenPreview(
                gallery = gallery,
                startIndex = startIndex,
                isSelected = { p -> selected.contains(p) },
                onToggleSelect = { p ->
                    // ★ 同时进入选择模式：不然从预览里选中了，回到列表看不到勾选框、
                    //   底部「开始传输(n)」也不会出现 —— 用户会以为没选上。
                    selecting = true
                    toggle(p)
                },
                onDismiss = { fullscreenEntry = null },
            )
        }
    }
}

/**
 * 选中项收集：目录递归展开（**限深 4 层**，无条数上限）；IO 线程调用。
 *
 * ★ 这里**没有** 500 项上限 —— 旧版有过，后来改成"逐目录递归、分批入队"，
 *   上限就撤了（静默截断比慢更糟：用户以为选全了，实际少了一半）。
 *   现在唯一的边界是深度 4：再深的目录树在相机存储卡上不存在，
 *   而万一有环（符号链接之类）也不会把队列撑爆。
 */
private suspend fun collectAndEnqueue(
    host: String,
    entries: List<ObjectRepository.FtpEntry>,
    selected: List<String>,
    enqueue: (List<ObjectRepository.FtpEntry>) -> Unit,
    onProgress: suspend (Int, Int) -> Unit,
) {
    data class DirTask(val path: String, val depth: Int)

    val seen = HashSet<String>()
    val batch = ArrayList<ObjectRepository.FtpEntry>(128)
    var found = 0
    var dirs = 0
    suspend fun flush() {
        if (batch.isEmpty()) return
        val copy = batch.toList()
        batch.clear()
        enqueue(copy)
        onProgress(dirs, found)
    }

    for (path in selected) {
        val selectedEntry = entries.firstOrNull { it.path == path } ?: continue
        if (!selectedEntry.isDir) {
            if (seen.add(selectedEntry.path)) {
                batch.add(selectedEntry)
                found++
            }
            if (batch.size >= 128) flush()
            continue
        }

        val queue = ArrayDeque<DirTask>()
        queue.add(DirTask(selectedEntry.path, 1))
        while (queue.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val task = queue.removeFirst()
            dirs++
            onProgress(dirs, found)

            var offset = 0
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val page = ObjectRepository.listPage(host, task.path, offset, 256)
                for (e in page.entries) {
                    if (e.isDir) {
                        if (task.depth < 4) queue.add(DirTask(e.path, task.depth + 1))
                    } else if (e.ext in setOf("jpg", "jpeg", "arw") && seen.add(e.path)) {
                        batch.add(e)
                        found++
                        if (batch.size >= 128) flush()
                    }
                }
                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
            }
        }
    }
    flush()
    onProgress(dirs, found)
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
            // 圆角由它在组里的位置决定（见调用处）：与上下的行拼成一整块卡
            .clip(shape)
            // 底色不再用 secondaryContainer 直接铺 —— 那个颜色太实，几行连选时整块变成
            // 一大片紫。选中态改成 secondaryContainer，未选中是分段面的底色，
            // 两者亮度差足够看出选中，又不至于把整页染上色。
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else segmentSurfaceColor()
            )
            // ★ 选择模式下点行 = 选中/取消（与九宫格同一语义）：对勾删掉后这是唯一的切换入口
            .combinedClickable(
                onClick = if (selecting) onToggleSelect else onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★ 选择标注只用**高亮底色**（用户定版"去掉左边的对勾"）；onToggleSelect
        //   仍由整行点击承担（选择模式下点一行 = 选中/取消）
        ThumbOrPlaceholder(entry, size = 56)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 大小 + 修改时间（FTP LIST 自带，用户要求显示）
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
            // ★ 裁剪必须跟在 Card 的**外面**先做：combinedClickable 挂在 Card 之前，
            //   它的水波纹画在卡片之外、不受卡片自身圆角裁剪 —— 不裁的话选择时是
            //   一个**方角块**盖在圆角卡片上（用户反馈"圆角遮罩处理粗糙")。
            //   形状直接取 CardDefaults.shape，避免两处各写一个半径日后漂移。
            .clip(CardDefaults.shape)
            // 单击：选择模式下切换选中，普通模式打开；长按任何视图下都进选择模式
            .combinedClickable(
                onClick = if (selecting) onToggle else onClick,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            // 选中时整张卡片换底色：九宫格里缩略图占满，只靠右上角那枚对号不够显眼
            containerColor = if (selecting && isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Box {
            Column(Modifier.fillMaxWidth()) {
                // ★ 版式统一（用户要求：文件的显示和文件夹的显示要一样）：
                //   图片 = 缩略图铺满这块面；文件夹/非图片文件 = **同一块面**铺中性底
                //   + 居中的字形。原来文件夹只在格子中间浮着一枚小字形，跟铺满的缩略图
                //   是两种版式，一屏里一眼就能看出"这几格是空的"。
                //
                //   ★ 预览区**四周内缩 6dp**（用户定版）：图片原来紧贴卡片边缘，选中时
                //   的高亮底色只在图片上下的文字区看得见 —— 卡片加高一点、图与卡边留出
                //   空隙，高亮框才能在**图片上方**也露出来。
                val isImage = entry.ext in setOf("jpg", "jpeg", "arw")
                val cacheKey = if (isImage) {
                    ThumbStore.ObjectCacheKey(
                        cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
                        path = entry.path,
                        size = entry.size,
                        mtime = entry.timestamp,
                    )
                } else null
                val bmp = cacheKey?.let { ThumbStore.smallThumb(it) }
                val st = cacheKey?.let { ThumbStore.states[it.token] }
                // 内缩 6dp + 小圆角裁剪：高亮（选中底色）在图片四周露出一整圈
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .clip(MaterialTheme.shapes.small),
                ) {
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
                            // 文件夹 / 无缩略图的图片 / 其它文件 —— 三种字形，同一块面
                            when {
                                entry.isDir -> MsIcon.FOLDER
                                isImage -> MsIcon.IMAGE_PLACEHOLDER
                                else -> MsIcon.FILE_GENERIC
                            }
                        )
                    }
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                )
                // 修改日期时间（用户定版：网格里也要看得见，不只列表里有）
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
        }
    }
}

/**
 * 网格里"没有缩略图"的那块面：与图片**同一个尺寸**（整格上半部），只是换成中性底 +
 * 居中的字形。
 *
 * ★ 为什么不是一枚裸字形：那样文件夹与图片是两种版式（图片铺满、文件夹浮着一枚小
 *   图标），一屏里一眼就能看出"这几格是空的"。统一之后每一格都是"一块面 + 文件名 +
 *   时间"，只有面上的内容不同（用户要求：文件的显示和文件夹的显示要统一）。
 * ★ 底色取 `surfaceContainerHighest`：它在卡片底（`surfaceContainer`）上只高两档，
 *   是"一块面"而不是"一块色"，不会再把文件夹变成一屏里最亮的东西
 *   （此前用地板色 / 强调色圆片两次都被反馈"扎眼"）。
 */
@Composable
private fun GridPlaceholder(icon: MsIcon) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            size = 32.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 网格里可能一次出现几十个占位：关掉动效，省掉每个图标的手势检测与动画状态
            animated = false,
        )
    }
}

/** 列表行的小缩略图 / 占位（需求：无缩略图文件单独显示占位图标） */
@Composable
private fun ThumbOrPlaceholder(entry: ObjectRepository.FtpEntry, size: Int) {
    val isImage = entry.ext in setOf("jpg", "jpeg", "arw")
    val cacheKey = if (isImage) {
        ThumbStore.ObjectCacheKey(
            cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
            path = entry.path,
            size = entry.size,
            mtime = entry.timestamp,
        )
    } else null
    val bmp = cacheKey?.let { ThumbStore.smallThumb(it) }
    val st = cacheKey?.let { ThumbStore.states[it.token] }
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
            // ★ 与网格同一条规矩：文件夹 / 非图片文件都占**与缩略图同一块面**
            //   （原来文件夹是一枚 28dp 裸字形，缩略图是 56dp 的一块图，
            //   同一列里两种版式，用户要求统一）。
            else -> Box(
                Modifier
                    .size(size.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(
                    icon = when {
                        entry.isDir -> MsIcon.FOLDER
                        isImage -> MsIcon.IMAGE_PLACEHOLDER
                        else -> MsIcon.FILE_GENERIC
                    },
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    size = 26.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 列表里每一行都有一个：关掉动效（同 GridPlaceholder 的理由）
                    animated = false,
                )
            }
        }
    }
}

/**
 * 全屏大预览：**左右滑动切换（吸附+动画） + 双指缩放拖动 + 双击放大**。
 *
 * 手势分工（写清楚免得日后调乱）：
 *
 * | 手势 | 未放大（1x） | 已放大（>1x） |
 * |---|---|---|
 * | 单指拖动 | 切上一张/下一张（Pager 自带吸附与动画） | 平移图片 |
 * | 双指捏合 | 缩放 | 缩放 |
 * | 双击 | 放大到 2.5x | 复位到 1x |
 *
 * ★ **单击不再退出**（用户定版：退出只走左上角的返回键）—— 单击会与双击、与
 *   "点一下想看细节"打架，去掉后手势表更干净。
 *
 * ★ **为什么放大后要挡住 Pager 才能平移**：`HorizontalPager` 在 1x 时吃掉横向拖动，
 *   `transformable` 也吃横向拖动。做法是**只在 1x 时把拖动交给 Pager**：
 *   放大后用 `userScrollEnabled = false` 关掉 Pager 自己的滑动，横向拖动就只剩
 *   `transformable` 在接。
 *
 * @param gallery 同目录的可预览文件（顺序 = 列表顺序）
 * @param startIndex 从哪一张开始看
 * @param isSelected 某一张当前是否已选中（复用列表/网格的同一份多选状态）
 * @param onToggleSelect 切换某一张的选中态
 */
@Composable
private fun FullscreenPreview(
    gallery: List<ObjectRepository.FtpEntry>,
    startIndex: Int,
    isSelected: (String) -> Boolean,
    onToggleSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { gallery.size }

    // 缩放与平移状态。**每张图各自一份**（key = 页码）：换到下一张时是
    // "新的图、新的 1x"，而不是把上一张的放大倍数带过去。
    var scale by remember(pagerState.currentPage) { mutableStateOf(1f) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }

    // 1x 才让 Pager 能滑（放大后横向拖动归平移，见上面的分工表）
    val atBaseScale = scale <= 1.001f

    var infoOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = atBaseScale,
        ) { page ->
            val entry = gallery.getOrNull(page)
            if (entry != null) {
                PreviewPage(
                    entry = entry,
                    scale = if (page == pagerState.currentPage) scale else 1f,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onScale = { if (page == pagerState.currentPage) scale = it },
                    onOffset = { if (page == pagerState.currentPage) offset = it },
                )
            }
        }

        // ===== 顶部：返回键 + 照片名（大）/ 拍摄时间 · 序号（小） =====
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                MsIcon(
                    icon = MsIcon.ARROW_BACK,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(6.dp))
            val current = gallery.getOrNull(pagerState.currentPage)
            Column(Modifier.weight(1f)) {
                Text(
                    current?.name ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    val date = formatTime(current.timestamp)
                    val seq = "${pagerState.currentPage + 1}/${gallery.size}"
                    Text(
                        // 序号跟在日期后面（用户定版："30/35" 写在小标题的日期后）
                        if (date.isNotBlank()) "$date · $seq" else seq,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ===== 底部：左 = 照片信息（EXIF）；右 = 选中 =====
        val current = gallery.getOrNull(pagerState.currentPage)
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { infoOpen = true },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.45f),
                    contentColor = Color.White,
                ),
            ) {
                MsIcon(
                    icon = MsIcon.INFO,
                    contentDescription = "照片信息",
                    tint = Color.White,
                )
            }
            if (current != null) {
                val sel = isSelected(current.path)
                FilledIconButton(
                    onClick = { onToggleSelect(current.path) },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (sel) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.45f),
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary
                        else Color.White,
                    ),
                ) {
                    MsIcon(
                        icon = MsIcon.CHECK,
                        contentDescription = if (sel) "取消选中" else "选中",
                        tint = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White,
                    )
                }
            }
        }
    }

    if (infoOpen) {
        gallery.getOrNull(pagerState.currentPage)?.let { current ->
            PhotoInfoDialog(entry = current, onDismiss = { infoOpen = false })
        }
    }
}

/**
 * 照片信息弹窗：文件名 / 拍摄时间 / 大小 / 分辨率 + 预览图 EXIF 里能读到的拍摄参数
 * （机型 / 快门 / 光圈 / ISO / 焦距 —— 相机端生成的预览图带什么就显示什么，没有的行不出）。
 */
@Composable
private fun PhotoInfoDialog(entry: ObjectRepository.FtpEntry, onDismiss: () -> Unit) {
    val key = ThumbStore.ObjectCacheKey(
        cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
        path = entry.path,
        size = entry.size,
        mtime = entry.timestamp,
    )
    var meta by remember(key) { mutableStateOf<ThumbStore.PreviewMeta?>(null) }
    LaunchedEffect(key) {
        meta = withContext(Dispatchers.IO) { ThumbStore.previewMeta(key) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("照片信息") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val rows = buildList {
                    add("文件名" to entry.name)
                    val date = formatTime(entry.timestamp)
                    if (date.isNotBlank()) add("拍摄时间" to date)
                    if (entry.size > 0) add("文件大小" to formatSize(entry.size))
                    meta?.let { m ->
                        if (m.width > 0) add("分辨率" to "${m.width} × ${m.height}")
                        addAll(m.exif.toList())
                    }
                }
                rows.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(88.dp),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 预览里的一张：拉图 + 缩放平移 + 手势。
 *
 * 拆成单独的 composable 是为了让"每张图自己的缩放状态"天然隔离
 * （父层按页码给状态，这里只负责渲染与手势），也避免 Pager 预取相邻页时
 * 把当前页的手势状态搅在一起。
 */
@Composable
private fun PreviewPage(
    entry: ObjectRepository.FtpEntry,
    scale: Float,
    offset: Offset,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
) {
    val key = ThumbStore.ObjectCacheKey(
        cameraGuid = ConnectionCenter.camera?.guidHex.orEmpty(),
        path = entry.path,
        size = entry.size,
        mtime = entry.timestamp,
    )
    var bmp by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val b = withContext(Dispatchers.IO) { ThumbStore.fetchPreviewBlocking(key) }
        if (b != null) bmp = b else failed = true
    }

    // 容器尺寸：夹紧平移要用它（把图片拖出屏幕就找不回来了）
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // ★★ 手势回调必须读"当下值"（用户实测两个 bug 的共同病根）：
    //   `pointerInput(Unit)` 与 `rememberTransformableState` 的 lambda 都只组合一次，
    //   直接捕获 `scale`/`offset` 拿到的是**初值 1f/0** —— 于是"再次双击复位"永远
    //   走放大分支（scale 判断恒假）、捏合也从 1f 起算。用 rememberUpdatedState
    //   把最新值转交给这些长命 lambda。
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (currentScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        // 缩放变化时把平移也按比例收敛，否则缩小回去之后画面还偏在一边
        val ratio = if (currentScale > 0f) next / currentScale else 1f
        val panned = (currentOffset + panChange) * ratio
        onScale(next)
        // ★ 夹紧：可平移的最大范围 = 图片溢出容器的部分的一半（缩放以中心为基准）。
        //   不夹的话放大后能把图拖到屏幕外，只剩黑底 —— 用户会以为图没了。
        val maxX = (boxSize.width * (next - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (next - 1f) / 2f).coerceAtLeast(0f)
        onOffset(
            Offset(
                panned.x.coerceIn(-maxX, maxX),
                panned.y.coerceIn(-maxY, maxY),
            ),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // 手势①：双击放大/复位。★ 单击不做事（用户定版：去掉"点击任意处返回"）。
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentScale > 1.001f) {
                            onScale(1f)
                            onOffset(Offset.Zero)
                        } else {
                            onScale(DOUBLE_TAP_SCALE)
                        }
                    },
                )
            }
            // 手势②：双指缩放 + 平移。
            // ★ canPan：**单指平移只在已放大时归 transformable**（用户实测 1x 时它把
            //   横向拖动整个吃掉、Pager 永远翻不了页）；1x 时单指拖动不消费 →
            //   交给 Pager 翻页。双指捏合（zoom）不受 canPan 影响，任何时刻可用。
            .transformable(transformState, canPan = { currentScale > 1.001f })
            // 缩放的视觉表现：scale 改大小、offset 改位置。
            // ★ 用 graphicsLayer 而不是 Modifier.scale：这两个变换都在**绘制阶段**生效，
            //   不触发布局 —— 缩放跟手才不会卡。
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                b,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                // Fit：整图可见优先。放大靠手势，不靠 contentScale
                // （预览图是 1616×1080，屏幕放得下；用 FillBounds 会先裁掉一部分）
                contentScale = ContentScale.Fit,
            )
            failed -> Text("无法获取预览", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/** 可全屏预览的扩展名（与进入预览时的判断同一套，别再写第二份）。 */
private val PREVIEWABLE_EXT = setOf("jpg", "jpeg", "arw")

/** 双击放大到的倍数。2.5 是"看清细节"与"不至于迷路"之间的常用值。 */
private const val DOUBLE_TAP_SCALE = 2.5f

/** 缩放下限与上限。上限 5x：预览图是 1616×1080，再放大就已明显糊了。 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

private fun formatSize(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** 修改时间（FTP LIST 解析）；无效返回空串 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}
