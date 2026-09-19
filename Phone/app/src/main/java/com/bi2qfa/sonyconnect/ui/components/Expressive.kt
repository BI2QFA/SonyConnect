package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.ui.theme.CircleTint
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.ui.components.MsIcon

/**
 * **MD3E 组件套件**（照用户给的 Google Wallet 设置页那套语言摆）。
 *
 * 分工：**几何与手感向库要，语义与拼法在这里定**。
 *
 * - 行 = 原生 `ListItem`（分段形状 / 分段配色 / 按下变形都是库的，见 [SettingsRow]）
 * - 段间距 = 库的 `ListItemDefaults.SegmentedGap`
 * - 主题 = `MaterialExpressiveTheme` + `MotionScheme.expressive()`（见主题文件）
 * - 这里只负责"哪几件事算一组、每行的图标圆片什么色、标题写什么"
 *
 * | 构件 | 对应参考图里的什么 |
 * |---|---|
 * | [SectionHeader] | "安全 / 电子邮件 / 卡券"那几行小标题 |
 * | [GroupCard] | 只有一段的卡片（型号、关于、电量这类非行内容） |
 * | [SettingsGroup] / [Segment] | 一组分段卡片 / 组里的一段 |
 * | [SettingsRow] | 卡里的一行：左圆图标 + 标题 + 副标题 + 右侧控件 |
 * | [IconCircle] | 行首那枚**彩色圆片**（淡紫/淡绿/淡蓝…） |
 * | [Chevron] | 可进入下一层的行尾 "›" |
 * | [SwitchRow] | 带开关的行（开关里有对号） |
 *
 * 几何取的是参考图的实测比例（卡片左右留 16、组内行间距由分隔线负责、
 * 组与组之间 16、行内上下 14）；圆片 44dp 是"触摸目标 ≥48 时看着仍轻"的折中。
 */

