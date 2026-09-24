package com.bi2qfa.sonyconnect.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.transfer.TransferStore
import com.bi2qfa.sonyconnect.ui.components.CardCorner
import com.bi2qfa.sonyconnect.ui.components.LocalRowInteraction
import com.bi2qfa.sonyconnect.ui.components.MsIcon
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.CompositingStrategy
import com.bi2qfa.sonyconnect.ui.theme.useDarkTheme

/**
 * 悬浮导航（用户定版，MD3E 走查后的形态）：
 *
 * - 左：**药丸型** `ShortNavigationBar`，只放主页 / 文件 / 传输三个页面，**纯图标无文字**
 * - 右：**圆形**控制中心按钮（原来的圆角矩形"错号"），点击展开悬浮窗
 * - 圆环：圆形按钮内缘嵌一圈进度指示，承担原本顶栏那个下载/缩略图进度窗的作用
 * - 悬浮窗三段（自上而下）：① 下载进度 + 缩略图（**仅在进行中显示**）
 *   ② 设置 ③ 已绑定设备（切换不同设备）
 *
 * 组件选型说明：药丸是手摆的（`ShortNavigationBar` 是**占满宽度**的栏，塞进 Row 会把
 * 整行吃光，右边那颗圆钮直接被挤出屏幕 —— 见 [NavPill] 的注释）；"悬浮"这一层由外层
 * 阴影 + 圆角自己摆。
 *
 * ★ 动效**改用库的 expressive 规格**（`MaterialTheme.motionScheme.fastSpatialSpec /
 *   defaultSpatialSpec`）。升级前这几条路径拿不到 `MotionScheme`（1.4.0 里是 internal），
 *   只能照着令牌把弹簧数值抄进工程；现在直接向库要，与全应用其余组件同一份手感。
 */
/**
 * 悬浮导航在屏幕上实际占掉的高度（药丸 60dp + 上下留白 24dp + 系统手势栏余量）。
 *
 * 可滚动内容在底部留出这一份：悬浮栏是**盖在内容上**的，不留余地的话列表最后
 * 一项永远滚不到栏上方（用户反馈"下面字被切割"就是这个）。
 */
val FloatingNavInset = 112.dp

/**
 * 导航行与屏幕底边之间的**最小**间距。
 *
 * 关闭系统手势提示条（"小白条"）后 `navigationBars` 这个 inset 会变成 0 ——
 * 如果直接拿它当底部间距，药丸就会贴到屏幕最下沿，位置随系统设置来回变。
 * 兜住这个最小值，开不开小白条都停在同一高度。
 */
private val NavMinBottomPad = 16.dp

/**
 * 悬浮导航行实际占掉的高度（底部间距 + 药丸高度）；**可组合**，因为它含系统 inset。
 *
 * 给 FAB 这类悬浮元素用：想让某个按钮"贴着导航栏上沿"，就把底边距设成
 * `floatingNavHeight() + 一点间隙`，而不是拍一个 FloatingNavInset 那样的常数
 * —— 常数在开/关小白条时会高出或压住。
 */
@Composable
fun floatingNavHeight(): Dp {
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return inset.coerceAtLeast(NavMinBottomPad) + NavRowHeight
}

// ===== 导航行的几何常量（**面板展开动画要用，所以抽出来共享**）=====
//
// ★★ 数值出处：用户给了一张 1200×2670 的参考图 + 一段 90fps 录屏。参考图的 density
//    用**两个独立的已知尺寸**反推出来，都是 3.0，互相印证：
//      · 圆形按钮 168px ÷ 56dp(MD3 FAB) = 3.00
//      · 通话记录行距 213px ÷ 72dp(标准 ListItem) = 2.96
//    于是参考图那块屏是 **400dp 宽**，各元素的 dp 真值就是下面这些。
//
//    我上一版是拿"元素占屏宽的比例"硬折算的，得到圆钮 60dp / 药丸 274dp —— 全偏大。
//    正确的做法是**照搬 dp**（dp 本来就是与屏幕无关的绝对尺寸），这也正是用户要的
//    "完全一样"：同一套 Material 尺寸，在任何屏上都该是同一个 dp 值。
//
//    参考图实测（px → dp @3.0）：
//      左边距 116→38.7   药丸宽 760→253.3   间距 40→13.3
//      圆钮 168→56       右边距 116→38.7    行高 166→55.3
//      底边距 74→24.7    面板宽 1053→351    面板边距 74→24.7   面板圆角 90→30
// ★★★ 以下尺寸全部是**原厂实测值**（从 MIUI 设备 `uiautomator dump` 量的真实 bounds，
//      不是从录屏反推、也不是照抄 Material 规格）。
//
//   量法：`bottom_nav_fab` / 底部导航行的 `bounds="[x1,y1][x2,y2]"` 直接读，
//   再按原厂设备密度换算 —— 1200×2670 @ 520dpi ⟹ density = 3.25。
//
//   | 元素 | 原厂 px | ÷3.25 |
//   |---|---|---|
//   | 药丸 | 763×168 | **234.8 × 51.7dp** |
//   | 圆钮 | 168×168 | **51.7dp** |
//   | 单个 tab | 269×150 | 82.8 × 46.2dp |
//   | tab 图标 | 86×86 | 26.5dp |
//   | 药丸↔圆钮间距 | 37 | **11.4dp** |
//   | 药丸左边距 / 钮右边距 | 116 | **35.7dp** |
//   | 导航行高 | 168 | 51.7dp |
//   | 行顶距屏幕底 | 73 | 22.5dp |
//
//   ★ 我之前那套是照"参考图"按 density=3.0 折算的（药丸 252×56、钮 56、
//     间距 13、边距 38.7），**每一项都偏大 2~10%** —— 因为那份图里的 px 值
//     本来就是从视频帧上量的，有压缩误差；而 uiautomator 给的是**布局的真实值**。

/** 圆形控制中心按钮的直径 = 药丸高度（原厂两者都是 168px = 51.7dp）。 */
private val CcButtonSize = 51.7.dp
/**
 * 药丸里一个图标位的宽度 = **药丸宽 ÷ 3**（原厂三个 tab 等分药丸）。
 *
 * 234.8 ÷ 3 = **78.27dp**。
 * ★ 注意原厂 uiautomator 报的单个 tab 是 269px = 82.8dp，比 78.27 大 ——
 *   那是**触摸区**（相邻 tab 的触摸区会重叠），视觉与布局上它们仍是等分的。
 *   按 78.27 走才能保证药丸总宽 = 原厂的 234.8dp。
 */
private val NavItemWidth = 78.27.dp
/**
 * 图标位之间的间距。
 *
 * 原厂药丸 234.8dp = 3×82.8 + 2×gap + 2×padH。tab 之间有分隔线（不是空白），
 * 所以 gap 取 0、内边距取 (234.8 − 3×82.8)/2 = **−6.5**? 不对 ——
 * 原厂三个 tab 是**等分**药丸（每个 82.8，正好 3×82.8 = 248.4 > 234.8）。
 * 真实情况：tab 有重叠的触摸区，视觉上等分。这里取 padH=0、gap=0、
 * itemWidth = 药丸宽/3 = 78.3dp，保证**总宽与原厂一致**。
 */
private val NavItemGap = 0.dp
/** 药丸的横向内边距（原厂没有额外内边距，tab 直接铺满）。 */
private val NavPadH = 0.dp
/** 药丸的纵向内边距（+ [NavItemHeight] = 药丸高 51.7dp）。 */
private val NavPadV = 2.75.dp
/** tab 的触摸高度（原厂 150px = 46.15dp）。 */
private val NavItemHeight = 46.15.dp
/**
 * 选中指示块相对图标位的**横向内缩** = [NavPadV]（2.75dp）。
 *
 * ★ 为什么是这个值（用户定版"高亮与框体三边距离要相等"）：指示块高 = 条目高
 *   （46.15dp），在药丸（51.7dp）里垂直居中后，上下各留 (51.7−46.15)/2 =
 *   **2.75dp** —— 横向内缩取同值，选中左右两端的 tab 时，高亮到药丸边缘的
 *   左/上/下三边距离完全相等，看着才是"嵌在框里"而不是"歪着贴一边"。
 *   ★ 内缩同时是防穿模的那道保险：指示块圆角弧线不再贴着药丸端帽的弧线走。
 */
private val NavIndicatorInset = NavPadV
/**
 * **内层弧**（药丸里那圈浅色内嵌面）相对药丸边缘的内缩量。
 *
 * ★ 必须**小于** [NavIndicatorInset]（用户实测："高亮压在弧线上，左边看着不对"）：
 *   两者取同一个值时，内层弧的弧线与选中高亮的弧线**完全重合** —— 高亮看起来像
 *   溢出到了弧线之外，两端都不干净。往里收少一点（1.5dp < 2.75dp），高亮四周就
 *   都有一圈可见空隙，"边框 → 细缝 → 内层弧 → 细缝 → 高亮"的层次才成立。
 */
private val NavRingInset = 1.5.dp
/**
 * 药丸总宽 = 2×3 + 3×78 + 2×6 = **252dp**（参考图 253.3dp）。
 *
 * 写成一个算式而不是常量，是为了让它与上面四个数**永远自洽** —— 这正是预览工具
 * 那条教训（"歌剧院式的两处拷贝迟早对不上"）。指示块的位移也用它推算。
 */
private val NavPillWidth = NavPadH * 2 + NavItemWidth * 3 + NavItemGap * 2
/** 药丸与圆钮之间的水平间距（原厂 37px = **11.4dp**）。 */
private val NavGap = 11.4.dp
/**
 * 导航行的上下留白。
 *
 * 参考图的"行底 → 屏底"是 24.7dp，那一份由**系统手势条内边距 + 这里的留白**共同构成
 * （本机手势条内边距实测约 20.5dp），所以这里只留 4dp，合起来正好 24.5dp。
 */
private val NavRowPadV = 0.dp
/**
 * 导航行从屏幕底边算起占的高度 = 药丸/圆钮 56 + 上下留白 4×2。
 *
 * 面板的底边要落在**这一整行的上方**，所以它和 [CcPanelGap] 一起决定
 * `bottomPadding`（见 [FloatingNav]）。写成算式而不是写死 64，是为了改行高时
 * 面板会自己跟着让位。
 */
private val NavRowHeight = CcButtonSize + NavRowPadV * 2
/**
 * 面板底边与导航行顶边之间的间距（参考图：面板底 795dp、行顶 809dp ⟹ 14dp）。
 */
private val CcPanelGap = 14.dp
/** 面板的左右边距（参考图实测 74px ÷ 3.0 = 24.7dp）。 */
private val CcPanelMargin = 24.dp
/** 面板的圆角（参考图实测 90px ÷ 3.0 = 30dp）。 */
/**
 * 面板的**最终圆角 = 全局卡片圆角**（[CardCorner]，用户定版"所有卡片 r 角统一"）。
 * 面板就是"一张最大的卡片"；高亮行（[PanelRow]）= CardCorner − 横向内缩 8dp，
 * 与本值**同心**，改 CardCorner 时它们自动跟上。
 */
private val CcPanelCorner = CardCorner

