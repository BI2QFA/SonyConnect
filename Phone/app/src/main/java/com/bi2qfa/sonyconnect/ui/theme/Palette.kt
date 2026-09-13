package com.bi2qfa.sonyconnect.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb


















private class Ramp(hue: Float, private val saturation: Float) {
    private val h = ((hue % 360f) + 360f) % 360f
    operator fun get(tone: Int): Color = Color.hsl(h, saturation, tone / 100f)
}


private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}






private fun darkScheme(primaryHue: Float, secondaryHue: Float, tertiaryHue: Float): ColorScheme {
    val p = Ramp(primaryHue, 0.42f)
    val s = Ramp(secondaryHue, 0.26f)
    val t = Ramp(tertiaryHue, 0.34f)
    
    
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


class Seed internal constructor(
    val swatch: Color,
    private val primary: Color,
    private val secondary: Color,
    private val tertiary: Color,
) {
    internal fun dark() = darkScheme(hueOf(primary), hueOf(secondary), hueOf(tertiary))
    internal fun light() = lightScheme(hueOf(primary), hueOf(secondary), hueOf(tertiary))

    
    override fun equals(other: Any?) = other is Seed && other.swatch == swatch
    override fun hashCode() = swatch.hashCode()
}

val Seeds: List<Seed> = listOf(
    Seed(Color(0xFF1565C0), Color(0xFF1565C0), Color(0xFF546E7A), Color(0xFF00838F)), 
    Seed(Color(0xFF7E57C2), Color(0xFF7E57C2), Color(0xFF9575CD), Color(0xFF26A69A)), 
    Seed(Color(0xFF2E7D32), Color(0xFF2E7D32), Color(0xFF558B2F), Color(0xFF9E9D24)), 
    Seed(Color(0xFFEF6C00), Color(0xFFEF6C00), Color(0xFFF57C00), Color(0xFFD84315)), 
)








object IconTints {
    val Blue = CircleTint(Color(0xFFD3E3FD), Color(0xFF041E49))
    val Purple = CircleTint(Color(0xFFEADDFF), Color(0xFF21005D))
    val Green = CircleTint(Color(0xFFC4EED0), Color(0xFF072711))
    val Cyan = CircleTint(Color(0xFFCCE8E6), Color(0xFF00201C))
    val Amber = CircleTint(Color(0xFFFEEFC3), Color(0xFF241A00))
    val Pink = CircleTint(Color(0xFFFFD8E4), Color(0xFF31111D))
}


class CircleTint(val container: Color, val content: Color)
