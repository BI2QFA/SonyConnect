package com.bi2qfa.sonyconnect.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.ui.components.AppHeader
import com.bi2qfa.sonyconnect.ui.screens.CameraDetailScreen
import com.bi2qfa.sonyconnect.ui.screens.FilesScreen
import com.bi2qfa.sonyconnect.ui.screens.HomeScreen
import com.bi2qfa.sonyconnect.ui.screens.PairingScreen
import com.bi2qfa.sonyconnect.ui.screens.SettingsPage
import com.bi2qfa.sonyconnect.ui.screens.SettingsScreen
import com.bi2qfa.sonyconnect.ui.screens.TransfersScreen
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.ui.theme.SonyConnectTheme
import com.bi2qfa.sonyconnect.ui.theme.useDarkTheme

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ 沉浸式：在 setContent **之前**就把边到边摆好。
        //   原来只在下面的 LaunchedEffect 里调 —— 那个要等首帧之后才跑，冷启动时
        //   能看见状态栏区域先闪一下底色再变成内容。这里先按当前深浅色设一次，
        //   设置里改主题时再由 LaunchedEffect 重设（两处判据必须一致，见 darkThemeNow）。
        enableEdgeToEdge(
            statusBarStyle = barStyle(darkThemeNow()),
            navigationBarStyle = barStyle(darkThemeNow()),
        )
        // ★ 关掉系统给系统栏垫的那层**对比度蒙版**（Android 10+）：透明栏也会被它涂成
        //   一条灰带（MIUI 上尤其明显），那正是"沉浸不彻底"的来源。内容自己画到栏下，
        //   不需要这层灰。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val dark = useDarkTheme()
            // ★ 系统栏图标明暗必须跟着**我们自己的**深浅色走（不能靠
            //   enableEdgeToEdge 的 auto）：本应用默认深色，若按系统取色，
            //   手机在浅色模式下会给出"深色图标 + 我们的深色底" = 状态栏看不见。
            //   深浅色在设置里一改这里就重跑，不用重启。
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = barStyle(dark),
                    navigationBarStyle = barStyle(dark),
                )
            }
            SonyConnectTheme(dark) {
                AppRoot()
            }
        }
        // 首次进入即请求通知权限（传输进度/保活通知依赖；只问一次，拒绝后
        // 传输页开始传输时仍会再请求）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val sp = getSharedPreferences("settings", MODE_PRIVATE)
            if (!sp.getBoolean("notifRequested", false)) {
                sp.edit().putBoolean("notifRequested", true).apply()
                runCatching {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * 与主题里那个 `useDarkTheme()` **同一套判据**，但能在 Compose 之外调用
     * （onCreate 里要在 setContent 之前把系统栏样式摆好）。
     * ★ 两处判据必须一致：不一致时首帧的系统栏图标会与内容底色打架 ——
     *   那正是当初把判据抽成 `useDarkTheme()` 的原因。
     */
    private fun darkThemeNow(): Boolean = when (com.bi2qfa.sonyconnect.data.SettingsRepo.darkMode) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun barStyle(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
}

/**
 * 应用根：决定"进软件先看到哪一页"。
 *
 * **用户定版的路由规则**（与原版最大的差别：进入软件的连接页不再每次都出现）：
 * - 本机**一台都没配过** → 配对页（此刻唯一有意义的事就是配对）
 * - 本机**已有配对相机** → 直接进主界面，并**自动连上"上次最后连接过的那台"**
 * - 配对页只在"配对时"出现：没有配对设备，或从设置手动进（连着一台时再配第二台）
 *
 * 因此主界面要能在**未连接**时也显示（写"未连接 · ILCE-6300"并给"重新连接"），
 * 悬浮导航也一并显示 —— 不然用户连不上任何相机时，连"换一台"都没有入口。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val state = ConnectionCenter.state
    // 配对表是 Compose 状态：配对/解除立刻让这里重组（决定进配对页还是主界面）
    val hasPaired = PairingStore.cameras.isNotEmpty()
    val connectedCamera = ConnectionCenter.camera
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    // 设置内部的页面栈（一级=入口列表，二级=具体设置项）。见 SettingsPage。
    var settingsPage by remember { mutableStateOf(SettingsPage.Hub) }
    // 从设置页手动进配对页（用户定版：配对界面 = 无绑定设备时的默认页 + 设置里的入口）
    var showPairing by remember { mutableStateOf(false) }
    // 相机详情（从主页那张相机卡推进来的二级页）。**只从主页进**，所以与设置/配对互斥。
    var showCameraDetail by remember { mutableStateOf(false) }
    var browsingPreviousGuid by remember { mutableStateOf("") }
    // 文件页浏览位置提升到根（用户定版：切页再回仍在原目录）。
    // ★ 位置也要**按设备分开**记（隔离原则）：`/DCIM/100MSDCF` 这种路径两台相机上
    //   完全一样，记一份就会出现"切到 B 还停在 A 浏览的目录"，用户以为在看 B 的目录。
    val filesDirs = remember { mutableStateMapOf<String, String>() }
    val filesDir = remember { mutableStateOf("/") }
    val browsingGuid = ConnectionCenter.displayGuid().orEmpty()
    LaunchedEffect(browsingGuid) {
        filesDirs[browsingPreviousGuid] = filesDir.value   // 存下上一台的位置
        filesDir.value = filesDirs[browsingGuid] ?: "/"    // 恢复这一台的位置
        browsingPreviousGuid = browsingGuid
    }

    val pairingScreen = showPairing || !hasPaired

    // 进软件自动连"上次最后连接过的那台"（用户定版）。hasPaired 变化时重跑：
    // 配好第一台（false→true）那一刻也会自动连上，不必让用户再点一次。
    LaunchedEffect(hasPaired) {
        if (hasPaired) ConnectionCenter.autoConnectLast(context)
    }

    // 连接/配对成功（当前相机换成了一台）→ 自动回主界面（用户定版）。
    // 键用 camera 而不是 state：已连着一台时再从设置配第二台，state 一直是 CONNECTED
    // 不变，只有 camera 会变 —— 盯着 state 的话那次配对成功后配对页不会自己退。
    LaunchedEffect(connectedCamera) {
        if (connectedCamera != null) showPairing = false
    }

    // 当前屏的 key：返回历史与页面转场共用同一份（见下面 AnimatedContent）。
    val screenKey = when {
        pairingScreen -> "pairing"
        showCameraDetail -> "cameraDetail"
        showSettings -> "settings:${settingsPage.ordinal}"
        else -> "tab$tab"
    }

    // ★★ 返回 = **访问历史栈**（用户定版"触发返回手势时回到上一次显示的屏幕，
    //   主界面也是如此 —— 要记住上一次显示了什么"）。
    //
    //   之前是手写的层级规则（设置返回=关设置、非主界面 tab 返回=回主界面…），
    //   每一层"返回该去哪"各写各的，结果"关于 → 打赏"按返回直接跳回设置一级
    //   （打赏的层级规则只认得"回 Hub"），用户实测报了 bug。现在**前进**时把当前
    //   屏压栈，返回弹栈回到上一次显示的那一屏 —— 打赏返回回关于、设置返回回
    //   进入前的 tab、tab 之间也互相记得，全部自动成立。
    //
    //   栈空且已在主界面（tab0）时 handler 关闭 → 交给系统退出。
    val backStack = remember { mutableStateListOf<String>() }

    /**
     * **前进**的入口统一先压栈。
     *
     * ★★ 不能把压栈挂在 screenKey 变化上：返回（弹栈恢复）同样会改变 screenKey，
     *   那一来"回退"也会往栈里塞刚离开的屏 —— 栈越弹越深、还会绕回刚离开的屏
     *   （实测：关于 → 打赏，按返回回关于，再按却进了打赏）。正解是经典模型：
     *   **前进处压栈、返回处弹栈**，两条路彻底分开。
     */
    fun pushCurrent() {
        val k = screenKey
        if (backStack.lastOrNull() != k) backStack.add(k)
    }

    fun restoreScreen(key: String) {
        when {
            key == "pairing" -> {
                showPairing = true
                showCameraDetail = false
                showSettings = false
            }
            key == "cameraDetail" -> {
                showCameraDetail = true
                showSettings = false
            }
            key.startsWith("settings:") -> {
                showSettings = true
                showCameraDetail = false
                settingsPage = SettingsPage.entries.getOrNull(
                    key.substringAfter(':').toIntOrNull() ?: 0,
                ) ?: SettingsPage.Hub
            }
            else -> {
                tab = key.removePrefix("tab").toIntOrNull()?.coerceIn(0, 2) ?: 0
                showSettings = false
                showCameraDetail = false
                showPairing = false
            }
        }
    }

    BackHandler(enabled = backStack.isNotEmpty() || screenKey != "tab0") {
        var restored = false
        while (backStack.isNotEmpty()) {
            val prev = backStack.removeAt(backStack.lastIndex)
            // 跳过与当前屏相同的项（重复进出同一屏会压进重复 key）
            if (prev != screenKey) {
                restoreScreen(prev)
                restored = true
                break
            }
        }
        if (!restored && screenKey != "tab0") restoreScreen("tab0")
    }

    // 顶栏（MD3E：大标题 + 副标题 + 子页给返回箭头）。
    // 副标题挂相机身份：把"现在连的是哪台"顶在标题下面（多设备下这一眼很要紧）。
    // ★ 用户定版（回改）：这里显示**"型号 SN:序列号"组合串**（如 ILCE-6300 SN:05186914）
    //   —— 同型号机身靠 SN 才分得清，顶栏这一眼最要紧。
    // ★ 配对页在没有设备型号时**不写副标题**（用户定版：删掉"先与一台相机配对"）——
    //   标题"配对相机"已经把这件事说完了，副标题再复述一遍是废话。
    //   传 **null** 而不是空串：AppHeader 只在非 null 时画那一行（空串会留一条空行，
    //   标题下面白着一块比没有更难看）。
    val deviceLabel = ConnectionCenter.displayGuid()?.let { ConnectionCenter.labelOf(it) }
    val subtitle: String? = when {
        pairingScreen -> deviceLabel
        state == ConnectionCenter.State.CONNECTED && deviceLabel != null -> deviceLabel
        deviceLabel != null -> "未连接 · $deviceLabel"
        else -> "未连接相机"
    }
    val (pageTitle, onBack) = when {
        // 首次启动（一台都没配过）的配对页不给返回：这时候返回没有去处
        pairingScreen -> "配对相机" to (if (hasPaired) ({ showPairing = false }) else null)
        showCameraDetail -> "相机信息" to ({ showCameraDetail = false })
        showSettings -> settingsPage.title to (
            if (settingsPage == SettingsPage.Hub) ({ showSettings = false })
            else ({ settingsPage = SettingsPage.Hub })
            )
        else -> "SonyConnect" to null
    }

    // ★ 悬浮导航**不占** Scaffold 的 bottomBar 槽位：那个槽位是"预留 + 内容躲开"的
    //   旧式遮挡处理（用户反馈：下面字被切割，说明还留着遮挡）。悬浮栏就该盖在内容
    //   之上，所以这里改成 Box 覆盖；内容铺满整屏，各可滚动页自己留 FloatingNavInset
    //   的底部余地，最后一项照样能滚到栏上方。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // ★ containerColor 必须是**当前方案的 surface**，不能留透明：
            //   留透明时页面底实际是窗口背景（themes.xml 里那个深色常量），
            //   切到浅色后卡片变白了、页面底却还是黑的，顶栏深色标题直接看不见
            //   （装机实测）。深色下它与窗口底同色，所以看不出区别 —— 正是这种
            //   "只在浅色下才现形"的问题最容易漏。
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                AppHeader(title = pageTitle, subtitle = subtitle, onBack = onBack)
            },
        ) { pad ->
            // 页面切换过渡。
            // ★ 顶层页面之间仍是**纯淡入淡出**（用户定版），但淡变本身也换成 expressive
            //   的 effects 规格 —— 不再是写死的 tween(220)，而是弹簧：起手快、收尾稳。
            // ★ 设置的一级↔二级之间用 MD3E 的"共享轴 X"：进二级页从右侧推入、返回时从
            //   左侧推回，位移只有屏宽的一小段。**位移走 spatial（有回弹）、淡变走 effects**，
            //   而且进出不对称：进用 defaultSpatial（0.8/380）从容推开，出用 fastSpatial
            //   （0.6/800）干脆让位 —— "离开比进入快"是 MD3E 的规矩，别让用户等一个
            //   正在消失的页面。方向由两个状态里的层级算出来，所以返回时自动反着播。
            //   主界面 ↔ 相机详情同理（也是一对上一层/下一层）。
            // （screenKey 已提到返回历史栈那一段：返回与转场共用同一份。）
            // ★ 规格必须先在这里取好：`transitionSpec` 那个 lambda **不是 composable
            //   上下文**（`Motion.*` 是 @Composable，要读主题），写进去编译器会报
            //   "@Composable invocations can only happen from a @Composable function"。
            //   取出来当普通值捕获进 lambda 即可。
            val slideDefault = Motion.spatialDefault<IntOffset>()
            val slideFast = Motion.spatialFast<IntOffset>()
            val fadeDefault = Motion.effectsDefault<Float>()
            val fadeFast = Motion.effectsFast<Float>()
            AnimatedContent(
                targetState = screenKey,
                transitionSpec = {
                    val from = navPosOf(initialState)
                    val to = navPosOf(targetState)
                    // ★ 必须**同树**才比层级（见 navPosOf 的说明）：只比数字的话
                    //   主界面(0) → 设置一级(1) 也会被判成"往里推"，而那条转场是
                    //   用户定版过的纯淡入淡出，不能动。
                    if (from != null && to != null && from.first == to.first && from.second != to.second) {
                        val forward = to.second > from.second
                        val dir = if (forward) 1 else -1
                        val slideIn = if (forward) slideDefault else slideFast
                        val slideOut = if (forward) slideFast else slideDefault
                        (
                            slideInHorizontally(slideIn) { it / 4 * dir } + fadeIn(fadeDefault)
                            ).togetherWith(
                            slideOutHorizontally(slideOut) { -it / 4 * dir } + fadeOut(fadeFast),
                        )
                    } else {
                        fadeIn(fadeDefault).togetherWith(fadeOut(fadeFast))
                    }
                },
                label = "screen",
            ) { key ->
                Surface(Modifier.fillMaxSize().padding(pad), color = Color.Transparent) {
                    when {
                        key == "pairing" -> PairingScreen()
                        key == "cameraDetail" -> CameraDetailScreen()
                        key.startsWith("settings:") -> SettingsScreen(
                            // 内容按 **key** 取页（不是按外层的 settingsPage）：转场期间
                            // 新旧两页同时在画，都读外层状态的话旧页会跟着变成新页的内容，
                            // 动画就白做了
                            page = SettingsPage.entries[
                                key.substringAfter(':').toIntOrNull() ?: 0
                            ],
                            onNavigate = {
                                pushCurrent()
                                settingsPage = it
                            },
                            onOpenPairing = {
                                pushCurrent()
                                showPairing = true
                            },
                        )
                        key == "tab0" -> HomeScreen(
                            onOpenCameraDetail = {
                                pushCurrent()
                                showCameraDetail = true
                            },
                        )
                        key == "tab1" -> FilesScreen(
                            onGoTransfers = {
                                pushCurrent()
                                tab = 2
                            },
                            dirState = filesDir,
                        )
                        else -> TransfersScreen()
                    }
                }
            }
        }

        // 覆盖在内容之上的悬浮导航（见上面的说明）。
        // 条件从"已连接"放宽为"有配对设备"：未连接时用户更需要它 —— 面板里的
        // "已绑定设备"是换一台相机重连的**唯一**入口（连接页已经不再是常驻页面了）。
        // 相机详情页也不挂导航：它是从主页推进来的二级页，导航让位（与设置页一致）。
        if (hasPaired && !showSettings && !showCameraDetail && !pairingScreen) {
            FloatingNav(
                // 铺满内容区：导航栏贴底、面板向上展开（内部各自对齐）
                modifier = Modifier.fillMaxSize(),
                tabIndex = tab,
                onTab = { tab = it },
                onOpenSettings = {
                    pushCurrent()
                    // 每次从面板进设置都从头开始，而不是停在上次那个二级页
                    settingsPage = SettingsPage.Hub
                    showSettings = true
                },
                onSwitchDevice = { guid ->
                    pushCurrent()
                    // 切设备 = 直接进那台设备的主界面（用户定版）：先回主页签，
                    // 主页那张卡会显示"正在连接 <那台>"直到连上/报错
                    tab = 0
                    ConnectionCenter.switchTo(context, guid)
                },
            )
        }
    }
}