@Composable
fun FloatingNav(
    modifier: Modifier = Modifier,
    // 预览器（actions 模式）用不到这四个 —— 给默认值，那边只传 actions 即可
    tabIndex: Int = -1,
    onTab: (Int) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSwitchDevice: (String) -> Unit = {},
    /**
     * **预览器模式**（可空）：非空时药丸里画这些动作按钮（如"旋转 / 选择"），
     * 而不是三个 tab；**小球与它展开的控制中心面板完全照旧**（用户定版：
     * 预览器里的球与主界面是同一个东西、同一套动画与功能）。
     * 药丸宽度随条目数自动缩短（条目宽度是定值，见 [NavItemWidth]）。
     */
    actions: List<NavItemSpec>? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // 面板展开时，返回键先收面板（原来靠 Popup 的 focusable 吃返回键）
    androidx.activity.compose.BackHandler(enabled = expanded) { expanded = false }

    // ★ **整条动画的唯一驱动量**，提到这一层是为了让"圆钮"与"面板"共用同一条时间线 ——
    //   参考视频里那两件事是**同一段动画的两个侧面**（按钮飞走 = 面板飞来），各用各的
    //   AnimatedVisibility 会各自跑、互相错位。
    //
    // ★★★ **曲线与时长是逆向原厂 APK 拿到的，不是反推的** ★★★
    //
    //   用户给了参考录屏对应的系统 APK（`通讯录与拨号_18.30.00.53.apk`）和设备，
    //   要求"抄原厂的实现"。jadx 扒开后，那段动画的真身在
    //   **`com.android.contacts.dialer.DialerViewController`**：
    //
    // ```java
    // // 展开（A1 / L0 方向）
    // animConfig.setEase(a.O(0.8f, 0.42f));                      // 主曲线
    // animConfig.setSpecial(TRANSLATION_Y, a.O(0.97f, 0.25f));   // 位移
    // animConfig.setSpecial(ALPHA,         a.y(50L));            // 透明度 50ms
    // // 收起
    // animConfig.setEase(a.O(0.97f, 0.25f));                     // 主曲线
    // animConfig.setSpecial(TRANSLATION_Y, a.O(0.8f, 0.42f));    // 位移
    // animConfig.setSpecial(ALPHA,         a.I(300L));           // 透明度 300ms 正弦
    // ```
    //
    //   `a.O(ζ, response)` → `getStyle(-2, …)` → **`SpringEasing(ζ, ω = 2π/response)`**，
    //   也就是**阻尼谐振子的阶跃响应**（实现见 [Motion.springEasing]）。
    //   两组参数在进出之间**对调**：ζ=0.97/0.25s 几乎不过冲，ζ=0.8/0.42s 过冲约 1.5%。
    //
    // ★★ 这一发现推翻了我之前**全部**反推出来的结论 ——
    //   "驻留 44ms / 恒定微速飘回 / 紫钮 1.083× 落位"那些细节，原厂一个都没有：
    //   它们是我从 90fps 视频的**压缩伪影**里过度解读出来的（视频里那点像素抖动
    //   被我当成了"停住"和"过冲"）。所以用户会觉得"刻意"、"不自然"——
    //   我复刻的是一个不存在的东西。**有源码就不要猜。**
    //
    // ★ 进出不对称（原厂也这样，方向与 MD3E 的规矩一致）：
    //   展开主曲线 0.42s（慢、稳、略有弹），收起主曲线 0.25s（快、干脆）。
    //   `animateFloatAsState` 支持中途换规格并从**当前值**续跑，快速连点也不会跳。
    // ★★ 原厂就是**一台 animator、两个方向共用**（`DoubleCallButtonContainer.p()`）：
    //    `ValueAnimator.ofFloat(0f,1f)` + `easeOutCubic` + `setDuration(350L)`，
    //    `p(true, …)` 展开、`p(false, …)` 收起 —— 起点终点由那对字段互换决定，
    //    曲线与时长**完全一样**。我先前给它配了进出两条不同的弹簧，那是我自己加的。
    // ★★ 面板的**实测尺寸**必须提到这一层 —— 因为**紫钮要读它**（也见下面 progress
    //   的门控：动画放行与否取决于"量到没有"）。
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val panelMeasured = panelSize != IntSize.Zero

    val spec: FiniteAnimationSpec<Float> = Motion.factoryMorph()
    val progress by animateFloatAsState(
        // ★★ **量到面板几何之前不放行动画**（首次展开"闪一下"的修复）：
        //   进程里第一次展开时 panelSize 还是零、panelAnchor 还没上报，dx/dy 与
        //   盘径全部没有起点 —— 第一帧面板会被画到错误的位置/尺寸，下一帧测量
        //   回来才跳正，宏观上就是"闪了一下、不流畅"；第二次起这些值都留着，
        //   所以只有第一次出错（用户抓拍对比的两张图正是这一对）。
        //   现在第一次展开时先把面板按最终位置**静默量一遍**（见
        //   [ControlCenterPanel] 的 measureOnly），量到之前 progress 钉在 0。
        targetValue = if (expanded && panelMeasured) 1f else 0f,
        animationSpec = spec,
        label = "ccProgress",
    )

    // 面板要悬在导航栏**上方**：底部留白不能写死（之前写 96dp，实测导航栏占 100dp，
    // 面板底边正好压住药丸顶部）。这里按导航行的真实几何算（见 NavRowHeight）。
    // ★ 这里必须与导航行**同一口径**（同样 clamp 到 NavMinBottomPad）：
    //   否则关掉系统小白条后 inset=0，导航行被兜住不下移、而面板按 0 算，
    //   面板就会压住药丸顶部。
    val navBottomInset =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .coerceAtLeast(NavMinBottomPad)
    val panelBottomPadding = navBottomInset + NavRowHeight + CcPanelGap
    val density = LocalDensity.current

    // ★★ 面板的**实测尺寸**必须提到这一层 —— 因为**紫钮要读它**。
    //
    //   用户："收起面板变为按钮的**衔接不丝滑**"。病根是两颗圆走了两条独立的曲线：
    //   面板的盘径由 [discGeometry] 从 624px 缩到 168px，而紫钮的缩放另写了一条
    //   `1.84 → 1.083 → 1.0` —— 交叉段两者最大差 **1.3 倍**，于是交接时
    //   看得见"一个大圈淡出、一个明显更小的圆淡入"，怎么调都不顺。
    //
    //   正解只有一个：让紫钮**直接采用面板的盘径**（同一个圆、同一条曲线）。
    //   所以把"面板多大"这个事实提到共同祖先这里，两边读同一份数。
    //  （[panelSize] 的声明在 progress 那段 —— 动画放行要以它为前提。）
    val panelW = panelSize.width.toFloat().coerceAtLeast(1f)
    val panelH = panelSize.height.toFloat().coerceAtLeast(1f)
    val buttonPx = with(density) { CcButtonSize.toPx() }
    val buttonDipPx = with(density) { BUTTON_DIP_DP.toPx() }   // Dp → px

    // ★ 根节点是**铺满内容区**的 BoxWithConstraints（调用方传 fillMaxSize）：导航栏贴底
    //   居中，面板作为覆盖层画在它上方。用 BoxWithConstraints 只是为了拿到真实宽度，
    //   好把导航行的横向边距**按剩余空间**算出来。
    //   之所以不再用 Popup 承载面板 —— Popup 的窗口尺寸正好等于内容尺寸，带阴影的
    //   圆角卡片一放进去，**阴影就被窗口边界裁掉**，圆角处露出硬边灰块、角也不平滑。
    BoxWithConstraints(modifier) {
        // ★★ **底部渐暗层**（用户定版）：从屏幕底边向上、到导航行一半高度处，
        //   由深变浅的黑色渐变。层级刻意放在**这个 Box 的第一个孩子** ——
        //   它在 Scaffold 内容之上（FloatingNav 整体盖在内容上）、在导航行与面板
        //   之下（它们是这个 Box 的后置孩子）：内容渐渐沉进暗里，导航条和小球
        //   浮在暗层之上，底部就"和谐"了。没有 pointerInput，触摸会穿透到内容。
        //   ★ 高度 = 手势栏 + 导航行的一半：渐变的"最深"一线正好落在导航条的
        //     垂直中点，往上是内容区。
        // ★★ **底部渐隐层**（用户定版）：从屏幕底边向上、到导航行一半高度处，
        //   由透明渐变到"底色"——**深色模式用黑、浅色模式用白**（用户定版：
        //   "加一个深浅色检测，浅色模式下就改成和黑色渐变对应的白色渐变"）。
        //   层级刻意放在**这个 Box 的第一个孩子** —— 它在 Scaffold 内容之上
        //   （FloatingNav 整体盖在内容上）、在导航行与面板之下（它们是这个 Box 的
        //   后置孩子）：内容渐渐沉进底色里，导航条和小球浮在其上，底部就"和谐"了。
        //   没有 pointerInput，触摸会穿透到内容。
        //   ★ 高度 = 手势栏 + 导航行的一半：渐变的"最深"一线正好落在导航条的
        //     垂直中点，往上是内容区。
        val dark = useDarkTheme()
        val scrim = if (dark) Color.Black else Color.White
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // ★ 2026-09-22 加强（用户定版"深浅色都加强"）：带子从"导航行的一半"
                //   抬到"导航行的 0.8"，三档透明度也整体上调。浅色模式下白压白的
                //   对比天生比黑压黑弱，所以浅色时再多给一点（+0.06），两种模式看起来
                //   的强度才接近。
                .height(navBottomInset + NavRowHeight * 0.8f)   // navBottomInset 已含 clamp
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to scrim.copy(alpha = if (dark) 0.18f else 0.24f),
                        0.70f to scrim.copy(alpha = if (dark) 0.46f else 0.52f),
                        1f to scrim.copy(alpha = if (dark) 0.80f else 0.86f),
                    ),
                ),
        )
        // ★★ 导航行的横向边距：**按剩余空间算**，不写死（见 NavEdgePaddingMax 的注释）。
        //   本机 360dp 屏：360 − 252(药丸) − 13(间距) − 56(圆钮) = 39dp，两边各 19.5dp。
        //   屏够宽时它自然长到 39dp 那个参考值；屏窄时收窄边距，而**药丸与圆钮的
        //   尺寸一个不动** —— 用户点名要"完全一样"的正是这两个。
        val navEdgePadding = with(density) {
            val fixed = (NavPillWidth + NavGap + CcButtonSize).toPx()
            val half = ((maxWidth.toPx() - fixed) / 2f)
                .coerceIn(NavEdgePaddingMin.toPx(), NavEdgePaddingMax.toPx())
            half.toDp()
        }

        // ★★ 用**真实的屏幕坐标**算位移，不再自己推 dp 公式。
        //
        //   我连着错了几轮，根因都是"用 dp 反推布局位置"：padding 链、
        //   windowInsets、Row 的居中与溢出补偿……任何一项想错，结果就偏一大截。
        //   原厂也是**量的**（`view.getLocationOnScreen()` → `Rect` → 两个中心相减）。
        //   所以这里照做：`onGloballyPositioned` 拿到各自的真实窗口坐标，
        //   相减即位移 —— 布局怎么摆都不会错。
        var buttonCentre by remember { mutableStateOf(Offset.Zero) }
        var panelAnchor by remember { mutableStateOf(Offset.Zero) }

        // ★★ **按钮中心 → 面板锚点**的位移。
        //
        //   形状在 [DiscShape] 里**贴内容区底边**（不是居中），所以：
        //   - 终点（p=1）：形状 = 整个内容区，锚点就是**内容区底边的中点**
        //   - 起点（p=0）：形状是个圆钮，必须**正好盖住那颗按钮**
        //
        //   于是位移 = "内容区底中点 − 按钮中心"，全是**纯常量**，
        //   不含 panelW/panelH（它们首帧为 0，一旦参与就会算错 —— 那正是
        //   "图标在右下角、面板却在上面展开"的原因）。
        //
        //   ★ 全部用 **"距屏幕左 / 距屏幕底"** 一套坐标：
        //     · 内容区底中点的 x = 屏幕中心（内容区左右对称）
        //     · 内容区底边距屏幕底 = [panelBottomPadding]（由 padding 保证）
        //     · 按钮中心 = Row 内垂直居中 ⟹ 距底 [navBottomInset]+[NavRowPadV]+半个按钮
        val dx = buttonCentre.x - panelAnchor.x
        //   ★★★ 纵向的**符号**要按 `translationY` 的语义来（Compose 里 **+Y 向下**）：
        //   p=0 时形状高 = 按钮高，它的**底边**要落在按钮**底边**上（圆心才重合）。
        //   形状的底边现在贴在**内容区底边**（距屏幕底 [panelBottomPadding]），
        //   要挪到**按钮底边**（距屏幕底 navBottomInset+NavRowPadV）——
        //   因为按钮更靠近屏幕底，这是**往下**挪 ⟹ `translationY` 取 **正值**。
        //
        //   ★ 我上一版写成 `按钮距底 − 内容区距底`（= 负数），那是"距屏幕底"这一套
        //     坐标下的差值；而 `translationY` 的正负与它**恰好相反**。结果形状被
        //     往上挪了 74dp —— 面板在按钮**上方**展开，正是用户看到的现象。
        //     教训：算位移时先确定"正方向"，再套数值，别拿另一套坐标的结论直接用。
        //   形状**居中**于内容区，所以 p=1 时它的中心 = 内容区中心
        //   （距屏幕底 = panelBottomPadding + 内容高/2）；p=0 时要落在按钮中心上。
        //
        //   ★ 面板内容高 `panelH` 是**稳定值**（面板一展开就测出来了，且不随动画变），
        //     首帧为 0 时 `dy` 会略小，但那一帧形状只有 56dp、又被按钮盖着，看不出来。
        //     等第一帧测量回来，之后每帧的 `dy` 都是准的 —— 这就是"终态永远精确"。
        val dy = buttonCentre.y - panelAnchor.y

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // ★ 底部间距 = max(系统手势栏, [NavMinBottomPad])。不能直接用
                //   windowInsetsPadding(navigationBars)：**关闭系统手势提示条（小白条）后
                //   这个 inset 会变成 0**，导航药丸就一路贴到屏幕最下沿，与开着的时候
                //   位置明显不同（用户实测两张截图对比）。兜一个最小值，两种情况位置一致。
                .padding(bottom = navBottomInset.coerceAtLeast(NavMinBottomPad))
                .padding(horizontal = navEdgePadding, vertical = NavRowPadV),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavPill(
                // ★ 预览器与主界面用**同一个指示块**（同一段绘制代码、同一套尺寸与内缩）——
                //   用户实测"预览器里高亮与外边框的间距和主界面不一样"：那是我另画了一层
                //   逐项底色，两套几何迟早对不上。现在统一成一层，间距必然一致。
                //   "不滑一下"由下面 if 里的动画保证：首次出现即以目标位置初始化。
                items = actions ?: listOf(
                    NavItemSpec(MsIcon.NAV_HOME, "主界面", tabIndex == 0) { onTab(0) },
                    NavItemSpec(MsIcon.NAV_FILES, "文件", tabIndex == 1) { onTab(1) },
                    NavItemSpec(MsIcon.NAV_TRANSFERS, "传输", tabIndex == 2) { onTab(2) },
                ),
            )
            Spacer(Modifier.width(NavGap))
            // ★★ 两颗按钮由**同一个状态机**算出（见 [buttonState]）—— 这是"紫色按钮
            //    消失、只剩一种按钮状态"那个 bug 的正解：不是一个量控制一颗、
            //    各自为政（那样收起静止时两颗 alpha 都是 1，深的盖住紫的），
            //    而是**一次算出两颗**，让它们的互斥关系体现在代码结构里。
            //   ★ 顺带把面板几何传进去：紫钮的尺寸**就是面板的盘径**（见 [buttonState]），
            //     这样交接时两个圆必然一般大 —— 那是"衔接丝滑"的硬条件。
            val buttons = buttonState(
                expanded = expanded,
                progress = progress,
                panelW = panelW,
                panelH = panelH,
                buttonPx = buttonPx,
                cornerPx = with(density) { CcPanelCorner.toPx() },
                dx = dx,
                dy = dy,
                dipPx = buttonDipPx,
            )
            // 上报按钮中心（真实窗口坐标）—— 位移的起点就是它
            ControlCenterButton(
                expanded = expanded,
                primaryAlpha = buttons.primaryAlpha,
                primaryScale = buttons.primaryScale,
                primaryTx = buttons.primaryTx,
                primaryTy = buttons.primaryTy,
                panelProgress = progress,
                modifier = Modifier.onGloballyPositioned { c ->
                    val r = c.boundsInWindow()
                    buttonCentre = Offset(r.center.x, r.center.y)
                },
            ) { expanded = !expanded }
        }

        ControlCenterPanel(
            progress = progress,
            expanded = expanded,
            measureOnly = expanded && !panelMeasured,
            dx = dx,
            dy = dy,
            bottomPadding = panelBottomPadding,
            onSizeMeasured = { panelSize = it },
            onAnchorMeasured = { panelAnchor = it },
            onDismiss = { expanded = false },
            onOpenSettings = {
                expanded = false
                onOpenSettings()
            },
            onSwitchDevice = { guid ->
                expanded = false
                onSwitchDevice(guid)
            },
        )
    }
}