/**
 * 页面顶栏（参考图那一版：左侧返回箭头 + 大号标题 + 下面一行副标题 + 右侧动作）。
 *
 * ★ 不用 `TopAppBar`：它的高度是写死的 64dp，标题层给的是"一行 22sp"，副标题
 *   （相机型号那行很重要，用户定过版）在 24sp 标题下面就顶出去了。这里自己排，
 *   顺便把状态栏内边距自己加上（Scaffold 只给**内容**加 inset，顶栏槽位不给）。
 */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = if (onBack == null) 20.dp else 4.dp,
                end = 12.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                val backSource = remember { MutableInteractionSource() }
                IconButton(onClick = onBack, interactionSource = backSource) {
                    MsIcon(
                        icon = MsIcon.ARROW_BACK,
                        contentDescription = "返回",
                        modifier = Modifier.size(24.dp),
                        size = 24.dp,
                        interactionSource = backSource,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                // 标题跟着页面一起换：给文字一个**纵向的交待**（新的自下方淡入、旧的
                // 向上淡出）。页面本身是横向共享轴推入的，标题若"啪"地换掉会脱拍；
                // 位移用 spatial、淡变用 effects，进出同样不对称（进从容、出干脆）。
                val titleIn = Motion.spatialDefault<IntOffset>()
                val titleOut = Motion.spatialFast<IntOffset>()
                val fadeInSpec = Motion.effectsDefault<Float>()
                val fadeOutSpec = Motion.effectsFast<Float>()
                AnimatedContent(
                    targetState = title to subtitle,
                    transitionSpec = {
                        (
                            slideInVertically(titleIn) { it / 5 } + fadeIn(fadeInSpec)
                            ).togetherWith(
                            slideOutVertically(titleOut) { -it / 5 } + fadeOut(fadeOutSpec),
                        )
                    },
                    label = "headerTitle",
                ) { (t, sub) ->
                    Column {
                        Text(
                            t,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            actions?.invoke(this)
        }
    }
}

/** 段落小标题（参考图里那种"安全""卡券"）。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // ★ 上下留白收到 10/4（原 18/8）：主界面有四段小标题，一段多占 12dp
        //   就是 48dp —— 而主界面要求"一屏不用滑动就放下"（用户定版）。
        //   收完之后标题仍与上面那一段拉开距离、与自己的卡片贴得较近，
        //   归属关系反而更清楚（"标题属于下面那张卡"）。
        modifier = modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * **组**：若干段之间留库自带的分段间距（`ListItemDefaults.segmentedGap`），露出页面底。
 *
 * ★ 参考图里"安全性与登录 / Google 密码 / 关联的应用"是一张卡被切成三段的观感：
 *   外圈圆角大、段与段之间一道极细的缝，读起来是"同一个组里的几件事"，而不是
 *   三张互不相干的卡。
 *
 * ★★ **组与组之间必须留出一段**（这里写死 18dp）：如果组间不留白，组边界和组内的
 *   缝长得一模一样，整页就读成"一长条行"，分组等于白分 —— 装机实测第一版正是
 *   这样，相机/文件/传输三段连成一串，看不出哪几行是一组的。
 *
 * ★ 分段间距不再写死 2dp：改取 `ListItemDefaults.segmentedGap`，与原生分组列表
 *   保持同一个数值（1.4.0 时代这是自己量的 5px@2.25x）。
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        // vertical 9dp 上下各一半 = 相邻两组之间 18dp，而页面首组离顶栏 9dp
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}

/**
 * 组里第 [index] 段（共 [count] 段）的**静止形状**。
 *
 * ★ 圆角不再向库要（用户定版"所有卡片的圆角稍大一些，注意 r 角统一"）：
 *   库的 segmentedShapes 是它自己那一档，跟悬浮面板这类自绘件对不上号。
 *   现在全局一个 [CardCorner]（32dp），多段组的接缝用 [SegmentInnerCorner]（4dp）
 *   —— 卡片、行、悬浮面板从此同一个 r 角。
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(CardCorner)
    index <= 0 -> RoundedCornerShape(
        topStart = CardCorner, topEnd = CardCorner,
        bottomStart = SegmentInnerCorner, bottomEnd = SegmentInnerCorner,
    )
    index >= count - 1 -> RoundedCornerShape(
        topStart = SegmentInnerCorner, topEnd = SegmentInnerCorner,
        bottomStart = CardCorner, bottomEnd = CardCorner,
    )
    else -> RoundedCornerShape(SegmentInnerCorner)
}

/** 全局卡片/行/面板共用的圆角（用户定版"r 角统一"）。 */
val CardCorner = 32.dp

/** 多段组相邻段之间的接缝小圆角。 */
private val SegmentInnerCorner = 4.dp

/**
 * 分段面的底色。
 *
 * ★ 库的 `ListItemDefaults.segmentedColors()` **默认容器色是透明的** —— 分组列表的
 *   官方用法是调用方自己铺一层分组底板（一组一块面），行本身不着色。我们这边一组的
 *   面就是"每一段各画自己那块"，所以把容器色补成 `surfaceContainer`：
 *   装机第一版没补，整页的卡片全隐形了，只剩文字浮在页面底上。
 *
 * ★ 公开（原来是 private）：文件页/传输页也要用同一块底色自己拼"几小块组成一大块"，
 *   各写一个常数迟早会与设置页漂移。
 */
@Composable
fun segmentSurfaceColor(): Color =
    ListItemDefaults.segmentedColors()
        .copy(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        .containerColor

/**
 * 组里的一段（**不是行**的内容用它）：色调面 + 分段形状 + 可选整段点击。
 * 行请用 [SettingsRow]（那边是原生 `ListItem`，还额外带按下变形）。
 */
@Composable
fun Segment(
    index: Int = 0,
    count: Int = 1,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = segmentShape(index, count)
    val color = segmentSurfaceColor()
    if (onClick != null) {
        // 走 Surface(onClick)：水波纹才会落在**这一段自己的形状**里（外圈圆、内圈方），
        // 挂在外面再加 background 的话水波纹是整块方角，深浅色下都很显眼
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            content = { Column(content = content) },
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            content = { Column(content = content) },
        )
    }
}

/**
 * 单张卡片（= 只有一段的组）。内容不是"行"的卡片（型号、关于、电量这类）用它。
 *
 * 不再投影：MD3 的面靠**色调分层**（底 tone 6 → 卡片 tone 12），投影在深色下
 * 只会在卡片四周糊出一圈更黑的边，看着像脏了。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    /** 整卡可点时给（走 Surface(onClick)，水波纹才会被卡片圆角裁掉、不是方角块）。 */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsGroup(modifier) {
        Segment(index = 0, count = 1, onClick = onClick, content = content)
    }
}

/**
 * 当前行的交互源：让 [IconCircle] 里的图标跟着整行一起 Q 弹，而不用自己装手势。
 *
 * ★ 为什么用 CompositionLocal 而不是把参数一路传下去：`leading` 是调用方传进来的
 *   `@Composable () -> Unit`，`SettingsRow` 拿不到它内部的图标 —— 要么改掉全部
 *   19 个调用点的签名，要么用 CompositionLocal 在渲染时把 source 递进去。
 *   后者让"行 → 圆片 → 图标"的联动对调用点完全透明。
 */
val LocalRowInteraction = staticCompositionLocalOf<InteractionSource?> { null }

/**
 * 行首那枚彩色圆片（**带按下反馈**）。
 *
 * 图标用 [MsIcon]（可变字体）而不是矢量图，这样才有"点击时 q 弹的感觉"。
 * 交互源按这个顺序取，先用显式传入的、再取当前行的（[LocalRowInteraction]）：
 *
 * ★ 图标**不装自己的手势**。这一层通常在"整行可点"的容器里（[SettingsRow] 的
 *   `ListItem`）：图标自己装一个 `clickable` 的话，手指落在图标那一小块上就不会触发
 *   整行点击 —— 一个纯装饰的圆片把整行的点击抢掉，是很难查的一类 bug。
 *   所以按下状态一律由外层行传进来。
 *
 * @param source 显式交互源；不传则用当前行的（[LocalRowInteraction]）
 */
@Composable
fun IconCircle(
    icon: MsIcon,
    tint: CircleTint,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    source: InteractionSource? = LocalRowInteraction.current,
) {
    Box(
        Modifier.size(size).background(tint.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint.content,
            size = iconSize,
            animated = source != null,
            interactionSource = source,
        )
    }
}

/** 行尾 "›"（表示可以进下一层）。 */
@Composable
fun Chevron() {
    // 纯指示图形（不可点）：关掉动画，免得它跟着整行的按下一起缩
    // —— 它是"可以进下一层"的标记，不是被按的对象。
    // ★ 不传 interactionSource 时 MsIcon 会自己装一个空的 clickable，那会抢掉整行点击，
    //   所以这里必须显式 `animated = false`。
    MsIcon(
        icon = MsIcon.CHEVRON_RIGHT,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        size = 22.dp,
        animated = false,
    )
}

/**
 * 组里的一行 —— **直接用原生的 `ListItem`**，不是自己拼 Row。
 *
 * 用原生 ListItem 换来的三件事（1.4.0 时代都拿不到，只能自己拼）：
 *
 * 1. **分段形状**：`ListItemDefaults.segmentedShapes(index, count)` 给出这一段的
 *    静止/按下/聚焦/选中四种形状，外圈大圆角、内圈小圆角由库算
 * 2. **按下变形**：按下时那一行会从"内圈 4dp"**动画变成**一个更圆/更方的形状
 *    （MD3E 的标志性手感），这套 morph 是 ListItem 自己做的，自拼的 Row 做不出来
 * 3. **分段配色**：`ListItemDefaults.segmentedColors()` 给的容器色/各级文字色
 *    就是分组列表该用的那套
 *
 * 传了 [onClick] 才可点（用 ListItem 自己的可点重载，按压反馈与触摸目标都由它管）。
 * `leading` 通常给 [IconCircle]。
 *
 * ★ 标题用 titleMedium 而不是 ListItem 默认的 bodyLarge：参考图里的行标题是加粗的，
 *   bodyLarge 在中文下太轻。其余排版（内边距、行高、说明行位置）全交回 ListItem。
 *
 * ★ 不再有 `extra` 槽：以前靠它在"同一段里、行下面"挂色点行/分段控件，现在那种
 *   组合**用两个相邻的段**表达（行一段、控件一段），这才是分组列表的原生写法 ——
 *   段的形状由库按 index/count 算，两个段天生同形。
 */
/**
 * 分组列表里的一行：**自定义行布局**（不再用原生 ListItem）。
 *
 * ★ 为什么弃用 ListItem：它的 leading 槽是**顶对齐**的 —— 说明文字折行把行撑高时，
 *   行首圆片跟着第一行走、看着偏上（用户定版"无论何时圆片都要在纵轴中央"，
 *   且要**全局**成立）。各种布局补丁都不如把行自己排了：圆片、文字块、行尾控件
 *   全部放进一个 `Row(CenterVertically)`，谁高谁矮都严格共中线。
 *
 * 结构 = Surface（分段形状 + surfaceContainer 底 + 整行可点）+ 三列 Row：
 * `leading`(44dp 圆片) → 标题/说明（占满剩余宽）→ trailing（开关/按钮/图标钮）。
 * 按压变形（MD3E 的 shape morph）随 ListItem 一起没了，水波纹与无障碍语义都在；
 * 禁用态按 MD3 的 0.38 内容透明度压暗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    support: String? = null,
    supportColor: Color? = null,
    /** 说明行最多几行（读数行传 1：圆片与文字要共中线，见 [IconCircle] 的注释）。 */
    supportMaxLines: Int = 3,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = segmentShape(index = index, count = count)
    val color = segmentSurfaceColor()
    // 行级交互源：Surface(onClick) 与行首圆片里的图标共用 ——
    // "整行被按住"会让图标一起 Q 弹（图标自己不装手势，见 IconCircle 的注释）
    val rowInteraction = remember { MutableInteractionSource() }
    val leadingWithSource: (@Composable () -> Unit)? = leading?.let { inner ->
        { CompositionLocalProvider(LocalRowInteraction provides rowInteraction) { inner() } }
    }
    val body: @Composable RowScope.() -> Unit = {
        if (leadingWithSource != null) {
            leadingWithSource()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                // ★ 标题**只给一行**：行首有 44dp 圆片、行尾还有控件，文字列只有
                //   一百多 dp 宽；不限制行数时一条长标题（型号+SN+设备码）就会撑成
                //   三行，卡片一下变得又高又乱（装机实测）。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (support != null) {
                Text(
                    support,
                    style = MaterialTheme.typography.bodyMedium,
                    // 说明文字允许折行（长路径折两行是对的），给个上限防撑开。
                    // ★ 行整体是 CenterVertically 的：折多少行圆片都稳在正中。
                    maxLines = supportMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    color = supportColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
    val row: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp)
                // MD3 禁用态：内容整体压暗（原生 ListItem 的 disabled alpha 同款）
                .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
            verticalAlignment = Alignment.CenterVertically,
            content = body,
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = rowInteraction,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            content = row,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            content = row,
        )
    }
}

