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
                    
                    .clip(shape)
                    .background(color),
            )
        }
    }
}












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
