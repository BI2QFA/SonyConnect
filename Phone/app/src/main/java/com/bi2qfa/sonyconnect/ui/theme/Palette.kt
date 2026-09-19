package com.bi2qfa.sonyconnect.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 色彩令牌：MD3E 的**色调板（tonal palette）**。
 *
 * ★ 为什么自己按色调把整族算出来，而不是像以前那样只给
 *   `darkColorScheme(primary=…, secondary=…, tertiary=…)` 三个色：
 *   只给三个色时，容器色那一整族（primaryContainer / surfaceContainer 系列 /
 *   fixed 系列）会**落回 material3 的内置基线（出厂紫）**。于是"换强调色"只换了
 *   一处，卡片、导航药丸、选中行仍是紫的 —— 一屏里两套色系打架。这里按 MD3 的
 *   tone 表把每族 0~100 一次算全，换色是**整屏一起换**。
 *
 * ★ 算法是 HSL 版（`Color.hsl`），不是 MD3 原版的 HCT：tone 数值与官方令牌对齐，
 *   色相直接取种子色、彩度由每个色族给一个固定值去逼近 HCT 的手感。够用且零依赖 ——
 *   material3 的 `TonalPalette` / HCT 都不对外（`TonalPalette` 只有一个 88 参数的
 *   内部构造函数），引内部实现正是这个工程一直避免的事。
 */

/** 一个色族：固定色相 + 固定彩度，按 tone（0~100）取色。 */
private class Ramp(hue: Float, private val saturation: Float) {
    private val h = ((hue % 360f) + 360f) % 360f
    operator fun get(tone: Int): Color = Color.hsl(h, saturation, tone / 100f)
}

/** 种子色的色相（彩度不用种子的：种子是给人看的大色块，直接拿来当色调板太艳）。 */
private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

/**
 * 深色方案。tone 数字与 MD3 官方深色令牌一一对应：
 * 强调色取 tone 80（浅而艳，压在深底上才看得清），容器取 tone 30，
 * 中性面从 tone 4（最底）到 tone 22（最亮的面）铺开。
 */
private fun darkScheme(primaryHue: Float, secondaryHue: Float, tertiaryHue: Float): ColorScheme {
    val p = Ramp(primaryHue, 0.42f)
    val s = Ramp(secondaryHue, 0.26f)
    val t = Ramp(tertiaryHue, 0.34f)
    // 中性族**带着主色相**（彩度极低）：灰里透出一点主题色，正是 MD3 的观感；
    // 用纯灰（彩度 0）在深色下会显脏、和彩色容器接不上。
    val n = Ramp(primaryHue, 0.05f)
    val nv = Ramp(primaryHue, 0.08f)
    val e = Ramp(0f, 0.62f)
    return darkColorScheme(
        primary = p[80], onPrimary = p[20],
        primaryContainer = p[30], onPrimaryContainer = p[90],
        inversePrimary = p[40],
        secondary = s[80], onSecondary = s[20],
        secondaryContainer = s[30], onSecondaryContainer = s[90],
        tertiary = t[80], onTertiary = t[20],
        tertiaryContainer = t[30], onTertiaryContainer = t[90],
        error = e[80], onError = e[20],
        errorContainer = e[30], onErrorContainer = e[90],
        // 页面底 = tone 6（近黑）；卡片 = surfaceContainer tone 12（看得出比底亮一档）；
        // 再往上 High/Highest 给悬浮层与选中块 —— 与参考图的分层完全同序
        background = n[6], onBackground = n[90],
        surface = n[6], onSurface = n[90],
        surfaceDim = n[6], surfaceBright = n[24],
        surfaceContainerLowest = n[4], surfaceContainerLow = n[10],
        surfaceContainer = n[12], surfaceContainerHigh = n[17],
        surfaceContainerHighest = n[22],
        surfaceVariant = nv[30], onSurfaceVariant = nv[80],
        surfaceTint = p[80],
        inverseSurface = n[90], inverseOnSurface = n[20],
        outline = nv[60], outlineVariant = nv[30],
        scrim = Color.Black,
        primaryFixed = p[90], primaryFixedDim = p[80],
        onPrimaryFixed = p[10], onPrimaryFixedVariant = p[30],
        secondaryFixed = s[90], secondaryFixedDim = s[80],
        onSecondaryFixed = s[10], onSecondaryFixedVariant = s[30],
        tertiaryFixed = t[90], tertiaryFixedDim = t[80],
        onTertiaryFixed = t[10], onTertiaryFixedVariant = t[30],
    )
}

