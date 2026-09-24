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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.bi2qfa.sonyconnect.ui.components.DownloadDirMissingDialog
import com.bi2qfa.sonyconnect.ui.components.HintText
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.RowIconAction
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.ui.components.MsIcon
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.bi2qfa.sonyconnect.data.LocalPhoto
import androidx.compose.runtime.mutableIntStateOf
import com.bi2qfa.sonyconnect.ui.components.WholeAreaPressScope
import com.bi2qfa.sonyconnect.ui.components.LocalRowInteraction

/**
 * 传输页：每项文件一张卡片、独立进度条与状态；
 * FAB 统一开始 / 停止（停止=整批暂停，进度保留可续传）。
 * FAB 状态跟随 TransferStore.batchRunning（响应式——修"停止后按钮不回弹"）。
 * 点已完成项 → 系统选择器打开文件；右上倒三角菜单：删除已完成 / 删除所有任务
 * （红色，带确认弹窗；只删任务记录，绝不动已下载的文件）。
 *
 * MD3E 版式：段落小标题 + 设备副标题（右上角放任务菜单）→ 每项任务一张 28dp 卡片。
 * 一项一张卡（而不是原来的一行行排在一个列表里）是因为**每项自带进度条**：
 * 卡片把"文件名 / 状态 / 进度 / 字节数"框成一件独立的事，一屏十项也不会串行。
 */
