package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * **普通动画的对话框**（用户定版："EXIF 窗口那个飞出动画去掉，就普通的动画就可以"）。
 *
 * Material3 的 `AlertDialog` 这一版带的是 expressive 的"飞入/飞出"位移，用户要的是
 * 传统那种**淡入 + 轻微缩放**（0.94 → 1.0），所以这里自己搭一个：内容仍是
 * `surfaceContainerHigh` 的圆角面（与原来的视觉一致），只是动画换成最朴素的。
 *
 * `decorFitsSystemWindows = false`：窗口铺满整屏（含状态栏/手势栏区域），
 * 内容靠各自的 inset 避让 —— 这样**沉浸**的界面（照片预览）不会被系统栏切掉一块。
 */
@Composable
fun PlainDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 出现动画：一次淡入 + 轻微放大。用 LaunchedEffect 翻一个布尔驱动，
        // 结束态就是最终态（没有回弹、没有位移 —— 这就是"普通"）。
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val alpha by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(durationMillis = 150),
            label = "plainDlgAlpha",
        )
        val pop by animateFloatAsState(
            targetValue = if (shown) 1f else 0.94f,
            animationSpec = tween(durationMillis = 170, easing = FastOutSlowInEasing),
            label = "plainDlgScale",
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier.graphicsLayer {
                    this.alpha = alpha
                    scaleX = pop
                    scaleY = pop
                },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                content = content,
            )
        }
    }
}
