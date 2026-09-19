package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass

/**
 * Material Symbols 图标身份。
 *
 * 每个成员带着**码位**（可变字体里那个字形的位置）与**官方图标名**。
 * 用字体而不是矢量图（`res/drawable/ic_*.xml`）的唯一理由：**只有字体能用到可变轴**
 * —— FILL / wght 这些轴在静态 path 上根本不存在，"按下变实心"这种效果就做不出来。
 *
 * 字体是官方 `MaterialSymbolsOutlined[FILL,GRAD,opsz,wght].ttf` 的**子集**
 * （10.2 MB → 34.5 KB，只留下面这些图标），由 `tools/subset_ms_font.py` 生成。
 */
enum class MsIcon(val codePoint: Int, val officialName: String) {
    ADD(0xE145, "add"),
    ARROW_BACK(0xE5C4, "arrow_back"),
    CAMERA(0xE412, "photo_camera"),
    CHECK(0xE668, "check"),
    CHEVRON_RIGHT(0xE5CC, "chevron_right"),
    CONTRAST(0xEB37, "contrast"),
    FILE_GENERIC(0xE66D, "draft"),
    FOLDER(0xE2C7, "folder"),
    GRID(0xE9B0, "grid_view"),
    IMAGE_PLACEHOLDER(0xE3F4, "image"),
    INFO(0xE88E, "info"),

    /**
     * 镜头行的图标：Material Symbols 的 `camera` —— **光圈叶片**字形（不是
     * `photo_camera` 那个机身轮廓）。用户定版：原来那个 `photo_camera_back`
     * 太抽象，"去 material symbol 里找一个形象一点的"；光圈是"镜头"最直白的记号。
     */
    LENS(0xE3AF, "camera"),
    LIST(0xE896, "list"),

    /** 导航栏的"文件"：与 [FOLDER] 是同一个字形，分开是**语义**上的区分（将来想换就换）。 */
    NAV_FILES(0xE2C7, "folder"),
    NAV_HOME(0xE9B2, "home"),

    /**
     * 控制中心圆钮的图标：`keyboard_arrow_up`（"~" 形的三角/山形箭头）。
     * 收起态朝上（"点我有东西展开"），展开态由调用方转 180° 朝下（用户定版）。
     */
    NAV_CARET(0xE316, "keyboard_arrow_up"),

    /** 传输页的任务菜单：`arrow_drop_down`（实心倒三角，"点开菜单"的通用记号）。 */
    MENU_DROP(0xE5C5, "arrow_drop_down"),
    NAV_TRANSFERS(0xE8D5, "swap_vert"),
    PALETTE_DOTS(0xE40A, "palette"),
    REFRESH(0xE5D5, "refresh"),
    REWARD(0xE8F6, "redeem"),
    SELECT_ALL(0xE162, "select_all"),
    SELECT_INVERSE(0xEBB6, "deselect"),
    SETTINGS_GEAR(0xE8B8, "settings"),
    START(0xE037, "play_arrow"),
    STAT_TRANSFER(0xF090, "download"),
    STOP(0xE047, "stop"),

    /** 缩略图下载：与 [STAT_TRANSFER] 同字形，理由同 [NAV_FILES]。 */
    THUMB_DOWNLOAD(0xF090, "download"),
    TRASH(0xE92E, "delete"),
    WIFI(0xE63E, "wifi"),
    ;

    /**
     * 该图标的 **FILL 轴是否会真的改变字形**。
     *
     * ★ 这**不是**可以随手改的开关，是**实测出来的事实**：Material Symbols 里有一半图标
     *   天生只有一种形态（`FILL` 从 0 到 1 是同一个字形）。对它们做"按下变实心"不会报错、
     *   也不会崩 —— 只是**什么都不发生**，很容易被误当成动效写错了。
     *
     * 名单来自逐图标渲染实测（比对该轴两端的墨迹像素），复跑见
     * `tools/check_icon_axes.py`（换图标或升级上游字体后必须重跑）。
     */
    val supportsFill: Boolean
        get() = this !in NO_FILL

    companion object {
        /** 实测 FILL 轴无效果的成员（`tools/check_icon_axes.py` 会核对这份名单）。 */
        val NO_FILL = setOf(
            ADD, ARROW_BACK, CHECK, CHEVRON_RIGHT, CONTRAST, SELECT_INVERSE,
            STAT_TRANSFER, LIST, REWARD, REFRESH, SELECT_ALL, NAV_TRANSFERS,
            NAV_CARET, MENU_DROP, WIFI, THUMB_DOWNLOAD,
        )
    }
}

/**
 * 图标字体只加载一次。
 *
 * ★ 这里**不能用**一个全局共享的 `FontFamily`：可变轴是 **Font 的属性**（这个版本里
 *   `TextStyle` 没有 `fontVariationSettings`），所以轴值一变就必须换一个 Font 对象
 *   （见 [MsIcon] 里的 `remember(fill, weight)`）。全局共享就只能有一种轴值，
 *   动效直接失效。
 */

