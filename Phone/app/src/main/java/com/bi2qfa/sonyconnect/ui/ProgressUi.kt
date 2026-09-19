package com.bi2qfa.sonyconnect.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.ui.theme.Motion

/**
 * 全应用统一的细进度条。
 *
 * ★ 手摆，**不要改回 `LinearProgressIndicator`**：MD3 那个不让我们控制"完成段"的
 * 落地形状（它的 `gapSize`/`drawStopIndicator` 都是实验性参数，而且默认会在末尾
 * 留缺口/小圆点），手摆之后形状完全归我们。
 *
 * ★★ **完成段必须是圆头（圆弧）**，不能做成直边 —— 直边"没有圆弧、不优雅"
 * （用户原话）。所以完成段自己也裁成 `RoundedCornerShape(50)`：
 * 左端与底轨的左圆角重合，右端露出一个半圆头；进度 100% 时两端与底轨完全重合，
 * 不会出现多余痕迹。
 *
 * 底轨（用户定版）：不用灰色 surfaceVariant，而是当前进度色的浅色同系底
 * （primary 22% 透明度叠在卡片上即得），电量/下载/缩略图全部一致。
 *
 * ★ 进度值走**弹簧**（`Motion.spatialDefault()`）：下载进度、电量、缩略图张数都是
 *   按秒跳的数字，直接喂进来进度条是一格一格蹦的；让它追着目标值滑，
 *   观感是"平滑地爬上去"，也顺带把偶发的数值回退（重算、续传）抹平。
 */
@Composable
internal fun SlimProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.22f),
) {
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.spatialDefault(),
        label = "slimProgress",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(trackColor),
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(f)
                    // 圆头：完成段自己的两端也圆角，头部那个弧就是它的右半圆
                    .clip(shape)
                    .background(color),
            )
        }
    }
}

/**
 * 容量格式化（存储卡总容量、已用空间）。未知（负数）返回 "—"。
 *
 * ★ 与 `FilesScreen` / `TransfersScreen` 里那两份私有 `formatSize` 的**区别**：
 *   那两份格式化的是单个文件，量级到几十 MB 就封顶，所以只到 MB 为止；
 *   存储卡是 GB 级的量，没有 GB 分支的话 32GB 会印成 "30518 MB"（用户读不出量级）。
 *   这里补上 GB/TB，GB 段带一位小数。
 *
 * 进制用 1024（与工程里既有那三份一致），不是硬盘厂商的 1000 —— 同一块卡在
 * 相机菜单里和这里显示的数字要对得上，就得跟相机走。
 */
internal fun formatCapacity(bytes: Long): String {
    if (bytes < 0) return "—"
    val kb = 1L shl 10
    val mb = 1L shl 20
    val gb = 1L shl 30
    val tb = 1L shl 40
    return when {
        bytes >= tb -> "%.2f TB".format(bytes / tb.toDouble())
        bytes >= gb -> "%.1f GB".format(bytes / gb.toDouble())
        bytes >= mb -> "%.0f MB".format(bytes / mb.toDouble())
        bytes >= kb -> "%.0f KB".format(bytes / kb.toDouble())
        else -> "$bytes B"
    }
}