/**
 * 带开关的一行（开关里带对号 —— MD3E 的开关就是这样）。
 *
 * 整段可点（点文字也切换），这是参考图里那些开关行的行为。
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    support: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        support = support,
        modifier = modifier,
        index = index,
        count = count,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = if (checked) {
                    {
                        MsIcon(
                            icon = MsIcon.CHECK,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                            size = SwitchDefaults.IconSize,
                            // 在开关的滑块里，纯图形：不装手势（装了会跟开关的拖动打架）
                            animated = false,
                        )
                    }
                } else null,
            )
        },
    )
}

/**
 * 主按钮（MD3E：56dp 高、全圆角、字比基线大一号）。
 * 用 `Button`/`FilledTonalButton` 而不是手搓，是为了保留水波纹与无障碍语义。
 */
@Composable
fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val pad = PaddingValues(horizontal = 28.dp, vertical = 8.dp)
    val label: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    if (tonal) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            enabled = enabled,
            shape = CircleShape,
            contentPadding = pad,
            content = label,
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(56.dp),
            enabled = enabled,
            shape = CircleShape,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(),
            content = label,
        )
    }
}

/** 行尾的图标按钮（删除/菜单这类）：自带水波纹与 48dp 触摸目标。 */
@Composable
fun RowIconAction(
    icon: MsIcon,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    // 图标按钮本身可点：把它的交互源转给图标，按下时图标跟着 Q 弹
    // （不这样做的话，图标要自己装 clickable —— 那会跟按钮的点击打架）。
    val source = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, enabled = enabled, interactionSource = source) {
        MsIcon(
            icon = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = tint,
            size = 22.dp,
            interactionSource = source,
        )
    }
}

/** 空态/提示文字（居中的一句灰字）。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}