@Composable
fun TransfersScreen(
    /** 控制中心面板里的两件事：由 MainActivity 透传（预览器里的面板要用）。 */
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val items = TransferStore.items
    val running = TransferStore.batchRunning
    val scope = rememberCoroutineScope()

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { DownloadService.start(context) }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    /** 下载目录预检失败（目录被删/权限回收）→ 弹窗，文件一律不入队（用户定版）。 */
    var dirMissing by remember { mutableStateOf(false) }

    // 应用内照片查看器：点开时把"这批可看的照片"钉住（之后列表变化不影响正在看的这一轮）
    var viewerItems by remember { mutableStateOf<List<TransferItem>?>(null) }
    var viewerIndex by remember { mutableIntStateOf(0) }

    /** 长按已完成项（用户定版）：交给系统选择器"用其他应用打开"。 */
    fun openWithOtherApp(item: TransferItem) {
        val path = item.path
        // 与下载落盘走同一个路径函数：两边各算一次迟早会不一致，
        // 表现是"显示已完成、点开却说文件不存在"
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

    fun openFile(item: TransferItem) {
        if (LocalPhoto.openable(item.name)) {
            val gallery = items.filter {
                it.state == TransferState.DONE && LocalPhoto.openable(it.name)
            }
            val idx = gallery.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                viewerItems = gallery
                viewerIndex = idx
                return
            }
        }
        openWithOtherApp(item)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ★ 设备那一行**整行删掉**（用户定版）：设备码（"e0fa60c4"）是"只用在后端做
            //   队列隔离"的东西，前端不展示。队列按设备隔离仍然是后端的事
            //   （TransferStore 按设备码分桶），界面只需要回答"这些任务怎么样了"。
            // ★ 倒三角因此从"独占一行"并入**筛选芯片那一行**（用户反馈：设备行删掉之后
            //   那颗倒三角独自浮在上面、位置偏高，下面的内容还得往下让）。现在是这一行的
            //   行尾动作，与芯片垂直居中对齐，上面那段空行也就不存在了。
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
                // 倒三角展开菜单（用户定版：替换原"清除已完成"按钮）
                Box {
                    val menuSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { menuOpen = true },
                        interactionSource = menuSource,
                    ) {
                        MsIcon(
                            // 倒三角（arrow_drop_down）：菜单展开的通用记号。原来的
                            // water_drop 是水滴，谁也看不出它是"菜单"（用户定版换掉）
                            icon = MsIcon.MENU_DROP,
                            contentDescription = "任务菜单",
                            interactionSource = menuSource,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // ★ MD3E 的菜单观感在这两个令牌上（用户反馈"展开面板没适配 MD3E"）：
                        //   形状取 MenuDefaults.shape（大圆角）、容器取 MenuDefaults.containerColor
                        //   （surfaceContainer 那一档）。原来这里写死
                        //   containerColor = surfaceContainerHigh —— 比 MD3E 的容器亮一档，
                        //   圆角也没给，于是这块面板看着像个方盒子。
                        //   ⚠ MenuDefaults 的 tonalElevation / shadowElevation 在 Kotlin 侧
                        //   取不到（编译期 Unresolved reference），而 DropdownMenu 的默认值
                        //   本来就走那两个 —— 不必也不该显式传。
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

            // ★ 「几个小块拼成一个大块」（用户定版，照已配对相机那一页的样子）：
            //   段与段之间留库自带的分段缝，每段的圆角由它在组里的位置决定，
            //   拼起来是**一整块被切开的卡**（原来是一项一张独立卡片，一行一个圆角块）。
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
                    // 复用设置页那一段（[Segment]）：同一种"组里的一段"，
                    // 形状/底色/水波纹裁剪都由它统一保证，"几小块拼一大块"才拼得齐
                    Segment(
                        index = index,
                        count = count,
                        // 单击 = 应用内查看（图片）/ 系统选择器（其它）；
                        // 长按 = 一律交给系统选择器"用其他应用打开"（用户定版）
                        onClick = if (done) ({ openFile(item) }) else null,
                        onLongClick = if (done) ({ openWithOtherApp(item) }) else null,
                        // 增删与挪位走弹簧：删掉一项时下面的段是"滑上来"而不是"跳上来"
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
                                        TransferState.DONE -> MsIcon.CHECK
                                        TransferState.FAILED -> MsIcon.INFO
                                        else -> MsIcon.THUMB_DOWNLOAD
                                    },
                                    when (item.state) {
                                        TransferState.DONE -> IconTints.Accent
                                        TransferState.FAILED -> IconTints.Error
                                        else -> IconTints.Accent
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
                                    // 已完成不再写状态字（用户定版："已完成 · 点此打开"整句删掉）。
                                    // 那一行是多余的：左边绿底对号 + 进度条已经满格，都说明传完了；
                                    // "点此打开"也早就是点击即打开，不需要再教。
                                    // ★ 这一行给已完成项写**源文件的修改时间**（用户要求：传完的
                                    //   文件在文件名下方要有修改时间）；还没传完的项仍写状态字。
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
                                        MsIcon.TRASH,
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

        // 列表为空、或全部任务都已结束时隐藏 FAB（用户定版）。
        // 出现/消失走弹簧（缩放 + 淡变）—— 同文件页那颗 FAB 的处理。
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
            // ★ 整颗按钮都能驱动图标的按下变形（用户实测："只有精准按到图标才会变形"）：
            //   FAB 拿不到库的交互源，所以由这个壳用 Initial pass 观察**整块区域**的按下，
            //   再经 LocalRowInteraction 把信号递给图标。
            WholeAreaPressScope(
                modifier = Modifier
                    // ★ 与文件页的"开始传输"**同一个高度**（用户定版：两个都按文件页
                    //   的高度再降低一点）。两处用同一个算式，改一处不会漂。
                    .padding(horizontal = 20.dp)
                    .padding(bottom = FloatingNavInset - 12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (running) {
                            DownloadService.stopCurrent(context)
                        } else if (ConnectionCenter.state != ConnectionCenter.State.CONNECTED) {
                            // 没连相机就点"开始传输"：说清楚并按兵不动（服务端也有一道同样的闸）
                            android.widget.Toast.makeText(
                                context, "相机未连接，无法开始传输", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            // ★ 下载目录预检（用户定版：点"开始传输"那一刻就查，不存在则弹窗、
                            //   一个文件都不入队）：SAF query 是阻塞调用，放 IO 线程；
                            //   预检通过才继续原有的"重置失败项 + 起服务"流程。
                            scope.launch {
                                val dirOk = withContext(Dispatchers.IO) {
                                    StorageSink.downloadDirAvailable(context, SettingsRepo.downloadTreeUri)
                                }
                                if (!dirOk) {
                                    dirMissing = true
                                    return@launch
                                }
                                TransferStore.resetFailedForStart()
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    DownloadService.start(context)
                                }
                            }
                        }
                    },
                ) {
                    MsIcon(
                        icon = if (running) MsIcon.STOP else MsIcon.START,
                        contentDescription = null,
                        animated = true,
                        interactionSource = LocalRowInteraction.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "停止传输" else "开始传输")
                }
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
                        // 下载中删除全部任务：立刻停掉当前传输（用户定版：
                        // 任务全没了传输就该结束，而不是空转到当前文件下完）
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

    // ★ 用户定版：弹窗的"选择目录"**直接拉起系统目录选择器**（不再绕设置页）
    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
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
        // 下载目录不可用：弹窗提示 + 给一条"去设置"的路（预检已拦，未入队、服务未启动）
        if (dirMissing) {
            DownloadDirMissingDialog(
                dirLabel = SettingsRepo.downloadDirLabel(),
                onPickDir = { treePicker.launch(null) },
                onDismiss = { dirMissing = false },
            )
        }

        // 应用内照片查看器：**用 Dialog 包一层**（与文件页大预览同一做法）——
        // 全屏覆盖 + 系统返回键自动关掉，不必自己再接 BackHandler。
        // usePlatformDefaultWidth=false：默认弹窗左右留边，预览图会看不全。
        if (viewerItems != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { viewerItems = null },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    // 铺满整屏（含状态栏/手势栏区域）→ 预览页真正沉浸
                    decorFitsSystemWindows = false,
                ),
            ) {
                LocalPhotoViewer(
                    items = viewerItems.orEmpty(),
                    startIndex = viewerIndex,
                    onOpenSettings = onOpenSettings,
                    onSwitchDevice = onSwitchDevice,
                    uriOf = { it ->
                        val rel = StorageSink.localRelPath(it.deviceDir, it.path)
                        StorageSink.openUriOrNull(context, SettingsRepo.downloadTreeUri, rel)
                    },
                    onDismiss = { viewerItems = null },
                )
            }
        }
    }
}

/** 队列计数的小药丸（等待 / 完成 / 失败）。 */
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

/**
 * 状态行文案。**已完成返回空串 = 不显示那一行**（用户定版："已完成 · 点此打开"整句删掉）。
 * 左边那枚绿底对号 + 满格进度条已经把"传完了"说清楚，不需要再写一遍。
 */
private fun stateLabel(item: TransferItem): String = when (item.state) {
    TransferState.QUEUED -> "等待传输"
    TransferState.RUNNING -> "传输中"
    TransferState.DONE -> ""
    TransferState.FAILED -> "失败（开始传输时自动续传）"
}

/** 源文件修改时间（`FTP LIST` 解析得到）；0 或非法值返回空串，界面就不显示这一行 */
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
