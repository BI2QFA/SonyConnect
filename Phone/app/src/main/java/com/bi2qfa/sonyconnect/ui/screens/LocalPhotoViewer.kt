package com.bi2qfa.sonyconnect.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.bi2qfa.sonyconnect.data.LocalPhoto
import com.bi2qfa.sonyconnect.ui.components.ExifInfoDialog
import com.bi2qfa.sonyconnect.transfer.TransferItem
import com.bi2qfa.sonyconnect.ui.components.MsIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Brush
import com.bi2qfa.sonyconnect.ui.FloatingNav
import com.bi2qfa.sonyconnect.ui.NavItemSpec
import com.bi2qfa.sonyconnect.ui.PhotoRotate
import com.bi2qfa.sonyconnect.ui.components.AppHeader

/**
 * **已下载照片的查看器**（传输页点开用）。
 *
 * 与文件页的大预览是同一套版式（黑底、顶部文件名 + 时间 · 序号、左右翻页、
 * 双击放大、双指缩放平移），两处差别就是用户定版的那两条：
 * <ul>
 *   <li>左下角不是"逆时针旋转"，而是**照片信息**（EXIF 弹窗）；</li>
 *   <li>右下角**没有**选择按钮 —— 这里不是选择场景。</li>
 * </ul>
 *
 * 数据来源是**手机上的完整文件**（不是相机预览缓存），所以 RAW 也要能看：
 * 见 [LocalPhoto]（ARW 走内嵌大预览，EXIF 直接读原文件）。
 *
 * @param items 可翻页的照片（调用方只放"已传输完成且可显示"的那些）
 * @param startIndex 从哪一张开始
 * @param uriOf 取某一项的本地文件 Uri；拿不到（被移动/删除）返回 null，页面显示失败文案
 */
@Composable
fun LocalPhotoViewer(
    items: List<TransferItem>,
    startIndex: Int,
    uriOf: (TransferItem) -> Uri?,
    onDismiss: () -> Unit,
    /** 控制中心面板里的两件事：由 MainActivity 一路透传（预览器里的面板要用）。 */
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) {
        items.size
    }
    var scale by remember(pagerState.currentPage) { mutableStateOf(1f) }
    var offset by remember(pagerState.currentPage) { mutableStateOf(Offset.Zero) }
    val atBaseScale = scale <= 1.001f
    var infoOpen by remember { mutableStateOf(false) }
    // 当前这一张（顶部文案、EXIF 弹窗、底部按钮都用它）
    val current = items.getOrNull(pagerState.currentPage)

    // 旋转：点了几次（每张各自记）/ 已转完几段（页面回报）——与文件页大预览同一套限流
    // （排队深度 ≤ 2：正在播的那段 + 至多一段待播）。
    val rotationByPath = remember { mutableStateMapOf<String, Int>() }
    val bakedByPath = remember { mutableStateMapOf<String, Int>() }

    // ★ 与主界面同构（用户定版"预览器背景色和主界面一致"）：surface 底 +
    //   AppHeader 在内容之上 + 照片区独立 + 底部悬浮导航盖在内容上。
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 照片铺满整屏（标题与导航浮在它上面）→ 在整屏里居中
        Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = atBaseScale,
        ) { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                LocalPhotoPage(
                    item = item,
                    uri = uriOf(item),
                    scale = if (page == pagerState.currentPage) scale else 1f,
                    offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                    onScale = { if (page == pagerState.currentPage) scale = it },
                    onOffset = { if (page == pagerState.currentPage) offset = it },
                    turnsOf = { rotationByPath[item.path] ?: 0 },
                    onBaked = { n -> bakedByPath[item.path] = n },
                )
            }
        }

        // ===== 顶部：标题浮层（照片满屏，垫一层压暗渐变保证白字可读）=====
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                ),
        ) {
        AppHeader(
            title = current?.name ?: "",
            subtitle = current?.let { it1 ->
                val date = formatTime(it1.mtime)
                val seq = "${pagerState.currentPage + 1}/${items.size}"
                if (date.isNotBlank()) "$date · $seq" else seq
            },
            onBack = onDismiss,
        )
        }

        // ===== 底部：与主界面同一套悬浮导航（药丸两个动作 + 小球/控制中心面板）=====
        //   小球与控制中心面板整块复用主界面（同一套动画与功能，用户定版）；
        //   药丸 + 小球是一个整体、横轴居中、高度与主界面相同（FloatingNav 的布局负责）。
        FloatingNav(
            modifier = Modifier.align(Alignment.BottomCenter),
            onOpenSettings = onOpenSettings,
            onSwitchDevice = onSwitchDevice,
            actions = listOf(
                NavItemSpec(MsIcon.ROTATE_CCW, "旋转", false) {
                    val p = current ?: return@NavItemSpec
                    val requested = rotationByPath[p.path] ?: 0
                    val done = bakedByPath[p.path] ?: 0
                    if (requested - done >= 2) return@NavItemSpec
                    rotationByPath[p.path] = requested + 1
                    offset = Offset.Zero
                },
                NavItemSpec(MsIcon.INFO, "照片信息", false) { infoOpen = true },
            ),
        )
        }
    }

    if (infoOpen && current != null) {
        val uri = uriOf(current)
        LocalExifDialog(item = current, uri = uri, onDismiss = { infoOpen = false })
    }
}

