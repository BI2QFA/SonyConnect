package com.bi2qfa.sonyconnect.ui

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

@Composable
internal fun SlimProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.22f),
) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = trackColor,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