/**
 * 一枚 Material Symbols 图标，带**按下 Q 弹**反馈。
 *
 * 手感分两层，**两层都是必要的**：
 *
 * 1. **缩放 + spring 回弹**（对所有图标都生效）：
 *    按下缩到 [PRESSED_SCALE]，松手用 `DampingRatioMediumBouncy` 弹回 ——
 *    宁可有一点点过冲，那才是"Q 弹"；用 tween 匀速回弹是死板的，像在拖动。
 * 2. **可变轴变化**（只在 [MsIcon.supportsFill] 的图标上看得出来）：
 *    `FILL` 0→1（空心变实心，Material Symbols 的招牌交互）+ `wght` 400→500。
 *    `wght` 是**所有**图标都支持的，所以实心变化缺席时仍有一点形态反馈。
 *
 * 为什么不把 FILL 当主效果：一半图标天生没有实心态（见 [MsIcon.supportsFill]），
 * 只靠它会让那些图标按下去毫无反应。
 *
 * ★★ **"按压实心"不是处处都对的**（用户定版）：导航栏图标的实心 = **选中**，
 *    被手指摸到就变实心会让人误以为选中状态变了。所以实心的驱动源是可配的：
 *    默认仍随按压（普通按钮的反馈），导航条那些传 `filled = selected` +
 *    `fillOnPress = false`，实心只跟选中态走。
 *
 * @param contentDescription 无障碍描述；装饰性图标传 null
 * @param size 字号（图标是文字，所以尺寸走字号）。默认 24dp 对齐 MD3 图标规范
 * @param animated false = 静态渲染（无动画、不装 pointerInput）。列表里几十个图标同时
 *   滚动、或通知小图标这类地方应当关掉 —— 那时每一枚都挂一个手势检测是纯浪费
 * @param interactionSource 外部已有的交互源（例如它被包在一个可点区域里）。
 *   给了就用它驱动反馈；不给则自己在图标上检测按下（图标自己可点时用）
 * @param filled **状态驱动的实心**（true = 实心）。给 true 时无论按不按压都实心；
 *   false 时不强制空心，按压是否实心由 [fillOnPress] 决定
 * @param fillOnPress 按压时是否变实心。导航条图标传 false：实心只表达"选中"，
 *   不表达"手指在上面"（用户定版）
 */
