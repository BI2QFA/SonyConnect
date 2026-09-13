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
        
        
        
        
        enableEdgeToEdge(
            statusBarStyle = barStyle(darkThemeNow()),
            navigationBarStyle = barStyle(darkThemeNow()),
        )
        
        
        
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val dark = useDarkTheme()
            
            
            
            
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












@Composable
fun AppRoot() {
    val context = LocalContext.current
    val state = ConnectionCenter.state
    
    val hasPaired = PairingStore.cameras.isNotEmpty()
    val connectedCamera = ConnectionCenter.camera
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    
    var settingsPage by remember { mutableStateOf(SettingsPage.Hub) }
    
    var showPairing by remember { mutableStateOf(false) }
    
    var showCameraDetail by remember { mutableStateOf(false) }
    var browsingPreviousGuid by remember { mutableStateOf("") }
    
    
    
    val filesDirs = remember { mutableStateMapOf<String, String>() }
    val filesDir = remember { mutableStateOf("/") }
    val browsingGuid = ConnectionCenter.displayGuid().orEmpty()
    LaunchedEffect(browsingGuid) {
        filesDirs[browsingPreviousGuid] = filesDir.value   
        filesDir.value = filesDirs[browsingGuid] ?: "/"    
        browsingPreviousGuid = browsingGuid
    }

    val pairingScreen = showPairing || !hasPaired

    
    
    LaunchedEffect(hasPaired) {
        if (hasPaired) ConnectionCenter.autoConnectLast(context)
    }

    
    
    
    LaunchedEffect(connectedCamera) {
        if (connectedCamera != null) showPairing = false
    }

    
    
    
    
    
    
    
    
    BackHandler(enabled = showPairing) { showPairing = false }
    
    
    BackHandler(enabled = showCameraDetail) { showCameraDetail = false }
    
    BackHandler(enabled = showSettings && !showPairing && settingsPage != SettingsPage.Hub) {
        settingsPage = SettingsPage.Hub
    }
    BackHandler(enabled = showSettings && !showPairing && settingsPage == SettingsPage.Hub) {
        showSettings = false
    }
    BackHandler(enabled = !showSettings && !pairingScreen && tab != 0) { tab = 0 }

    
    
    
    
    
    
    val deviceLabel = ConnectionCenter.displayGuid()?.let { ConnectionCenter.labelOf(it) }
    val subtitle: String? = when {
        pairingScreen -> deviceLabel
        state == ConnectionCenter.State.CONNECTED && deviceLabel != null -> deviceLabel
        deviceLabel != null -> "未连接 · $deviceLabel"
        else -> "未连接相机"
    }
    val (pageTitle, onBack) = when {
        
        pairingScreen -> "配对相机" to (if (hasPaired) ({ showPairing = false }) else null)
        showCameraDetail -> "相机信息" to ({ showCameraDetail = false })
        showSettings -> settingsPage.title to (
            if (settingsPage == SettingsPage.Hub) ({ showSettings = false })
            else ({ settingsPage = SettingsPage.Hub })
            )
        else -> "SonyConnect" to null
    }

    
    
    
    
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            
            
            
            
            
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                AppHeader(title = pageTitle, subtitle = subtitle, onBack = onBack)
            },
        ) { pad ->
            
            
            
            
            
            
            
            
            
            val screenKey = when {
                pairingScreen -> "pairing"
                showCameraDetail -> "cameraDetail"
                showSettings -> "settings:${settingsPage.ordinal}"
                else -> "tab$tab"
            }
            
            
            
            
            val slideDefault = Motion.spatialDefault<IntOffset>()
            val slideFast = Motion.spatialFast<IntOffset>()
            val fadeDefault = Motion.effectsDefault<Float>()
            val fadeFast = Motion.effectsFast<Float>()
            AnimatedContent(
                targetState = screenKey,
                transitionSpec = {
                    val from = navPosOf(initialState)
                    val to = navPosOf(targetState)
                    
                    
                    
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
                            
                            
                            
                            page = SettingsPage.entries[
                                key.substringAfter(':').toIntOrNull() ?: 0
                            ],
                            onNavigate = { settingsPage = it },
                            onOpenPairing = { showPairing = true },
                        )
                        key == "tab0" -> HomeScreen(onOpenCameraDetail = { showCameraDetail = true })
                        key == "tab1" -> FilesScreen(onGoTransfers = { tab = 2 }, dirState = filesDir)
                        else -> TransfersScreen()
                    }
                }
            }
        }

        
        
        
        
        if (hasPaired && !showSettings && !showCameraDetail && !pairingScreen) {
            FloatingNav(
                
                modifier = Modifier.fillMaxSize(),
                tabIndex = tab,
                onTab = { tab = it },
                onOpenSettings = {
                    
                    settingsPage = SettingsPage.Hub
                    showSettings = true
                },
                onSwitchDevice = { guid ->
                    
                    
                    tab = 0
                    ConnectionCenter.switchTo(context, guid)
                },
            )
        }
    }
}



















private fun navPosOf(key: String): Pair<String, Int>? = when {
    key == "tab0" -> "camera" to 0
    key == "cameraDetail" -> "camera" to 1
    key.startsWith("settings:") -> "settings" to (key.substringAfter(':').toIntOrNull() ?: 0)
    else -> null
}