// 顶栏进度图标与进度悬浮窗已被**圆形控制中心按钮**取代：
// 进度环嵌在按钮内缘、详情搬进悬浮窗的第一段（见 FloatingNav.kt）。
// 这样进度指示同时承担展开控制中心的入口，顶栏只留标题与相机型号。

/**
 * 页面 key → **（所属导航树, 树内层级）**；不属于任何"可推进的层级"返回 null。
 * 只用来判"进更深一层还是退回上一层"，据此决定共享轴转场往哪边播。
 *
 * 两棵树：
 * - `"camera"`：主界面 `tab0`(0) ↔ 相机详情 `cameraDetail`(1)；
 * - `"settings"`：设置一级 `settings:0` ↔ 二级 `settings:n`(n)。
 * 其余（`pairing` / `tab1` / `tab2`）返回 null —— 与谁都不同树。
 *
 * ★ 为什么要连**树名**一起返回、而不是只给一个可比的数字：数字本身是可比的，
 *   于是 `tab0`(0) → `settings:1`(1) 也会被判成"往里推"。但主页进设置这条转场
 *   是用户定版过的**纯淡入淡出**，不能因为"深度恰好不同"就被改掉。
 *   跨树一律走淡入淡出，只有同树内层级变化才推。
 */
private fun navPosOf(key: String): Pair<String, Int>? = when {
    key == "tab0" -> "camera" to 0
    key == "cameraDetail" -> "camera" to 1
    key.startsWith("settings:") -> "settings" to (key.substringAfter(':').toIntOrNull() ?: 0)
    else -> null
}