@Composable
fun MsIcon(
    icon: MsIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    animated: Boolean = true,
    interactionSource: InteractionSource? = null,
    filled: Boolean = false,
    fillOnPress: Boolean = true,
) {
    // 自己检测按下时用的交互源（只在"没给外部交互源"那条路上装）。
    //
    // ★★ 自己取按下信号时**绝不能装 `clickable`**（第一版就是这么写的，是个真 bug）：
    //   本文件里绝大多数图标都嵌在"可点父容器"里（`FilledIconButton` / `ListItem` /
    //   `ExtendedFloatingActionButton` / `Surface(onClick)` …）。Compose 的嵌套点击
    //   **内层优先**，图标那个空 `onClick` 会把父容器的点击吃掉 ——
    //   症状是"**手指正好按在图标上时按钮没反应**，按旁边就行"，
    //   一小块死区，极难查（而且要真机才发现）。
    //
    //   改用 `pointerInput` 只**观察**指针、不消费：父容器照常收到点击，
    //   图标也能拿到"按下/抬起"来驱动动画。这样两种场景（可点父容器 / 图标自己可点）
    //   用同一套代码都对，调用方也不需要知道这个区别。
    //   ★ 不用 `detectTapGestures`：它属于 foundation 的非传递 API（本工程 foundation
    //     是经 material3 进来的），编译类路径上找不到；`awaitPointerEventScope` 在
    //     `ui` 包里，直接可用。
    // 观察按下：进/出都**不消费**事件（不起 drag、不吞 up）
    val selfPressed = remember { mutableStateOf(false) }

    val pressed: Boolean = when {
        !animated -> false
        interactionSource != null -> interactionSource.collectIsPressedAsState().value
        else -> selfPressed.value
    }

    val pressModifier = if (animated && interactionSource == null) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    selfPressed.value = down.changes.any { it.pressed }
                }
            }
        }
    } else {
        Modifier
    }

    // 缩放：快进（press 要跟手）慢出（回弹要看得见）
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "msIconScale",
    )

    // 轴值：FILL 只在支持的图标上动；wght 所有图标都动。
    // 实心的两个驱动源（见参数说明）：状态（filled）与按压（fillOnPress）。
    val isFilled = (filled || (fillOnPress && pressed)) && icon.supportsFill
    val fill by animateFloatAsState(
        targetValue = if (isFilled) 1f else 0f,
        animationSpec = if (isFilled) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "msIconFill",
    )
    val weight by animateFloatAsState(
        targetValue = if (pressed) PRESSED_WEIGHT else NORMAL_WEIGHT,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "msIconWeight",
    )

    // ★ 可变轴是 **Font 的属性**，不是 TextStyle 的 —— 这个版本（ui-text 1.13.0-alpha03）里
    //   `TextStyle` **没有** `fontVariationSettings` 参数（javap 核过：0 处匹配）。
    //   所以轴值一变就得换一个 Font 对象，进而触发一次 Typeface 解析。
    //
    // ★★ 所以 wght 动画值**必须量化**（WEIGHT_STEPS），否则每个渐变帧都是一个新 Font →
    //    Compose 的 `AsyncTypefaceCache` 是个 **LRU 16**，一次 spring 回弹产生的中间值
    //    远超 16 个，会整个冲掉缓存，动画期间每帧重建一次 Typeface（掉帧、发热）。
    //
    // ★★★ **实心不依赖 FILL 轴了**（实测 Redmi 14R / Android 16 的平台层会静默忽略
    //    `Font(resId, variationSettings)` —— 图标能画，轴全部无效，选中永远空心；
    //    小米15 上一切正常）。ms_icons_fill.ttf 是生成时**钉死 FILL=1** 的实例
    //    （tools/subset_ms_font.py），实心的过渡 = 下面两枚字形的 **alpha 交叉淡化**：
    //    支持轴的设备上两支字体逐帧同粗细，观感与轴动画几乎一致；
    //    不支持的设备上 fill 字体就是静态实心 —— 实心从此与平台能力无关。
    //    顺带的收益：FILL 过渡不再产生任何中间 Font（alpha 是绘制期属性），
    //    上面那个 Typeface 缓存压力只剩 wght 一条路。
    val weightQ = quantize(weight, WEIGHT_STEPS)

    val baseFamily = remember(weightQ) {
        FontFamily(
            Font(
                resId = R.font.ms_icons,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weightQ.toInt()),
                    // opsz 固定在中性值：它影响笔画对比度，跟着字号变会让同一枚图标
                    // 在不同尺寸下看着不是同一个图标
                    FontVariation.Setting("opsz", OPSZ),
                ),
            ),
        )
    }
    // FILL=1 实例：wght/opsz 与上面同一口径，两支的笔画粗细逐帧一致
    val fillFamily = remember(weightQ) {
        FontFamily(
            Font(
                resId = R.font.ms_icons_fill,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weightQ.toInt()),
                    FontVariation.Setting("opsz", OPSZ),
                ),
            ),
        )
    }

    val glyph = remember(icon) { String(Character.toChars(icon.codePoint)) }
    // ★ 用 `Density.toSp(Dp)` 而不是 `size.value.sp`：后者是"把 dp 数值当 sp 用"，
    //   而 sp 会再过一次系统的 fontScale —— 用户在系统里调大字体时，
    //   这个**图标**会跟着变大（MD3 的图标规范是图标尺寸不随 fontScale 变，
    //   只有文字变）。`toSp` 会正确抵消 fontScale，图标永远是 size 指定的物理尺寸。
    val fontSize = with(LocalDensity.current) { size.toSp() }

    Box(
        modifier = modifier
            .then(pressModifier)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            modifier = Modifier.graphicsLayer { alpha = 1f - fill },
            color = tint,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = baseFamily,
                fontSize = fontSize,
            ),
        )
        // 实心层：支持 FILL 的图标才叠（其余图标 fill 实例与空心完全同形，叠了白画）
        if (icon.supportsFill) {
            Text(
                text = glyph,
                modifier = Modifier
                    .graphicsLayer { alpha = fill }
                    .clearAndSetSemantics {},
                color = tint,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = fillFamily,
                    fontSize = fontSize,
                ),
            )
        }
    }
}

/**
 * 把连续的动画值**量化**成有限的几档。
 *
 * 为什么要这么做（这条不是微优化，是"能不能跑顺"的分界）：
 * Compose 的 `AsyncTypefaceCache` 是一个 **LRU 16** 的缓存，而可变轴值不同的
 * `Font` 是**不同的缓存条目**。一次 spring 回弹会产生远多于 16 个中间值 ——
 * 不量化的话每个渐变帧都 miss、每帧重建一次 Typeface（掉帧、发热）。
 *
 * `Math.round` 到最近的 1/steps 档，所以 steps 越大过渡越顺、产生的组合越多。
 */
private fun quantize(value: Float, steps: Int): Float =
    Math.round(value * steps).toFloat() / steps

/** 按下时缩到多小。0.82 是"看得出来被按了、又不至于像要消失"。 */
private const val PRESSED_SCALE = 0.82f

/** 常规字重与按下字重（wght 轴 100..700，400 是常规）。 */
private const val NORMAL_WEIGHT = 400f
private const val PRESSED_WEIGHT = 500f

/**
 * wght 轴的量化档数。
 *
 * 步长 10 一档（400 / 410 / … / 500）。依据是 `gvar` 在 wght 上**稀疏采样**：
 * 实测墨迹 400→450 几乎不动（1102→1110），450→460 一次跳 140，之后 460→500 缓动。
 * 步长 10 正好能把那个跳变限制在 1 档内；再细（5）没有收益（单档变化几乎一样）。
 */
private const val WEIGHT_STEPS = 10

/** 光学尺寸固定值（轴范围 20..48，24 是官方默认）。 */
private const val OPSZ = 24f
