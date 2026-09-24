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

/**
 * 是否用深色。
 *
 * ★ **默认深色**（用户定版）：MD3E 那套参考图就是深色的，先给到那个观感；
 *   设置里"深浅色"仍可切 跟随系统 / 浅色 / 深色。
 *
 * ★ 抽成独立函数是因为它不止 Compose 用：`enableEdgeToEdge` 也要靠它决定
 *   状态栏图标是深是浅（深色底配深色图标 = 屏幕上什么都看不见）。
 */
@Composable
fun useDarkTheme(): Boolean = when (SettingsRepo.darkMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

/**
 * 主题基座 = **`MaterialExpressiveTheme` + `MotionScheme.expressive()`**。
 *
 * ★★ 形状与字体**都不传**，用库自己的 expressive 默认值 —— 那才是 MD3E 的规格本身。
 *   这些默认值在 1.4.0 时代是 `internal`（拿不到，只能在工程里手抄一份），
 *   升到 1.5.0-alpha 之后全面公开，于是"我们抄的"可以还给库：
 *
 * | 曾经内抄的东西 | 现在的来源 |
 * |---|---|
 * | 形状表（extraSmall…extraExtraLarge，含 largeIncreased / extraLargeIncreased） | `MaterialExpressiveTheme` 默认 |
 * | 字体表（expressive 的加粗标题层） | `MaterialExpressiveTheme` 默认 |
 * | 动效令牌（spatial 0.8/380、fast 0.6/800、slow 0.8/200；effects 0.8/1600、0.6/3800、0.8/800） | `MotionScheme.expressive()` |
 * | 分组列表的分段形状 / 分段配色 / 段间距 | `ListItemDefaults.segmentedShapes / segmentedColors / segmentedGap` |
 *
 * ★ 换成 expressive 主题之后，**组件的按压与切换动效也跟着换了**：Switch、SegmentedButton、
 *   NavigationBar、Menu、Checkbox、FAB 这些都从 `LocalMotionScheme` 取规格，不再是我们
 *   改不动的那套基线参数。这一层是 1.4.0 时代最遗憾的地方，现在补上了。
 *
 * ★ 版本代价：material3 稳定线仍是 1.4.0，expressive 只在 1.5.0-alpha 里，
 *   所以依赖走的是 **`compose-bom-alpha`**（见 `gradle/libs.versions.toml` 里的说明）。
 */
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