/** 一张：拉本地文件 → 显示；手势与旋转逻辑与文件页大预览**逐条一致**。 */
@Composable
private fun LocalPhotoPage(
    item: TransferItem,
    uri: Uri?,
    scale: Float,
    offset: Offset,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
    turnsOf: () -> Int = { 0 },
    onBaked: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    /** 这张图**已经转过几段**（绝对次数，父层按钮的限流依据）。 */
    var baked by remember(item.path) { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        if (uri == null) {
            failed = true
            return@LaunchedEffect
        }
        val b = withContext(Dispatchers.IO) { LocalPhoto.loadPreview(context, uri, item.name) }
        if (b == null) {
            failed = true
            return@LaunchedEffect
        }
        // 首帧把已点过的旋转**瞬时**补上（不播动画）；状态落定后才赋 bmp
        // （旋转协程靠"bmp 从无到有"唤醒，必须等这些都就位）
        val already = turnsOf()
        val loaded = PhotoRotate.rotateSteps(b, already)
        baked = already
        onBaked(already)
        bmp = loaded
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // 旋转：常驻协程一段段消化（不打断当前动画；排队深度由按钮限流）
    val anim = remember(item.path) { Animatable(0f) }
    LaunchedEffect(item.path) {
        snapshotFlow { turnsOf() to (bmp != null) }.collect { (target, ready) ->
            if (!ready) return@collect
            while (baked < target) {
                val cur = bmp ?: break
                anim.snapTo(0f)
                anim.animateTo(
                    PhotoRotate.STEP_DEG,
                    tween(durationMillis = PhotoRotate.STEP_MS, easing = FastOutSlowInEasing),
                )
                bmp = PhotoRotate.rotate(cur, PhotoRotate.STEP_DEG)
                baked += 1
                onBaked(baked)
                anim.snapTo(0f)
            }
        }
    }
    // 手势 lambda 只组合一次，必须读"当下值"（与文件页大预览同一处坑）
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (currentScale * zoomChange).coerceIn(MIN_VIEW_SCALE, MAX_VIEW_SCALE)
        val ratio = if (currentScale > 0f) next / currentScale else 1f
        val panned = (currentOffset + panChange) * ratio
        onScale(next)
        val maxX = (boxSize.width * (next - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxSize.height * (next - 1f) / 2f).coerceAtLeast(0f)
        onOffset(Offset(panned.x.coerceIn(-maxX, maxX), panned.y.coerceIn(-maxY, maxY)))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentScale > 1.001f) {
                            onScale(1f)
                            onOffset(Offset.Zero)
                        } else {
                            onScale(DOUBLE_TAP_VIEW_SCALE)
                        }
                    },
                )
            }
            .transformable(transformState, canPan = { currentScale > 1.001f })
            .graphicsLayer {
                // 旋转中连续适配尺寸（不然烘焙那一刻会突然跳一下，见 PhotoRotate 说明）
                val rotFit = PhotoRotate.fit(bmp, boxSize, anim.value)
                scaleX = scale * rotFit
                scaleY = scale * rotFit
                translationX = offset.x
                translationY = offset.y
                rotationZ = anim.value
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(b, contentDescription = item.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            failed -> Text("无法读取文件", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * 照片信息弹窗（用户定版的行与顺序）：
 * 文件名 / 文件格式 / 拍摄日期时间 / 相机型号 / 镜头型号 / 焦距 / 光圈 / 快门速度 / EV / ISO。
 *
 * ★ 2.7.0：版式搬到共享组件 [ExifInfoDialog]（文件页相机预览也用它，两处必须一致）；
 *   本函数只负责"本地文件 → 行"的数据源。
 */
@Composable
private fun LocalExifDialog(item: TransferItem, uri: Uri?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rows by remember(uri, item.id) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    LaunchedEffect(uri, item.id) {
        rows = withContext(Dispatchers.IO) {
            if (uri == null) listOf("文件名" to item.name, "文件格式" to
                item.name.substringAfterLast('.', "").uppercase())
            else LocalPhoto.readExif(context, uri, item.name)
        }
    }
    ExifInfoDialog(rows = rows, onDismiss = onDismiss)
}

/** 传输时间（与传输页列表同一口径：`yyyy-MM-dd HH:mm`）；0 = 不知道则空串。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
}

/** 与文件页大预览同一套缩放上下限（两处手感要一致）。 */
private const val MIN_VIEW_SCALE = 1f
private const val MAX_VIEW_SCALE = 5f
private const val DOUBLE_TAP_VIEW_SCALE = 2.5f