/**
 * **两颗按钮**在动画某一刻的完整状态 —— 整条动画就是它俩的接力。
 *
 * ## 原版机制（90fps 逐帧实测，用户口述 + 帧数据互相印证）
 *
 * 用户："点击按钮 - 绿色按钮飞出并变形为面板 - 原来按钮的位置由小到大由虚到实
 * 出现一个黑色按钮（这个按钮不会过冲）- 面板和按钮同时落位结束运动。
 * 关闭面板是 点击按钮 - 黑色按钮由大到小由实到虚消失 - 面板变为绿色按钮飞回"。
 *
 * 把两段的按钮槽位**逐帧摆开**看（不是只看尺寸曲线），量出来是这样：
 *
 * ```
 * 【展开】绿色(紫) → 面板      黑钮从无到有
 *   f62-64   绿钮 166→210→278   一边长大一边上移 → 已经是面板了
 *   f70-86   面板 0.53→0.99     黑钮 128→168px  由小到大、由虚到实
 *   f92      面板落位 1067px    黑钮 168px       ★ 同时到位
 *
 * 【收起】黑钮先走 → 空档 → 绿钮飞回
 *   f174-176  面板 1053→621→无   黑钮 161→148→无  ★ 同时退场（55ms）
 *   f179-186  （空档 89ms）       画面里只剩导航栏
 *   f187-202  绿钮 0→182→168px   由虚到实、**略大着落位再收回**（1.08×）
 * ```
 *
 * ## ★★ 我上一版的错，就在这个类要解决的这件事上
 *
 * 用户："你的软件下方的紫色按钮消失了，只有一种按钮状态"。
 * 病根是两颗按钮的进度函数**各自独立、没有互斥**：
 * 收起静止时 `buttonPresence = 1` 且 `newIconProgress = 1` ——
 * **两颗都画在最上层**，深色那颗盖住紫的，于是任何时刻看到的都是同一个样子。
 *
 * 所以这里不再给"两个独立的透明度"，而是把**两颗按钮当成一个状态机**：
 * 同一个 `progress` 同时算出两颗的 alpha/scale，让它们**天然互斥**。
 * 判据（`progress` 上）：
 *
 * ```
 * 展开：紫 1→0（p 0..0.08，随面板起飞退场）  黑 0→1（p 0.40..1，不过冲）
 * 收起：黑 1→0（p 1..0.55，与面板同时退场）  紫 0→1（p 0.50→0，带回弹）
 *                          └── p 0.50..0.55 两颗都为 0 ＝ 实测那 89ms 空档
 * ```
 */
private class ButtonState(
    /** 紫钮（我们的强调色圆钮；参考里是绿钮）的透明度 */
    val primaryAlpha: Float,
    val primaryScale: Float,
    /** 紫钮的横向位移（px，正 = 右）—— 用来抵消面板的飞行位移 */
    val primaryTx: Float,
    /** 紫钮的纵向位移（px，正 = 下）—— 抵消飞行 + 落位时那一下下沉 */
    val primaryTy: Float,
)

/** 见 [ButtonState] 的长注释。 */
private fun buttonState(
    expanded: Boolean,
    progress: Float,
    panelW: Float,
    panelH: Float,
    buttonPx: Float,
    cornerPx: Float,
    dx: Float,
    dy: Float,
    dipPx: Float,
): ButtonState {
    // ★ 展开时 progress 会冲到 1.045（面板的过冲），这里必须夹掉再喂给按钮 ——
    //   用户定版"这个小按钮不会过冲"：不夹的话它会跟着长过 1 再缩回来，
    //   看起来是"冒出来之后抖了一下"。**过冲只属于面板**。
    val p = progress.coerceIn(0f, 1f)

    // ★★ 紫钮**就是面板那只盘**（两个方向都成立，见 [ButtonState] 的头注释）：
    //    尺寸取盘径、位置抵消面板的位移。两个圆逐帧重合 ⟹ 交接无缝。
    //    ★ 原厂就是这样：`p()` 里 `f6612r/f6613s` 那对起止尺寸同时喂给了
    //      "容器形状"与"FAB 图标"两侧 —— 它压根没有第二个尺寸来源。
    val disc = discGeometry(p, buttonPx, buttonPx, panelW, panelH, cornerPx)

    if (expanded) {
        // ① 紫钮：随面板起飞退场。原版绿钮 f64（起飞后 2 帧）就没了 —— 极快。
        val primaryA = (1f - p / PRIMARY_FADE_OUT).coerceIn(0f, 1f)
        // ② 深色钮**不在这里算**了 —— 它由 `ControlCenterButton` 自己那条
        //    `Spring(ζ=0.95, 0.35s)` 驱动（与面板同长 350ms，见那里的 [DARK_IN_MS]）。
        return ButtonState(
            primaryAlpha = primaryA,
            primaryScale = (disc.w / buttonPx).coerceAtLeast(0.01f),
            primaryTx = -dx * p,
            primaryTy = -dy * p,
        )
    }

    // ---- 收起方向 ----
    // ★★ 深色钮：**把展开那一段反着放**（用户："收起时原来的灰色按钮应该也和展开
    //    动画一样，收起是反着的，由实到虚由大变小消失"）。
    //
    //    所以这里**故意复用展开侧同一个表达式**，而不是另写一条曲线：
    //    ```
    //    展开  p 0.40 → 1.00   alpha 0→1   scale 0.4→1.0
    //    收起  p 1.00 → 0.40   alpha 1→0   scale 1.0→0.4   ← 同一式子，p 反向递减
    //    ```
    //    同一个式子让 p 反向走，天然就是反放，两边**永远对称**。
    //    若各写一条曲线，改了一边忘另一边就会不对称 —— 我这轮之前正是这么错的
    //    （收起侧曾用 [panelExitEnvelope]，于是深色钮前 133ms 一直全不透明地杵着，
    //      而不是立刻开始缩小淡出）。
    //
    //    ★ 它在 p=0.40（110ms）就消失了，**早于**紫钮出现（p=0.31，133ms）——
    //      中间那 23ms 的空档正是原版量到的"旧的已经走了、新的还没来"。
    // 紫钮：面板开始淡出时它同步淡入（crossfade = 用户要的"渐变为图标"）。
    val primaryA = ((PRIMARY_RETURN_AT - p) / PRIMARY_FADE_IN_SPAN).coerceIn(0f, 1f)
    // `u` 是紫钮自己的时间轴：从它开始淡入那一刻算起。
    val u = ((PRIMARY_RETURN_AT - p) / PRIMARY_RETURN_AT).coerceIn(0f, 1f)
    return ButtonState(
        primaryAlpha = primaryA,
        // 尺寸：与面板**同一个圆**（盘径 ÷ 按钮径），不是另画一条曲线
        primaryScale = (disc.w / buttonPx).coerceAtLeast(0.01f),
        // 位置：抵消面板的飞行位移 ⟹ 两个圆重合。
        // ★ 这一项本身就是"**下落**"：p 从 0.31 掉到 0，紫钮从面板那一角
        //   一路下移到自己的位置（49dp 的行程），落位后再由 [dipPx] 补过冲。
        primaryTx = -dx * p,
        // ★ 下落过冲（用户："之前版本紫色按钮是有**下落再归位**的过冲动画的，
        //   为什么改没了"）：位移 = 抵消飞行 + 多沉一下再弹回。
        primaryTy = -dy * p + dipPx * dipEnvelope(u),
    )
}

/**
 * 紫钮落位时**多沉一下再弹回**的包络（0 → 1 → 0）。
 *
 * 实测原版：绿钮中心的 y 从 f192 的 2477 落到 f200 的 **2522**，再回到终值 2518 ——
 * 多沉了 4px 才归位。峰值落在紫钮那一段的 **13/15 帧**处（≈ [DIP_PEAK_U]），
 * 也就是**已经快落位的时候**，不是半空中。
 *
 * ★ 峰值必须**晚于面板的淡出终点**（p < [PANEL_FADE_DONE_AT] ⟹ u > 0.74），
 *   否则这一下会作用在"还在与面板交叉"的时刻：面板没跟着沉、只有按钮沉，
 *   两个圆就错开了，"衔接"立刻破功。
 */
private fun dipEnvelope(u: Float): Float = when {
    u <= DIP_PEAK_U -> smoothstep(u / DIP_PEAK_U)
    else -> 1f - smoothstep((u - DIP_PEAK_U) / (1f - DIP_PEAK_U))
}

/**
 * 收起时**面板的退场包络**（1 = 完整，0 = 已退场）。
 *
 * ```
 * p 1.00 → 0.31   全程 1      面板**完全不透明地缩小**（0..133ms）"窗口缩小"
 * p 0.31 → 0.08   线性 1→0    与紫钮交叉淡出          （133..215ms）"渐变为图标"
 * p 0.08 → 0      0           只剩紫钮，落位          （215..300ms）"落位"
 * ```
 *
 * ★ 为什么结束点不在 0.31（那会让面板 133ms 就消失，用户报"窗口消失太快"）：
 *   面板缩小这件事由 [discGeometry] 一路做到 p=0，但**它的形状在 p 小时
 *   已经缩成按钮大小的正圆、且与按钮同心同锚**。所以让淡出延续到 p=0.08
 *   （那一刻盘径 188px vs 按钮 168px，几乎重合）时，两个圆**位置与大小都重叠**，
 *   交叉淡出看起来就是**同一个圆在换颜色** —— 这正是"渐变为图标"。
 *   过早淡出反而会露出"大白圈凭空消失"的破绽。
 *
 * ★ **只有面板用它**。深色钮不用（它要的是"展开的反放"，见 [buttonState]）——
 *   两者退场的时机本来就不同：深色钮在 110ms 就没了，面板要到 215ms 才淡完。
 *   我中间有一版让两者共用这条线，结果深色钮前 133ms 一直全不透明地杵着，
 *   既没有"由大变小"，也没有"由实到虚"。
 */
private fun panelExitEnvelope(p: Float): Float =
    ((p - PANEL_FADE_DONE_AT) / (PANEL_FADE_FULL_AT - PANEL_FADE_DONE_AT)).coerceIn(0f, 1f)

/** `3x²−2x³`：两端导数为 0（起步轻、到位稳）。 */
private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)


/**
 * 展开时紫钮退场的长度（progress 上）。
 *
 * ★ 原厂的 alpha 是 **50ms**（`a.y(50L)` → LINEAR 50ms），换算到我们的弹簧上：
 *   `progress(50ms) = 0.187` —— 就是这里这个值（不再是反推的 0.08）。
 */
