package com.bi2qfa.sonyconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bi2qfa.sonyconnect.data.SettingsRepo










@Composable
fun useDarkTheme(): Boolean = when (SettingsRepo.darkMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}






















@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SonyConnectTheme(dark: Boolean = useDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (SettingsRepo.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = Seeds[SettingsRepo.seedIndex.coerceIn(0, Seeds.size - 1)]
        if (dark) seed.dark() else seed.light()
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
