package com.bi2qfa.sonyconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bi2qfa.sonyconnect.data.SettingsRepo

private val Seeds = listOf(
    Triple(Color(0xFF1565C0), Color(0xFF546E7A), Color(0xFF00838F)),
    Triple(Color(0xFF7E57C2), Color(0xFF9575CD), Color(0xFF26A69A)),
    Triple(Color(0xFF2E7D32), Color(0xFF558B2F), Color(0xFF9E9D24)),
    Triple(Color(0xFFEF6C00), Color(0xFFF57C00), Color(0xFFD84315)),
)

private fun scheme(seed: Triple<Color, Color, Color>, dark: Boolean): ColorScheme {
    val (p, s, t) = seed
    return if (dark) {
        darkColorScheme(primary = p, secondary = s, tertiary = t)
    } else {
        lightColorScheme(primary = p, secondary = s, tertiary = t)
    }
}

@Composable
fun SonyConnectTheme(content: @Composable () -> Unit) {
    val dark = when (SettingsRepo.darkMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = if (SettingsRepo.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        scheme(Seeds[SettingsRepo.seedIndex.coerceIn(0, Seeds.size - 1)], dark)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