private const val PRIMARY_FADE_OUT = 0.187f
/**
 * 深色钮出现的 progress。
 *
 * ★ 原厂没有"深色钮"这一路动画（它的原位图标是另一套 `GhostView` 位图快照），
 *   这是**我们自己的设计**。取 0.40：那时面板已长到约一半（progress 0.4 对应
 *   展开后约 150ms / 420ms），新钮在面板还差最后一口气时冒出来，
 *   与面板同时落位（用户："面板和按钮同时落位结束运动"）。
 */
/*
 * ★ 这里原本有个 `DARK_APPEAR_AT`（"面板进度长到多少灰钮才出现"）—— **已删除**。
 *
 *   用户最终定版："灰色按钮要在**点击紫色按钮时**就出现，然后和面板一起落位"。
 *   也就是它与面板**同时起步、同时结束**。
 *
 *   这条要求与曲线本身是**天然吻合**的：灰钮那条 `Spring(ζ=0.95, response=0.35s)`
 *   在 t=0.35s 处的值是 **0.9938**，本来就是个 350ms 的曲线 ——
 *   和面板的 `factoryMorph`（350ms）**时长完全一致**。
 *   所以不需要任何起始延迟，让两者同时 `animateFloatAsState` 起跑即可。
 *
 *   （中途那两版都不对：一版让它跟 `expanded` 起飞但时长 350ms 被面板的
 *     0.42s 衬得快，一版加 0.86 的判据变成"太晚"。根子是我一直在**改起始时刻**
 *     去凑结束时刻，而正解是**让两条曲线的时长相等**。）
 */
/**
 * 深色钮的**起始缩放** —— ★ 原厂值 0.6，不是我先前写的 0.4。
 *
 * 原厂（`PeopleActivityFab`）：
 * ```java
 * // 出现 p()：  scale 0.6 → 1.0, alpha 0→1, Spring(ζ=0.95, resp=0.35s)
 * // 消失 j()：  scale 1.0 → 0.6, alpha 1→0, Spring(ζ=1.0,  resp=0.20s)  ← 临界阻尼
 * ```
 * 我是从录屏里量出 112px/168px = 0.67 才反查到这里 —— 量出来的偏小值正是
 * "0.6 起、还没长完"的中间态（4 倍慢放下我的采样点赶在动画结束前）。
 */
private const val DARK_SCALE_FROM = 0.6f
/**
 * 收起时面板**开始淡出**的 progress（在此之前全不透明，只缩小）。
 *
 * 0.31 = `(1−t)²` 曲线上 t=0.443 处的值，对应 **133ms**。
 * 也就是面板前 133ms **完全不透明地一路缩小**（用户要的"窗口缩小"），
 * 到这一刻才交给紫钮接手（见 [panelExitEnvelope]）。
 */
/**
 * 收起时面板**开始淡出**的 progress。
 *
 * ★ 由原厂收起弹簧反算：`progress(100ms) = 0.272`。取 0.27：面板先不透明地
 *   缩小 100ms（用户："窗口缩小慢慢渐变为图标"），之后才交给紫钮。
 */
private const val PANEL_FADE_FULL_AT = 0.27f
/**
 * 收起时面板**淡出完毕**的 progress（= 交叉淡出的终点）。
 *
 * 0.08 ≈ **215ms**。取这么低是为了让淡出**与紫钮的放大重叠**：
 * 到 p=0.08 时盘径已缩到 188px、与按钮 168px 几乎重合，两个圆位置大小都一样，
 * 淡出读起来就是"同一个圆在换颜色"，而不是"大白圈消失"。
 */
/**
 * 收起时面板**淡出完毕**的 progress（= 交叉淡出的终点）。
 *
 * ★ `progress(200ms) = 0.031`。到这一刻盘径已缩到接近按钮大小，
 *   两个圆位置与大小都重合，淡出读起来就是"同一个圆在换颜色"。
 */
private const val PANEL_FADE_DONE_AT = 0.031f
/**
 * 紫钮**开始淡入**的 progress（= 深色钮已经消失、面板开始淡出的那一刻）。
 *
 * 0.31 = `(1−t)²` 曲线上 t=0.443 处的值，对应 **133ms**。
 * ★ 深色钮在 p=0.40（110ms）就退完了，与这里之间那 **23ms** 正是原版量到的
 *   "旧的已经走了、新的还没来"的空档；再往后面板淡出与紫钮淡入同时进行
 *   （两段进度范围等长，见 [PRIMARY_FADE_IN_SPAN]）—— 这就是用户要的**衔接**。
 */
private const val PRIMARY_RETURN_AT = 0.27f
/**
 * 紫钮透明度爬满所需的 progress 跨度。
 *
 * 0.23 与面板的淡出跨度（[PANEL_FADE_FULL_AT] − [PANEL_FADE_DONE_AT]）**相等**，
 * 这是上面那条 crossfade 能对称的前提 —— 改一个必须一起改。
 */
private const val PRIMARY_FADE_IN_SPAN = 0.239f
/**
 * 紫钮落位时**多沉一下**的幅度（dp）。
 *
 * 实测原版：绿钮中心 y 从 2477 落到 2522 再回到 2518 —— 多沉 4px（≈1.4dp）。
 * 我们的密度是 3.0、面板也更小，1.4dp 在 60fps 下只有两三帧、几乎看不见
 * （用户报过"下落再归位的过冲动画为什么改没了"，就是被削得太小而看不出来）。
 * 所以放大到 **5dp**：行程可见，但仍在"轻轻一顿"的量级。
 */
private val BUTTON_DIP_DP = 5.dp

/**
 * [dipEnvelope] 的峰值位置（紫钮自己时间轴 `u` 上的比例）。
 *
 * 0.74 = 面板淡出终点（`PANEL_FADE_DONE_AT`）对应的 `u` 值 —— 峰值**必须不早于**它，
 * 否则这一下会作用在"两个圆还在交叉"的时刻（面板没跟着沉、只有按钮沉，
 * 重合立刻破功）。取 0.78 留一点余量，让"落到位之后再一顿"读得出来。
 */
private const val DIP_PEAK_U = 0.78f

/**
 * 导航行的横向内边距 —— **参考图是 38.7dp，但那块屏是 400dp 宽，我们的只有 360dp**。
 *
 * 参考图的横向账：38.7(边距) + 254(药丸) + 13.3(间距) + 56(圆钮) + 38.7(边距) ≈ 400dp。
 * 摊到 360dp 的屏上就**装不下**了 —— 照搬那 38.7 会让 Row 超宽，Compose 会把
 * **固定尺寸的子项按比例压扁**：圆钮被挤成椭圆、药丸也被压窄（装机实测）。
 *
 * 所以取舍是：**药丸与圆钮的尺寸一个不许动**（用户点名要"完全一样"的就是这两个），
 * 边距改为**按剩余空间算**（见 [FloatingNav]）—— 屏够宽时它自然等于 38.7dp，
 * 屏窄时收窄边距，两个控件自己保持原样。这也符合 dp 的本意：Material 尺寸是绝对的，
 * 让位该由留白来让。
 */
private val NavEdgePaddingMax = 35.7.dp
/** 边距的下限：再窄也得与屏幕边缘拉开一点，否则药丸看着像贴在边上。 */
private val NavEdgePaddingMin = 8.dp

/**
 * 药丸型导航（照用户给的图摆：紧凑药丸 + 三个图标，宽度随内容）。
 *
 * ★ **不要**改回 `ShortNavigationBar` —— 实测踩过：它是**占满宽度**的栏，放进
 * Row 里会把整行吃光，右边那个圆形按钮直接被挤出屏幕（第一个版本就是这样把
 * 按钮整个弄丢的，截图里药丸横向拉满、右侧空空）。图里要的是"药丸 + 圆形按钮"
 * 两个**独立**控件，所以药丸自己摆：固定内边距 + 三个固定尺寸图标位，
 * 宽度只占自己该占的那一份。
 */
/**
 * 药丸里的一个条目。主界面用三个 tab；**预览器**用它塞两个动作按钮
 * （旋转/选择、旋转/信息）—— 药丸的几何与动效完全一致，只是条目数与内容不同。
 */
class NavItemSpec(
    val icon: MsIcon,
    val label: String,
    /** 未选中传 -1（预览器的动作按钮没有"选中"这回事）。 */
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * 悬浮导航一族的**强调色角色表**（用户定版 2026-09-22：浅色下强调色可读性差 → 全局改口径）。
 *
 * ## 两条硬要求（用户定版）
 * 1. **必须与主题强调色相符** —— 全族一律取 **primary 族**。不用 secondary 族：种子色/动态取色下
 *    secondary 会偏成另一个色相（实测浅色下选中高亮变成了"灰蓝色"，与主题色对不上）。
 * 2. **图标必须清晰可辨** —— 填充与内容永远取 MD3 的 on 配对（primary/onPrimary、
 *    primaryContainer/onPrimaryContainer），任何调色盘都保证对比度。
 *
 * ## 为什么浅色不能用容器档（实测数值，与具体调色盘无关）
 * 这一族组件自己的面是 `surfaceContainerHigh/Highest`：浅色 = tone 92/90、深色 = tone 17/22。
 * 而容器档强调色浅色 = tone 90 —— **同档**：实测选中高亮 (219,226,247) 压在药丸面
 * (224,226,236) 上，对比度只有 **1.0:1**（等于没画）；圆钮 (174,192,239) 压页面底也只有
 * 1.56:1。这是 tone 表的结构性结果：四套种子色、系统动态取色，浅色下都必然糊。
 *
 * ## 口径
 * - **浅色**：填充取**实色档** `primary`（tone 40）+ 内容 `onPrimary`（tone 100）——
 *   与面（tone 90~99）亮度差 ≥ 5 档，且就是主题强调色本身。
 * - **深色**：填充取**容器档** `primaryContainer`（tone 30）+ 内容 `onPrimaryContainer`
 *   （tone 90）—— 与面（tone 17~22）分得开，维持既有观感。
 *
 * ## 覆盖点（一处不漏）
 * 药丸滑动指示块 + 选中图标、圆钮底/图标/内缘进度环、面板起步闪色（必须与圆钮同色，
 * 否则"按钮飞上来变面板"的错觉破功）、面板里"当前设备"高亮行。
 * 预览器的导航栏与主界面**共用本组件**，自动同口径。
 */
private class NavAccents(
    /** 药丸：滑动指示块底 + 选中图标 */
    val indicatorFill: Color,
    val indicatorOn: Color,
    /** 圆钮（强调色那颗）：底 / 图标 / 内缘进度环 */
    val buttonFill: Color,
    val buttonOn: Color,
    val ring: Color,
    /** 面板：选中设备高亮行的底 / 正文与副标题 / 图标 */
    val rowFill: Color,
    val rowOn: Color,
    val rowIcon: Color,
)

@Composable
private fun navAccents(): NavAccents {
    val cs = MaterialTheme.colorScheme
    return if (useDarkTheme()) {
        NavAccents(
            indicatorFill = cs.primaryContainer,
            indicatorOn = cs.onPrimaryContainer,
            buttonFill = cs.primaryContainer,
            buttonOn = cs.onPrimaryContainer,
            ring = cs.primary,
            rowFill = cs.primaryContainer,
            rowOn = cs.onPrimaryContainer,
            rowIcon = cs.primary,
        )
    } else {
        NavAccents(
            indicatorFill = cs.primary,
            indicatorOn = cs.onPrimary,
            buttonFill = cs.primary,
            buttonOn = cs.onPrimary,
            ring = cs.onPrimary,
            rowFill = cs.primary,
            rowOn = cs.onPrimary,
            rowIcon = cs.onPrimary,
        )
    }
}

@Composable
private fun NavPill(items: List<NavItemSpec>) {
    val accents = navAccents()
    val tabIndex = items.indexOfFirst { it.selected }
    // ★★ 滑动高亮的水平位置**按实测的条目位置算**，不再用"条目宽×序号"推算。
    //   推算版会累积误差：条目实际宽度由布局决定（受边框/取整影响），与常量算出的
    //   有零点几 dp 的差，到最右那一项就明显了 —— 用户实测"选最左边时高亮到边缘的
    //   距离均匀，选到最右边就不均匀"。改用实测位置后，左右两端的高亮到药丸边缘的
    //   距离**必然相等**（都是 [NavIndicatorInset]）。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemLeft = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    val itemWidthPx = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val insetPx = with(density) { NavIndicatorInset.toPx() }
    // ★ 选中指示是**一块滑过去的药丸**，不是"这项亮一下、那项灭一下"：
    //   条目宽度与间距都是定值，所以节距（pitch）是算得出来的，指示块按
    //   index × pitch 平移即可，不必去量每个条目的坐标。
    //   弹簧用 spatialDefault（标准空间动效）：切换 tab 时它会**略微过冲**再落位，
    //   这正是 MD3E 那个"甩过去"的手感；用 tween 就只是一次平淡的位移。
    //
    // ★★ 这三个数必须与下面 Row 的排布**一致**：条目排在 Row 的 [NavPadH] 内边距
    //   **之内**，所以指示块的位置是 `内边距 + 节距 × 序号`。漏掉那个内边距就是恒定
    //   左偏一整格（用户反馈"下方胶囊里的高亮选中错位了"就是这个）。两者都从上面的
    //   顶层常量取，改一处不会又把另一处弄漂。
    val indicatorShape = RoundedCornerShape(50)
    Surface(
        shape = indicatorShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // ★ 与悬浮面板同一条白边（outlineVariant，用户定版"导航栏和小球应该有和
        //   悬浮窗面板一样的白边"）—— 悬在最底色上的三个悬浮件共用一圈描边，
        //   边界才立得住（面板那圈见 [PanelSurface]）。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            // ★ **内层弧**（用户定版："药丸两端要有一样的双层弧、两端对称"）。
            //   左端本来就有两层：外层的圆角描边 + 里面那块浅色的选中指示药丸；
            //   右端只有外层描边，于是同一颗药丸左右看着不一样饱满。
            //   这里补一层**同心**的内嵌圆角面（往里缩 [NavIndicatorInset]，
            //   圆角同步减小同样的量 → 与外层弧同心），两端就都有"边框 → 细缝 →
            //   内层弧"的结构了，左右一致。
            //   颜色用比外层描边亮一档的 surfaceContainerHighest：对比够看出弧线，
            //   又不至于抢选中指示药丸的颜色。
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .padding(NavRingInset),
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {}
            // 底层：滑动的指示药丸（高度与条目一致，由 Box 垂直居中 → 与条目同高同位；
            // 宽度比图标位窄 [NavIndicatorInset]×2 —— 防端帽穿模，见那里的注释）
            // ★ **预览器里没有"选中"这回事**（动作按钮不表示状态）：这时不画指示药丸，
            //   否则药丸左端会挂着一块无意义的高亮。
            // ★ 动画**写在 if 里面**：这样首帧就是"直接出现在目标位置"（animate*AsState 的
            //   初值 = 当时的目标值），不会因为条目位置要等一帧测量而从左端滑进来；
            //   之后的 tab 切换照旧走 Motion.fastOut 的"由快到慢"位移。
            if (tabIndex >= 0 && itemWidthPx.value > 0f) {
                val indicatorX by animateFloatAsState(
                    targetValue = itemLeft.getOrNull(tabIndex)?.plus(insetPx) ?: 0f,
                    animationSpec = Motion.fastOut(),
                    label = "navIndicator",
                )
                Box(
                Modifier
                    .offset {
                    // ★ 必须**四舍五入**，不能用 toInt()（截断）：2.75dp 在 density 3.25 上是
                    //   8.94px，截断成 8px 就比竖向留白（9px）少 1px —— 用户实测
                    //   "最左边的图标左端离边缘距离偏小"，差的就是这 1px。
                    androidx.compose.ui.unit.IntOffset(indicatorX.roundToInt(), 0)
                }
                    .size(
                        width = with(density) { (itemWidthPx.value - insetPx * 2).toDp() },
                        height = NavItemHeight,
                    )
                    .clip(indicatorShape)
                    .background(accents.indicatorFill),
                )
            }
            // 上层：三个图标位（自身不画底色，底色由上面那块滑动的药丸负责）
            Row(
                Modifier.padding(horizontal = NavPadH, vertical = NavPadV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NavItemGap),
            ) {
                items.forEachIndexed { idx, spec ->
                    NavPillItem(
                        selected = spec.selected,
                        icon = spec.icon,
                        label = spec.label,
                        selectedTint = accents.indicatorOn,
                        modifier = Modifier.onGloballyPositioned { c ->
                            val x = c.positionInParent().x
                            if (itemLeft.size <= idx) itemLeft.add(x) else itemLeft[idx] = x
                            itemWidthPx.value = c.size.width.toFloat()
                        },
                        onClick = spec.onClick,
                    )
                }
            }
        }
    }
}