/** 浅色方案：同一套色调板换 tone（强调 tone 40、容器 tone 90、中性面 tone 87~98）。 */
private fun lightScheme(primaryHue: Float, secondaryHue: Float, tertiaryHue: Float): ColorScheme {
    val p = Ramp(primaryHue, 0.42f)
    val s = Ramp(secondaryHue, 0.26f)
    val t = Ramp(tertiaryHue, 0.34f)
    val n = Ramp(primaryHue, 0.05f)
    val nv = Ramp(primaryHue, 0.08f)
    val e = Ramp(0f, 0.62f)
    return lightColorScheme(
        primary = p[40], onPrimary = p[100],
        primaryContainer = p[90], onPrimaryContainer = p[10],
        inversePrimary = p[80],
        secondary = s[40], onSecondary = s[100],
        secondaryContainer = s[90], onSecondaryContainer = s[10],
        tertiary = t[40], onTertiary = t[100],
        tertiaryContainer = t[90], onTertiaryContainer = t[10],
        error = e[40], onError = e[100],
        errorContainer = e[90], onErrorContainer = e[10],
        // ★ 浅色页面底取 tone 99（不是 MD3 名义上的 98）：卡片在 tone 94，
        //   98 与 94 只差 4 档，装机实测几乎分不出卡片边界；99 与 94 差 5 档，
        //   卡片才"立"得起来（深色那边是 6 对 12，差 6 档，本来就没这问题）
        background = n[99], onBackground = n[10],
        surface = n[99], onSurface = n[10],
        surfaceDim = n[87], surfaceBright = n[98],
        surfaceContainerLowest = n[100], surfaceContainerLow = n[96],
        surfaceContainer = n[94], surfaceContainerHigh = n[92],
        surfaceContainerHighest = n[90],
        surfaceVariant = nv[90], onSurfaceVariant = nv[30],
        surfaceTint = p[40],
        inverseSurface = n[20], inverseOnSurface = n[95],
        outline = nv[50], outlineVariant = nv[80],
        scrim = Color.Black,
        primaryFixed = p[90], primaryFixedDim = p[80],
        onPrimaryFixed = p[10], onPrimaryFixedVariant = p[30],
        secondaryFixed = s[90], secondaryFixedDim = s[80],
        onSecondaryFixed = s[10], onSecondaryFixedVariant = s[30],
        tertiaryFixed = t[90], tertiaryFixedDim = t[80],
        onTertiaryFixed = t[10], onTertiaryFixedVariant = t[30],
    )
}

/** 调色盘里的一套种子（用户可选的四套强调色，顺序即界面里的圆点顺序）。 */
class Seed internal constructor(
    val swatch: Color,
    private val primary: Color,
    private val secondary: Color,
    private val tertiary: Color,
) {
    internal fun dark() = darkScheme(hueOf(primary), hueOf(secondary), hueOf(tertiary))
    internal fun light() = lightScheme(hueOf(primary), hueOf(secondary), hueOf(tertiary))

    /** 色板圆点本身要用 [swatch]；相等性按它判，选中态才对得上。 */
    override fun equals(other: Any?) = other is Seed && other.swatch == swatch
    override fun hashCode() = swatch.hashCode()
}

val Seeds: List<Seed> = listOf(
    Seed(Color(0xFF1565C0), Color(0xFF1565C0), Color(0xFF546E7A), Color(0xFF00838F)), // 蓝
    Seed(Color(0xFF7E57C2), Color(0xFF7E57C2), Color(0xFF9575CD), Color(0xFF26A69A)), // 紫
    Seed(Color(0xFF2E7D32), Color(0xFF2E7D32), Color(0xFF558B2F), Color(0xFF9E9D24)), // 绿
    Seed(Color(0xFFEF6C00), Color(0xFFEF6C00), Color(0xFFF57C00), Color(0xFFD84315)), // 琥珀
)

/**
 * 列表行左侧**圆形图标底**的配色（那排彩色圆）。
 *
 * <h3>演进：多色相 → 统一强调色（用户定版）</h3>
 *
 * 这里原来是 Google 那套写死的容器色，后来改成从 [MaterialTheme.colorScheme] 取
 * 六个不同角色（primary/secondary/tertiary × 容器/fixed），想靠色相区分"哪行是干什么的"。
 * 实际效果是**一屏里好几个不同色相的圆片互相打架**（暗紫红、绿、浅紫混在一起），
 * 用户定版："圆形图标的主题色取的不统一，可以统一取一个当前色彩模式下比较浅的色。"
 *
 * 于是收敛成 [Accent] 一档：`primaryContainer` —— MD3 容器档的**深浅语义天然正确**
 * （用户定版"深色的时候就取一个深色颜色，浅色的时候就取一个浅色颜色，不扎眼"）：
 * 深色方案里容器是 tone 30 的暗强调色、浅色方案里是 tone 90 的浅面板，
 * 都比页面底只亮/暗一档，跟着主题（种子色 / 动态取色）一起换色相，
 * 永远是"安分的圆片"而不是"跳出来的色块"。
 *
 * [Error] 保留独立：失败/删除这类本该显眼的行仍然用错误容器，别拿红色当装饰。
 *
 * <h3>为什么是 @Composable get()</h3>
 *
 * 调用点都在 composable 上下文里；把 val 写成 `@Composable get()`，取值时机在
 * **组合时**，主题变化会跟着重组 —— 调用点一个字不用改。
 */
object IconTints {
    /** 全应用统一的圆片底色：主题的容器档（深色=深容器、浅色=浅容器，均不扎眼）。 */
    val Accent: CircleTint
        @Composable get() = CircleTint(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

    /**
     * 错误容器：**只用在"失败/删除"这类本来就该显眼的行上**（传输失败、解除配对）。
     * 不要拿它当普通装饰色。
     */
    val Error: CircleTint
        @Composable get() = CircleTint(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
}

/** 一枚圆片的底色与字形色。 */
class CircleTint(val container: Color, val content: Color)