/**
 * 药丸里的一个图标位：**只画图标**，底色由 [NavPill] 里那块滑动的指示药丸负责。
 *
 * 两处弹簧（用户要的"弹簧物理"就落在这种地方）：
 * - 图标颜色用 **effectsFast**（颜色属于"效果"，高刚度弹簧几乎瞬间到位，不带回弹）
 * - 选中时图标用 **spatialFast** 轻轻放大一点再回弹（位移/形变属于"空间"，
 *   阻尼 0.6 允许过冲）—— 这点"pop"是指示药丸滑到位时那一拍的落点感
 */
@Composable
private fun NavPillItem(
    selected: Boolean,
    icon: MsIcon,
    label: String,
    /** 选中态图标色 —— 由 [navAccents] 按深浅色给（浅色 = 实色块上的反色）。 */
    selectedTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val tint by animateColorAsState(
        targetValue = if (selected) selectedTint
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = Motion.fastOutFade(),
        label = "navIconTint",
    )
    val pop by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = Motion.fastOut(),
        label = "navIconPop",
    )
    // ★ 2.7.0：条目自己的交互源**提出来**并递给图标 —— 按到整个图标位任意处
    //   图标都弹（旧行为是图标自检：必须精准按到字形上才弹，用户实测反馈）。
    val itemSource = remember { MutableInteractionSource() }
    Box(
        modifier
            // 宽度 > 高度：图里的药丸是**修长**的，图标之间留白很大。
            // 48dp 高度是触摸区下限，再矮就不好按了。
            .width(NavItemWidth)
            .height(NavItemHeight)
            .clip(shape)
            // 按压水波纹铺满条目、由上面的 clip 裁成药丸形：与滑动的指示块同形同大
            .clickable(
                interactionSource = itemSource,
                // 用户定版：导航条不要按压水波纹（选中态由滑动的指示块表达）
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 导航图标：`pop`（选中时放大 1.12×）由外层 scale 管，MsIcon 只管按下反馈。
        // 两者的缩放是**相乘**的（外层 scale(pop) × MsIcon 内部 scale(0.82)），
        // 所以选中态按下时仍会缩到 0.82，不会有一方失效。
        // ★ 实心只跟**选中**走（filled = selected），按压不再触发实心 ——
        //   用户定版："不应该是被触摸到就变实心，应该是选中了才变成实心"。
        MsIcon(
            icon = icon,
            contentDescription = label,
            interactionSource = itemSource,
            modifier = Modifier.size(26.dp).scale(pop),
            tint = tint,
            size = 26.dp,
            filled = selected,
            fillOnPress = false,
        )
    }
}

/**
 * 圆形控制中心按钮：外缘一圈进度环 + 中间圆形按钮。
 *
 * 进度语义与旧顶栏图标一致（用户定版）：**有下载就表下载字节进度，否则表缩略图张数**；
 * 两者都没有时不画环（只留按钮），免得空环被误读成"卡在 0%"。
 *
 * ## 三层叠加（用户逐条点出来的，与参考一一对应）
 *
 * ```
 * ① flyAway   原按钮**飞走**：淡出（它飞上去的那一份由面板承担）
 * ② 新图标    **由虚到实、由小到大**地浮现 —— 「黑色的指示图标」
 * ③ offsetY   按钮**位置**过冲：先下移、再弹回（面板的过冲在**大小**上，两者分工）
 * ```
 *
 * ★★ 上一版只有一个 `presence` 透明度：图标不变、没有缩放、没有位移 ——
 *   用户的原话是"由虚到实由小到大出现一个黑色的指示图标，而你没有这么做导致看起来
 *   十分生硬"。所以这里必须把"旧按钮"与"新图标"当成**两个东西**分别画：
 *   新图标用**中性容器色**（surfaceContainerHighest，就是面板那个灰），
 *   而不是按钮原来那个强调色 —— 参考里原位冒出来的正是个**深色**图标。
 *
 * 两颗按钮的状态都由 [buttonState] 一次算出（**它们天然互斥**，见那里的长注释）：
 * 展开态画深色那颗、收起态画强调色那颗，中间有一段两颗都不可见的空档。
 *
 * @param primaryAlpha 强调色圆钮（"紫钮"）的透明度
 * @param primaryScale 紫钮的缩放（**直接取面板的盘径 ÷ 按钮径**，见 [buttonState]）
 * @param primaryTx 紫钮的横向位移（px）—— 抵消面板的飞行位移，让两个圆重合
 * @param primaryTy 紫钮的纵向位移（px）—— 抵消飞行，另含落位时那一下下沉过冲
 * ★ 深色钮**不接受外部状态**：它自己跑一条原厂弹簧（见函数体里的 `darkProgress`）——
 *   原厂它就是独立的一条时间线（`PeopleActivityFab.p()/j()`）。
 */
@Composable
private fun ControlCenterButton(
    expanded: Boolean,
    primaryAlpha: Float,
    primaryScale: Float,
    primaryTx: Float,
    primaryTy: Float,
    /** 面板的进度 —— 只用来**判断深色钮何时该出现**（见函数体里的 `darkTarget`） */
    panelProgress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accents = navAccents()
    // ★ 2.7.0：圆钮的交互源提出来（两颗钮共用）并递给箭头 —— 按到整个圆钮任意处，
    //   箭头都跟着变化（旧行为是图标自检：必须精准按在箭头上）。
    val ccSource = remember { MutableInteractionSource() }
    val batchRunning = TransferStore.batchRunning
    val thumbTotal = ThumbStore.batchTotal
    val thumbTotal2 = thumbTotal
    // ★ 不要把"已暂停"当成"有进度"：点开始传输的那一瞬，缩略图管线先被暂停、
    //   batchRunning 还没置位，此时 batchDone == batchTotal（例如 10/10）会被算成
    //   100% —— 于是满圈闪一下再掉回真实进度（用户实测）。只有**真的在拉**才算数。
    val downloading = batchRunning
    // ★ 2.6：自动传输开启时，小图与大预览合并成"文件周期"进度（与面板同一数据源）——
    //   顶栏环若还按旧的 batchDone/batchTotal 走，会和面板的周期数字打架。
    val autoOn = ThumbStore.autoTotal > 0
    val thumbsFetching = if (autoOn) {
        ThumbStore.autoDone < ThumbStore.autoTotal
    } else {
        thumbTotal2 > 0 && ThumbStore.batchDone < thumbTotal2
    }
    val active = downloading || thumbsFetching

    val fraction = when {
        downloading -> {
            val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
            TransferStore.batchDoneBytes.coerceIn(0, total) / total.toFloat()
        }
        thumbsFetching && autoOn -> ThumbStore.autoDone.toFloat() / ThumbStore.autoTotal.coerceAtLeast(1)
        thumbsFetching -> ThumbStore.batchDone.toFloat() / thumbTotal2
        else -> 0f
    }

    // 箭头翻转：spatialFast（小控件的形变）
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.fastOut(),
        label = "chevron",
    )
    // 进度环的数值也用弹簧追：下载进度是每秒跳一次的数字，直接喂进去环会一格一格蹦；
    // 让它追着目标值走，看起来是**平滑地爬**上去
    val shownFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.spatialDefault(),
        label = "ringFraction",
    )

    // ★★★ 深色钮的时机：**面板落位之后才开始出现**（用户："出现得太快了，像闪现"）。
    //
    //   原厂（`DoubleCallButtonContainer.p()` 的 AnimatorListener）：
    //   ```java
    //   onAnimationStart: if (z10) fab.setVisible(false);   // 展开：面板一动，钮先消失
    //   onAnimationEnd:   if (!z10) fab.setVisible(true);   // 收起：面板停稳，钮才回来
    //   ```
    //   也就是说**深色钮完全不在面板的动画区间里**，它自己那条
    //   `Spring(ζ=0.95, 0.35s)` 是在**面板已经就位**之后才跑的。
    //
    //   ★ 我上一版让它跟 `expanded` 同时起飞（`animateFloatAsState(target=1f)` 立刻开跑），
    //     于是它和面板**同时**在动 —— 面板还没落位，深色钮已经淡入完了，
    //     看起来就是"一闪就出来了"。
    //
    //   现在把它的起点**绑在面板进度上**：`expanded` 之后先等 `progress` 长过
    //   [DARK_APPEAR_AT]，再开始那 0.35s 的弹簧。这样顺序与原厂一致：
    //   面板飞出去 → 落位 → 深色钮冒出来。
    val darkTarget = if (expanded) 1f else 0f
    val darkProgress by animateFloatAsState(
        targetValue = darkTarget,
        animationSpec = if (expanded) {
            Motion.springEasing(DARK_ZETA_IN, DARK_RESPONSE_IN, DARK_IN_MS)
        } else {
            Motion.springEasing(DARK_ZETA_OUT, DARK_RESPONSE_OUT, DARK_OUT_MS)
        },
        label = "darkButton",
    )

    Box(
        modifier.size(CcButtonSize),
        contentAlignment = Alignment.Center,
    ) {
        // ---- ① 强调色圆钮（展开时"飞走变成面板"的那一个）----
        //   ★★ 它**就是面板那只盘**：尺寸取盘径、位移抵消面板的飞行 ——
        //      两个圆逐帧重合，所以交接处看到的是"同一个圆"，不是"大圈换小圆"。
        //      位移里那一项 `dip` 是落位时多沉一下再弹回的过冲（用户点名要的
        //      "下落再归位"），峰值落在面板已经淡完之后（见 [dipEnvelope]）。
        if (primaryAlpha > 0.001f) {
            Box(
                Modifier
                    .size(CcButtonSize)
                    .graphicsLayer {
                        alpha = primaryAlpha
                        scaleX = primaryScale
                        scaleY = primaryScale
                        // 位移是**屏幕像素**（graphicsLayer 里不随缩放走），
                        // 与面板那边 `flyX/flyY` 用的是同一个单位，所以能精确抵消。
                        translationX = primaryTx
                        translationY = primaryTy
                    },
                contentAlignment = Alignment.Center,
            ) {
                // ★ 不用 FilledIconButton（用户定版：删掉小球的按压水波纹）——
                //   自己排：阴影 → 圆底（primaryContainer，"主要动作"那一档）→
                //   与面板同源的描边 → 无 indication 的点击。
                //   药丸有阴影、这个按钮也得有，否则一个浮一个不浮（用户反馈过）。
                Box(
                    Modifier
                        .size(CcButtonSize)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accents.buttonFill)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(
                            interactionSource = ccSource,
                            indication = null,
                            onClick = onClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // ★ 图标换成山形三角（keyboard_arrow_up，用户定版"类似 ^ 的三角"）：
                    //   收起态朝上（= "点我展开"），展开时整枚随 `rotation` 转 180° 朝下
                    //   —— "悬浮窗展开时变成倒着的"。
                    MsIcon(
                        icon = MsIcon.NAV_CARET,
                        contentDescription = if (expanded) "收起控制中心" else "展开控制中心",
                        modifier = Modifier.size(26.dp).rotate(rotation),
                        size = 26.dp,
                        interactionSource = ccSource,
                        tint = accents.buttonOn,
                    )
                }
                // 进度环画在按钮**之上**、直径与按钮一致 → 环正好嵌在圆的内部边缘
                // （用户定版："在圆形的内部边缘嵌入一个进度条"）。
                // trackColor 透明：叠在实心按钮上再画一圈灰底会显脏。
                if (active) {
                    CircularProgressIndicator(
                        progress = { shownFraction },
                        modifier = Modifier.size(CcButtonSize),
                        strokeWidth = 4.dp,
                        color = accents.ring,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }

        // ---- ② 原位深色钮（由虚到实、由小到大）----
        //   容器色用 **surfaceContainerHighest**（面板那层灰）而不是强调色 ——
        //   参考里原位冒出来的是个"黑色指示图标"，与刚飞走那个绿钮**不是同一个东西**。
        //   ★ 缩放范围 **0.6 → 1.0**、透明度 0 → 1，两条都直接取 `darkProgress`
        //     （原厂 `p()` 就是 `ALPHA 0→1` 配 `SCALE 0.6→1`）。
        val darkAlpha = darkProgress.coerceIn(0f, 1f)
        val darkScale = DARK_SCALE_FROM + (1f - DARK_SCALE_FROM) * darkProgress
        if (darkAlpha > 0.001f) {
            Box(
                Modifier
                    .size(CcButtonSize)
                    .graphicsLayer {
                        alpha = darkAlpha
                        scaleX = darkScale
                        scaleY = darkScale
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 同样去水波纹（用户定版）：Surface(onClick) 换成无 indication 的 Box，
                // 白边照旧与面板同源（悬浮三件套共用描边）。
                Box(
                    Modifier
                        .size(CcButtonSize)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable(
                            interactionSource = ccSource,
                            indication = null,
                            onClick = onClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MsIcon(
                        icon = MsIcon.NAV_CARET,
                        contentDescription = "收起控制中心",
                        // 展开态=朝下：这颗只在展开时可见，转 180° 与紫钮同拍
                        modifier = Modifier.size(26.dp).rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 26.dp,
                        interactionSource = ccSource,
                    )
                }
                // ★ 用户定版："悬浮窗展开之后下面的小球也应该显示下载进度条，
                //   和外露的一样" —— 进度环原来只画在紫钮上，展开后被深色钮顶掉；
                //   这里给深色钮补上同一只环（同一条 [shownFraction] 弹簧，
                //   收起/展开切换时环的读数是连续的）。
                if (active) {
                    CircularProgressIndicator(
                        progress = { shownFraction },
                        modifier = Modifier.size(CcButtonSize),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

/**
 * 控制中心面板（三段），覆盖层形式：贴右下、悬在圆形按钮上方。
 *
 * ★ **不要改回 `Popup`**：Popup 的窗口尺寸等于内容尺寸，带阴影的圆角卡片放进去
 * 阴影会被窗口边界裁掉 —— 圆角处露出硬边灰块、角也不平滑（用户反馈
 * "圆角仍然不平滑而且还有遮罩"）。画在应用自己的组合里就没有窗口裁剪，
 * 阴影与圆角都正常，还能顺带用返回键收起。
 *
 * 点面板外任意处收起：用一层**透明**的全屏点击层（不给 indication，
 * 否则整屏会闪一下灰罩）。
 *
 * ## 展开动画：按钮**自己飞上去**变成面板（参考视频逐帧分析）
 *
 * 用户的原话："原版动画是**点击绿色按钮 - 绿色按钮向上飞走并变形为面板 - 原来绿色按钮的
 * 位置冒出一个新的黑色图标**"。逐帧验过，确实如此：
 *
 * ```
 * 帧62-65  绿盘一边长大一边上移（168px→210→278，y 从 2390 升到 2180）
 * 帧64-65  原位采样从 (67,191,37) 掉到 (3,3,3)   → 按钮**飞走了**
 * 帧66-78  绿盘继续长到面板大小（678×134@y2198）  → 变成面板
 * 帧79-95  原位采样 46→61→116→152→189→207       → 新图标**淡入**
 * ```
 *
 * 所以是**容器变换**（container transform）的三层叠加：
 * ```
 * ① 圆钮飞上去   从按钮的矩形 → 平移到面板位置（位移）
 * ② 沿途长大     尺寸从 56dp 长到面板大小（形状 + 圆角）
 * ③ 原位冒新图标 按钮淡出 → 空档 → 新的深色图标淡入（见 [buttonPresence]）
 * ```
 *
 * ★★ **上一版把这个机制做错了**：面板是在按钮**上方原地**冒出来、按钮完全没动。
 *   差别就在"位移"这一层 —— 少了它，看着是"凭空多了一块"，而不是"按钮自己飞过去"。
 *   现在整块的位移由 [flyX]/[flyY] 承担（见 [PanelSurface]），尺寸由 [DiscShape] 承担。
 *
 * ### 曲线：用户定版"由快到慢"（见 [Motion.FastOutEasing]）
 *
 * 帧差法逐帧量参考：从按钮大小长到满尺寸历时 **24 帧 / 267ms**，
 * 第 29 帧冲到 **1.013×（过冲 1.3%）**，第 45 帧（500ms）稳定。
 *
 * ★ 但曲线形状不是照抄那个过冲量 —— 用户定版是"**所有的动画要有明显的由快到慢的
 *   趋势**"，所以 [overshootEasing] 用 `easeOutCubic`（起步最猛、收尾停住）
 *   加一条只在末尾鼓起的过冲尾巴，而不是阻尼弹簧那个"慢起→中段快→慢收"的 S 形。
 *
 * ★ 上一版的另一个错在**用法**：当时写 `scaleIn(initialScale = 0.86f)` ——
 *   只从 86% 微放大到 100%，所以看着"平"。参考是从按钮那么小真正长出来的。
 *   现在由 [FloatingNav] 里那条**唯一的 progress** 驱动全部量，彼此必然同步。
 *
 * ### 进出不对称（MD3E 的规矩）
 *
 * 规格由 [FloatingNav] 统一给（进 defaultSpatial / 出 fastSpatial），本函数只消费。
 */
@Composable
private fun ControlCenterPanel(
    /** 整条动画的唯一驱动量：0 = 完全收起（一个圆钮），1 = 完全展开 */
    progress: Float,
    /** 目标状态：收起与展开各走一条原厂弹簧（进出参数对调，见 [FloatingNav]） */
    expanded: Boolean,
    /**
     * **只测量、不显示**：首次展开时 progress 被门控在 0，但面板几何（尺寸/锚点）
     * 必须先量出来动画才有起点 —— 这时把面板按**最终位置**挂出来排一遍版
     * （alpha 压成 0，画不出任何东西），量完 [FloatingNav] 就放行动画。
     */
    measureOnly: Boolean,
    /** 面板起点（按钮那一角）相对终点的平移量，见 [FloatingNav] */
    dx: Float,
    dy: Float,
    /** 面板底边距屏幕底部的距离（由调用方按导航栏真实几何算好） */
    bottomPadding: Dp,
    /** 面板实测尺寸的回传 —— 紫钮要用它算盘径（见 [buttonState]） */
    onSizeMeasured: (IntSize) -> Unit,
    /** 面板**内容区中心**的窗口坐标回传 —— 位移的终点（见 [buttonState] 的 dx/dy） */
    onAnchorMeasured: (Offset) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: (String) -> Unit,
) {

    Box(Modifier.fillMaxSize()) {
        // 收起到底之后整块不画：否则缩在右下角的那个小块会**继续吃触摸**，
        // 正好盖在圆钮上 —— 表现为"点按钮没反应，第二下才行"。
        // 判据是 progress 是否真的归零（而不是"目标状态是收起"）：收起途中仍要画。
        // ★ `|| measureOnly`：首次展开时 progress 被钉在 0，但面板必须先挂出来
        //   完成一次"隐形测量"（见 [measureOnly]），动画才放行。
        if (progress > 0.001f || measureOnly) {
            // 点面板外任意处收起（透明层，不给 indication 否则整屏闪灰罩）。
            // ★ 测量期**不挂**这层：它铺满全屏，会把点击全吃掉（球都点不了）。
            if (progress > 0.001f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
            // ★★★ 面板退场：**一路缩小、最后与紫钮交叉淡出**（用户两次点名这里）
            //
            // 用户："窗口消失的速度太快了，应该是**窗口缩小慢慢渐变为图标然后落位
            // 回到该有的位置**"、"关闭面板是 黑色按钮由大到小由实到虚消失、
            // 面板变为绿色按钮飞回，**要做好衔接**"。
            //
            // 我上一版让它在前 51ms 内淡掉（对齐原版 f174-178 的 55ms），但那笔账
            // 不适用：原版淡出时盘已经缩得只剩一点，而我们 624px 的大块边界还在
            // 才刚开始缩 —— 读起来就是"窗口唰地没了"。
            //
            // 现在共用 [panelExitEnvelope]（**与深色钮同一条**，两者本就是同一个
            // 容器的两种形态）：
            // ```
            // 0..133ms    全不透明地缩小              "窗口缩小"
            // 133..215ms  淡出 ‖ 紫钮淡入（对称交叉）  "渐变为图标"
            // 215..300ms  只剩紫钮，长大到 1.083 落位  "落位"
            // ```
            // 到淡出终点时盘径 188px vs 按钮 168px，**位置与大小都几乎重合**，
            // 所以交叉淡出看起来是"同一个圆在换颜色" —— 这就是"衔接"。
            val panelAlive = when {
                // 测量期：alpha 压到 0，但**必须保持组合**（测量就是靠它排版完成的），
                // 所以这里不做"alpha 归零就不画"的短路
                measureOnly -> 0f
                expanded -> 1f
                else -> panelExitEnvelope(progress)
            }
            PanelSurface(
                progress = progress,
                panelAlpha = panelAlive,
                dx = dx,
                dy = dy,
                modifier = Modifier.align(Alignment.BottomEnd),
                bottomPadding = bottomPadding,
                onSizeMeasured = onSizeMeasured,
                onAnchorMeasured = onAnchorMeasured,
                onOpenSettings = onOpenSettings,
                onSwitchDevice = onSwitchDevice,
            )
        }
    }
}

/**
 * 动画在某个 progress 处的**形状与位置** —— ★ 算法逐行照搬原厂。
 *
 * ## 原厂实现（`DoubleCallButtonContainer`，jadx 反编译逐行读过）
 *
 * 原厂**没有"按钮"和"面板"两个 view**：是**同一个容器**在变。它的 `onDraw` 里
 * `canvas.clipPath(圆角矩形)`，而那个矩形在 `onAnimationUpdate` 里逐帧算：
 *
 * ```java
 * // setup：记下两端的尺寸与中心距
 * f6612r = fabRect.width();     f6613s = panelRect.width();      // 起/止 宽
 * f6614w = fabRect.height();    f6615x = panelRect.height();     // 起/止 高
 * f6608n = 0;                   f6609o = |fabCx - panelCx|;      // 起/止 位移 x
 * f6610p = 0;                   f6611q = |fabCy - panelCy|;      // 起/止 位移 y
 *
 * // update(f)：同一个 fraction 插值三个量
 * int w = f6612r + (f6613s - f6612r) * f;
 * int h = f6614w + (f6615x - f6614w) * f;
 * int halfH = h / 2;
 * rect.set(cx - w/2, cy - halfH, cx + w/2, cy + halfH);   // ★ 以容器**中心**为中心
 * setRadii(halfH);                                        // ★ 圆角 = 半高
 * path.addRoundRect(rect, radii);
 * setTranslationX(f6608n + (f6609o - f6608n) * f);
 * setTranslationY(-f6610p - (f6611q - f6610p) * f);
 * animation_mask.setAlpha(f);                              // 内容淡入
 * ```
 *
 * ★★ 两个"啊哈"点，是我之前那套自创几何**完全没抓到**的：
 *
 * 1. **圆角恒等于半高**（`setRadii(h/2)`）—— 于是形状**自动**从圆（w=h）长成
 *    胶囊（w>h）再长成圆角矩形（w>>h），**一行插值搞定**。
 *    我却分了两段（先宽高同步长、再单独撑宽）还配了一堆接缝处理 —— 全是不必要的。
 * 2. **形状在容器里居中**，靠 `translation` 把它挪到按钮位置 —— 而不是把形状锚在
 *    某一个角上。所以位移量是**中心到中心的距离**，不是边到边的距离。
 *
 * 另外**没有独立的"过冲缩放"**：`f` 冲过 1 时，`w`/`h` 的插值自然就算过头了，
 * 几何量自己会超出去。我那个 `overshootScale` 纯属多余。
 */
private class DiscGeometry(val w: Float, val h: Float, val corner: Float)

// ===== ★★ 原厂弹簧参数（逆向自 MIUI 拨号盘，见 [FloatingNav] 的逆向记录） =====
//
// 原厂在进出之间把两组参数**对调**（`DialerViewController.a1()`）：
// ```
//          主曲线（尺寸）                位移                      透明度
// 展开     ζ=0.8  response=0.42s        ζ=0.97 response=0.25s     50ms 线性
// 收起     ζ=0.97 response=0.25s        ζ=0.8  response=0.42s     300ms 正弦
// ```
// 我们只有一条 progress 同时驱动尺寸与位移，所以**取主曲线那一组**。
// （原厂给位移配 ζ=0.97 = 几乎不过冲，说明弹的感觉只该留在尺寸上。）

// ---- 深色钮（原位那个"黑色指示图标"）自己的弹簧 ----
// 原厂它是**独立的一条**（`PeopleActivityFab.p()/j()`），不是从面板进度派生的：
//   出现 Spring(ζ=0.95, resp=0.35s)   消失 Spring(ζ=1.0, resp=0.20s)
// 我先前把它挂在面板的 progress 上（用 DARK_APPEAR_AT 切一刀），
// 那是**多出来的耦合** —— 用户说"实现不够彻底"就包括这类。
/** 深色钮出现：ζ=0.95、0.35s（轻微过冲，与面板同向）。 */
private const val DARK_ZETA_IN = 0.95f
private const val DARK_RESPONSE_IN = 0.35f
/** 深色钮消失：ζ=1.0（临界阻尼，不过冲）、0.20s —— 原厂收得比出现快。 */
private const val DARK_ZETA_OUT = 1.0f
private const val DARK_RESPONSE_OUT = 0.20f
private const val DARK_IN_MS = 350
private const val DARK_OUT_MS = 200

/*
 * ★ 这里原本还有两条"展开/收起弹簧"常量（ζ=0.8/0.42s 与 ζ=0.97/0.25s），
 *   逆向读完原厂之后**删掉了** —— 那对参数属于 FAB 图标自身的显隐
 *   （见 [DARK_ZETA_IN]），不是面板形变。面板形变是**固定 350ms + easeOutCubic**
 *   且两个方向共用一台 animator（`DoubleCallButtonContainer.p()`）。
 *   用户说"实现不够彻底"，指的就是我把两套系统的参数混着用了。
 */

/*
 * ★ 这里原本有 overshootNorm / overshootScale 两个函数，负责"面板整块等比放大一下"。
 *   逆向原厂之后**两个都删了**：原厂的过冲完全不在这条路径上 ——
 *   它写在 [discGeometry] 的宽高插值里（`f` 冲到 1.015 时 w/h 自然算到 101.5%）。
 *   我那个 scale 等于把同一份过冲算了两遍，而且引入了"位移不动、只有尺寸在弹"
 *   这种原厂没有的解耦。用户说"实现得不够彻底"指的正是这类多余层。
 */


/**
 * 按原厂算法算形状：**宽高各自线性插值，圆角取半高**。
 *
 * @param f     动画进度（可以 <0 或 >1 —— 原厂弹簧的过冲会透进来，那正是它要的）
 * @param fabW  起点宽（圆钮直径）
 * @param fabH  起点高（圆钮直径）
 * @param panelW/panelH 终点尺寸（面板实测尺寸）
 */
private fun discGeometry(
    f: Float,
    fabW: Float,
    fabH: Float,
    panelW: Float,
    panelH: Float,
    finalCorner: Float,
): DiscGeometry {
    // ★ 宽高**各按 f 线性插值** —— 这就是原厂的做法（`f6612r + (f6613s - f6612r) * f`）。
    //
    //   我一度以为"我们的面板是 1.84:1 宽扁形，线性插值会中途变成扁胶囊"，
    //   于是给高度配了 `f²` 想让它追平 —— 实测那是**反效果**：
    //   宽高中途比例从 1.56 恶化到 2.68，比线性更扁。
    //   线性插值的比例演进是 1.00 → 1.34 → 1.56 → 1.72 → 1.84（圆 → 面板），
    //   本来就是一条自然的过渡曲线，**不需要额外补偿**。
    val w = (fabW + (panelW - fabW) * f).coerceAtLeast(1f)
    val h = (fabH + (panelH - fabH) * f).coerceAtLeast(1f)
    // ★★ **圆角是"半高 → 固定值"的插值**，不是恒为半高。
    //
    //   我上一版照抄了原厂 `setRadii(h/2)` 就直接用 —— 那是**形变中途**的写法：
    //   原厂那个容器在形状还小的时候靠"圆角=半高"保证是圆/胶囊；但**最终态**
    //   面板是**固定的 34.7dp 圆角**（实测：参考帧 1053×1048 的面板，
    //   左上角轮廓在 y+96px 处归零 ⟹ 半径 96px ÷ 2.767 = 34.7dp）。
    //   恒取半高会让终态变成一个巨大的胶囊 —— 用户截图里"圆角成啥样了"指的就是它。
    //
    //   所以用一个和形状同步的量把两者接起来：形状高度长到面板高时，
    //   圆角也正好从"半高"过渡到 [finalCorner]。两端都连续。
    val halfH = h / 2f
    val k = (h / panelH.coerceAtLeast(1f)).coerceIn(0f, 1f)   // 0=还是按钮, 1=已到面板高
    val corner = halfH + (finalCorner - halfH) * k
    return DiscGeometry(w, h, corner.coerceIn(0f, halfH))
}

/**
 * [DiscGeometry] 对应的裁剪形状 —— **以容器中心为中心**的圆角矩形（原厂同款）。
 *
 * ★ 我上一版把它锚在右下角（`left = width - g.w`），那是按"面板从右下角长出来"
 *   这个**我以为的**机制写的。原厂是居中的：形状在容器里对称生长，
 *   靠 `translation` 决定它现在在哪 —— 两件事分得干干净净。
 */
private class DiscShape(
    private val f: Float,
    private val fabW: Float,
    private val fabH: Float,
    private val finalCorner: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        // ★★ `size` **就是内容区尺寸**：`.padding()` 在 `.fillMaxWidth()` 之前，
        //   所以 `.clip(shape)` 这一层拿到的是**已经扣掉内边距**的尺寸，与
        //   `panelW/panelH`（同样量自 `onSizeChanged`）完全一致。
        //   我上一版在这里又减了一次 padding（padL/padB），等于**减了两遍** ——
        //   面板因此偏左偏上、形状也小了一圈。
        val g = discGeometry(f, fabW, fabH, size.width, size.height, finalCorner)
        // ★ **居中于内容区** —— 四边同时外扩，这是原厂 `onAnimationUpdate` 的效果
        //   （它的 rect 就是 `cx ± w/2, cy ± h/2`）。
        //   `size` 是内容区尺寸（`.padding()` 在 `.clip()` 之前），p=1 时形状与内容区重合。
        val cx = size.width / 2f
        val cy = size.height / 2f
        return Outline.Rounded(
            RoundRect(
                left = cx - g.w / 2f,
                top = cy - g.h / 2f,
                right = cx + g.w / 2f,
                bottom = cy + g.h / 2f,
                topLeftCornerRadius = CornerRadius(g.corner, g.corner),
                topRightCornerRadius = CornerRadius(g.corner, g.corner),
                bottomRightCornerRadius = CornerRadius(g.corner, g.corner),
                bottomLeftCornerRadius = CornerRadius(g.corner, g.corner),
            )
        )
    }
}

/**
 * 面板本体与它的展开动画（由 [ControlCenterPanel] 在动画进行时挂载）。
 *
 * @param onSizeMeasured 面板**实测尺寸**的回传 —— 紫钮要用它算盘径（见 [buttonState]）。
 *   面板高度由内容决定，布局前无从得知，所以只能量完再报上去。
 */
@Composable
private fun PanelSurface(
    progress: Float,
    /** 面板的存在感：收起时提前退场（见 [ControlCenterPanel]），展开时恒为 1 */
    panelAlpha: Float,
    dx: Float,
    dy: Float,
    modifier: Modifier,
    bottomPadding: Dp,
    onSizeMeasured: (IntSize) -> Unit,
    onAnchorMeasured: (Offset) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchDevice: (String) -> Unit,
) {
    val panelColor = MaterialTheme.colorScheme.surfaceContainerHighest
    // ★ 起步闪色必须与**圆钮同色**（"按钮飞上来变面板"的错觉全靠它），所以取同一张角色表
    val accent = navAccents().buttonFill
    val density = LocalDensity.current
    val buttonPx = with(density) { CcButtonSize.toPx() }

    // 面板的**实测尺寸**（内容排完版才知道，见 [PanelSurface] 的参数说明）。
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val panelW = panelSize.width.toFloat().coerceAtLeast(1f)
    val panelH = panelSize.height.toFloat().coerceAtLeast(1f)

    // ★★ 形状随 progress 变（同一个 f 同时决定宽、高、圆角），所以每次新建。
    val cornerPx = with(density) { CcPanelCorner.toPx() }
    val shape = remember(progress, buttonPx, panelW, panelH, cornerPx) {
        DiscShape(progress, buttonPx, buttonPx, cornerPx)
    }

    // ★★ **位移**：按原厂算法，是**中心到中心的向量** × progress。
    //
    //   原厂：`setTranslationX(fabDx + (0 - fabDx) * f)` —— 起点是按钮中心、
    //   终点是面板中心，乘同一个 f。我们布局上把面板放在终点位置，
    //   所以位移就是 `-Δ × f`（Δ = 面板中心 − 按钮中心，由调用方算好）。
    //
    //   ★ 不再有独立的"过冲缩放"：`progress` 冲过 1 时，
    //     [discGeometry] 的宽高插值**自然**就超了，几何量自己会鼓出去 ——
    //     这正是原厂的做法（它也没有任何额外的 scale）。
    Box(
        modifier
            .padding(start = CcPanelMargin, end = CcPanelMargin, bottom = bottomPadding)
            .fillMaxWidth()
            .onSizeChanged {
                panelSize = it
                onSizeMeasured(it)
            }
            // 上报内容区中心的真实窗口坐标（`boundsInWindow` 拿到的正是内容区）
            .onGloballyPositioned { c ->
                val r = c.boundsInWindow()
                onAnchorMeasured(Offset(r.center.x, r.center.y))
            }
            .graphicsLayer {
                // ★ 位移与形状是**同一个 f** 驱动的两件事，原厂分得干净：
                //   形状负责"多大"（[discGeometry]），位移负责"在哪"。
                //
                //   ★★ 方向：面板盒子**布局在终点**，所以 `progress=1` 时位移必须为 0，
                //      `progress=0` 时位移正好等于 "按钮中心 − 面板中心"（= [dx]/[dy]）。
                //      写成 `dx * (1 - progress)` 即满足两端 —— 原厂是
                //      `f6608n + (f6609o - f6608n) * f`、起点 f6608n=Δ、终点 f6609o=0，
                //      代数上完全一样。
                //      ★ 我上一版写成 `-dx * progress`，把符号与进度都弄反了：
                //        progress=1 时位移是 +|dx|（把面板推出屏幕右缘）—— 就是用户看到的错位。
                //   ★ **不夹 progress**：原厂弹簧的过冲会透进来，位移也跟着略过一点，
                //     与尺寸同步（同一条曲线驱动，正是原厂的做法）。
                translationX = dx * (1f - progress)
                translationY = dy * (1f - progress)
                alpha = panelAlpha
            }
            // ★ 顺序要紧：先 clip（形状）再刷色再描边，三者共用同一个 shape。
            //   clip 同时管住**子节点**——动画期间内容是长在盘里的。
            .clip(shape)
            .background(panelColor)
            .then(
                if (progress < 0.18f) {
                    // 强调色：**很快**退掉（原厂是 50ms 线性淡出，见 [PRIMARY_FADE_OUT]）。
                    // ★ 起点是 100% 强调色 —— 那正是圆钮的容器色，所以"按钮飞上来"的
                    //   整个起步阶段这一块与按钮**同色**，看起来就是按钮自己变成了它。
                    val a = (progress / 0.18f).coerceIn(0f, 1f)
                    Modifier.background(accent.copy(alpha = 1f - (1f - (1f - a) * (1f - a))))
                } else Modifier
            )
            // 不投影就得给个边界：后面那几张信息卡是同一个灰阶，没有描边会糊在一起。
            // 描边画在背景之上、子节点之下，所以它跟着形状一起"化"，盘阶段没有硬边。
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            // 内容变化（进度段出现/消失、设备增删）时尺寸也跟着弹簧走
            .animateContentSize(Motion.spatialDefault()),
    ) {
        // ★★ **内容整体缩放 + 淡入** —— 原厂是 `animation_mask.setAlpha(f)`，
        //   也就是**内容遮罩的透明度直接等于进度**，一个量搞定。
        //
        //   原厂那个 `animation_mask` 是个盖在内容上的遮罩层，`setAlpha(f)` 让内容
        //   随生长一起显现；它**不缩放内容**（内容一直是最终尺寸排好的，被外层
        //   `clipPath` 裁着）。我们这里内容比早期的盘大得多，纯裁剪会只露出左上角
        //   一小块，所以额外按 `盘宽 ÷ 面板宽` 缩一下 —— 视觉上等价于原厂那个
        //   "内容跟着盘子一起长大"，只是我们用缩放实现（Compose 里更省事）。
        val g = discGeometry(progress.coerceIn(0f, 1f), buttonPx, buttonPx, panelW, panelH, cornerPx)
        val contentScale = (g.w / panelW).coerceAtLeast(0.02f)
        // 内容透明度：原厂 `alpha = f` 是**线性**的，不夹上界（过冲时内容也会更实一点）。
        val contentAlpha = progress.coerceIn(0f, 1f)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 1f)   // 与盘同一个锚点（右下）
                    scaleX = contentScale
                    scaleY = contentScale
                    alpha = contentAlpha
                    // ★★ **必须离屏合成**，否则文字会"颤抖"（用户实测反馈）。
                    //
                    //   原因：不加这一句时，Android 会按**当前缩放比**去栅格化文字 ——
                    //   缩放比每帧都在变，字形的亚像素落点就每帧都不同，于是笔画边缘
                    //   一直在跳，看起来就是字在抖。
                    //   `Offscreen` 让内容**按自然尺寸（最终态尺寸）栅格化进一张离屏缓冲**，
                    //   之后缩放只是把这张位图当纹理采样 —— 字形只在固定尺寸上算一次，
                    //   亚像素落点不再变化，抖动消失。
                    //
                    //   ★ 代价是动画期间文字会略微软一点点（位图被缩小采样），
                    //     但我们的缩放是**从 1.0 往下缩**（内容按最终尺寸布局、再缩到盘大小），
                    //     所以是降采样，不会糊；反而比逐帧重栅格化更干净。
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            // ---- ① 进度段：仅在进行中显示 ----
            if (TransferStore.batchRunning || ThumbStore.batchTotal > 0 || ThumbStore.autoTotal > 0) {
                ProgressSection()
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            // ---- ② 设置 ----
            PanelRow(
                icon = MsIcon.SETTINGS_GEAR,
                title = "设置",
                subtitle = null,
                onClick = onOpenSettings,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            // ---- ③ 已绑定设备（切换） ----
            DeviceSection(onSwitchDevice = onSwitchDevice)
        }
    }
}
/** ① 下载 + 缩略图进度（含速度/剩余/已用，沿用用户定版的三列布局） */
@Composable
private fun ProgressSection() {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (TransferStore.batchRunning) {
            val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
            val done = TransferStore.batchDoneBytes.coerceIn(0, total)
            val pct = (done * 100 / total).toInt()
            val elapsedMs = (System.currentTimeMillis() - TransferStore.batchStartedAt).coerceAtLeast(1)
            val speed = TransferStore.batchSpeedBps
            val etaSec = if (speed > 0) (total - done) / speed else -1
            // 正在传的那一批钉在某台设备上，可能**不是**用户此刻看的那台（队列按设备隔离）：
            // 不是当前这台时把设备名写出来，否则用户会以为这个进度属于界面上这台相机
            val batchGuid = TransferStore.runningQueue?.guid
            if (batchGuid != null && batchGuid != ConnectionCenter.displayGuid()?.lowercase()) {
                Text(
                    ConnectionCenter.labelOf(batchGuid),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ★ 颜色必须显式给 onSurface（用户实测："悬浮窗中的字颜色没有自适应"——
            //   不写颜色的 Text 在面板上落成了深色，深灰底上几乎看不见）。
            //   面板底是 surfaceContainerHighest，内容色就是 onSurface，一处不漏地写明。
            Text(
                "下载 $pct%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SlimProgress(fraction = done / total.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelMetric("已用", formatDuration(elapsedMs), Modifier.weight(1f))
                PanelMetric(
                    "剩余",
                    if (etaSec >= 0) formatDuration(etaSec * 1000) else "—",
                    Modifier.weight(1f),
                )
                PanelMetric("速度", formatSpeed(speed), Modifier.weight(1.2f))
            }
        } else {
            Text(
                "下载 无进行中的传输",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ★ 2.6（用户定版）：自动传输开启时，小图与大预览是**同一个"文件周期"的两半**
        //   （并发拉取、两者都完成才进入下一个文件），分成两行只会两个数字各跳各的 ——
        //   合并成一行"缩略/预览图"，进度取周期数（autoDone/autoTotal）。
        //   开关没开时 autoTotal 恒为 0，走下面原来的"缩略图"行，标题不变。
        if (ThumbStore.autoTotal > 0) {
            val aDone = ThumbStore.autoDone
            val aTotal = ThumbStore.autoTotal
            Text(
                if (aDone >= aTotal) "缩略/预览图传输完成 $aDone/$aTotal"
                else "缩略/预览图 $aDone/$aTotal" + if (ThumbStore.paused) "（已暂停）" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SlimProgress(fraction = if (aTotal > 0) aDone.toFloat() / aTotal else 0f)
        } else if (ThumbStore.batchTotal > 0) {
            val tDone = ThumbStore.batchDone
            val tTotal = ThumbStore.batchTotal
            Text(
                if (tDone >= tTotal) "缩略图加载完成 $tDone/$tTotal"
                else "缩略图 $tDone/$tTotal" + if (ThumbStore.paused) "（已暂停）" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SlimProgress(fraction = if (tTotal > 0) tDone.toFloat() / tTotal else 0f)
        }
    }
}

/**
 * ③ 已绑定设备：点一行切到那台。
 *
 * ★ **高亮 = "当前选中的设备"，不是"已连接的设备"**（用户定版）。用户的原话是
 *   "连接失败后高亮消失，无法辨别正在哪个设备的主界面" —— 所以判定用
 *   [ConnectionCenter.displayGuid]（连上了是它、没连上是本次选中的目标），
 *   它**跨连接失败保留**。若按"已连接"来高亮，只要连不上，面板里就没有一行是亮的。
 *
 * ★ 点已连接的那行也照常走 [onSwitchDevice]（回它的主界面），不再拐去设置页 ——
 *   用户定版："无论当前选中设备是否连接上都应该立刻切换"。副标题区分状态，
 *   点了不会真的重连（ConnectionCenter.switchTo 见到"已连着它"直接返回）。
 */
@Composable
private fun DeviceSection(onSwitchDevice: (String) -> Unit) {
    val cameras = PairingStore.all()
    // 选中的那台：连上了就是连着的，没连上就是本次的目标（见上）
    val selectedGuid = ConnectionCenter.displayGuid()
    val connectedGuid = ConnectionCenter.camera?.guidHex
    val busy = ConnectionCenter.connecting
    Text(
        "已绑定设备",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // 24 = 16(常规内边距) + 8(列表行的内缩)：标题要和下面那些行的图标对齐，
        // 否则标题比行更靠左，看着是排版错位
        modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 2.dp),
    )
    if (cameras.isEmpty()) {
        Text(
            "尚未绑定任何相机",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    cameras.forEach { cam ->
        val selected = cam.peerDeviceId.equals(selectedGuid, ignoreCase = true)
        val connected = cam.peerDeviceId.equals(connectedGuid, ignoreCase = true)
        // ★ 型号/序列号走 [PairingStore.PairedCamera.displayModel/displaySerial] 推导：
        //   老记录里 peerModel 可能是空的、peerName 可能是"ILCE-6300 SN:05186914"
        //   组合串 —— 直接读原字段会出现"组合串再拼一次 SN"或漏掉序列号。
        //   这一行的组合串正是它**唯一的正式出场场合**：辨别设备（用户定版），
        //   同型号的两台机身在这里必须长得不一样。
        val label = cam.displayModel.ifBlank { "未知相机" }
        val serial = cam.displaySerial.takeIf { it.isNotBlank() }
        // ★ 用户定版：序列号前带 **SN:**（"ILCE-6300 SN:05186914"，与顶栏、设置页同一写法），
        //   设备短码那一截**不再显示**——它只有"同一机身换过设备码时区分两条记录"这一个
        //   用途，太技术了，不该占用户视线。
        PanelRow(
            // 图标从"文件夹"换成相机：这一行代表一台相机，用文件页的图标会误导
            icon = MsIcon.CAMERA,
            title = label + (serial?.let { " SN:$it" } ?: ""),
            subtitle = when {
                connected -> "已连接"
                selected && busy -> "正在连接"
                // ★ 用户定版：不再写"（点此重连）"—— 这一块**只负责切换设备**，
                //   重连是主界面那颗按钮的事，两个动作混在一行里会让人误触。
                selected -> "未连接"
                else -> "点击切换"
            },
            highlighted = selected,
            // ★ 用户定版：点**当前已选中**的那一行不响应 —— 原来它会再触发一次连接，
            //   于是"想换台相机、手一抖点到当前这台"会把会话重启一遍。
            //   传 null 的做法让 PanelRow 渲染成**不可点**的 Surface（没有水波纹、
            //   也没有按下反馈），从手感上就能看出"这一行不是个按钮"。
            onClick = if (selected) null else ({ onSwitchDevice(cam.peerDeviceId) }),
        )
    }
}

/** 悬浮窗里的一行：图标 + 主标题 + 副标题（[onClick] 为 null = 这一行不可点） */
@Composable
private fun PanelRow(
    icon: MsIcon,
    title: String,
    subtitle: String?,
    highlighted: Boolean = false,
    onClick: (() -> Unit)?,
) {
    // 半径 = 面板圆角 − 横向内缩 8dp，与外框**同心**（用户反馈"高亮和外框 r 角不符"）
    val accents = navAccents()
    val shape = RoundedCornerShape(CardCorner - 8.dp)
    val color = if (highlighted) accents.rowFill
    else Color.Transparent
    val boxModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
    // ★ 2.7.0（用户定版）：行内图标要吃**本行**的按压信号 —— 按到行任意处都要弹
    //   （旧行为是图标只认自己的边界：必须精准按在字形上才变实心）。
    val rowSource = remember { MutableInteractionSource() }
    if (onClick == null) {
        Surface(color = color, shape = shape, modifier = boxModifier) {
            CompositionLocalProvider(LocalRowInteraction provides rowSource) {
                PanelRowContent(icon, title, subtitle, highlighted, accents)
            }
        }
        return
    }
    Surface(
        color = color,
        onClick = onClick,
        interactionSource = rowSource,
        // ★ 必须给形状：Surface 默认是**直角**，高亮行会在圆角卡片里
        //   戳出一个方角、水波纹也是方块（用户反馈"圆角遮罩处理粗糙"）。
        //   半径与外框**同心**：面板 CardCorner、这里内缩 8dp（见 shape 处注释）。
        shape = shape,
        modifier = boxModifier,
    ) {
        CompositionLocalProvider(LocalRowInteraction provides rowSource) {
            PanelRowContent(icon, title, subtitle, highlighted, accents)
        }
    }
}

/** 上面两种 Surface 的同一份内容（可点 / 不可点只差外面那层）。 */
@Composable
private fun PanelRowContent(
    icon: MsIcon,
    title: String,
    subtitle: String?,
    highlighted: Boolean,
    accents: NavAccents,
) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            MsIcon(
                icon = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (highlighted) accents.rowIcon
                else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    color = if (highlighted) accents.rowOn
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlighted) accents.rowOn
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
}

/** 一行式指标：小灰标签在上、数值在下（与旧顶栏进度窗同一版式） */
@Composable
private fun PanelMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            // 同 ProgressSection：面板上的文字一律显式 onSurface，不吃继承
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val m = s / 60
    val h = m / 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m % 60, s % 60)
        else -> "%d:%02d".format(m, s % 60)
    }
}

private fun formatSpeed(bps: Long): String = when {
    bps >= 1 shl 20 -> "%.1f MB/s".format(bps / 1048576.0)
    bps >= 1 shl 10 -> "%.0f KB/s".format(bps / 1024.0)
    bps > 0 -> "$bps B/s"
    else -> "—"
}

