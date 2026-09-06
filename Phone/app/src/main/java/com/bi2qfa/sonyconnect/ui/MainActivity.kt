package com.bi2qfa.sonyconnect.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.transfer.TransferStore
import com.bi2qfa.sonyconnect.ui.screens.DisconnectedScreen
import com.bi2qfa.sonyconnect.ui.screens.FilesScreen
import com.bi2qfa.sonyconnect.ui.screens.HomeScreen
import com.bi2qfa.sonyconnect.ui.screens.SettingsScreen
import com.bi2qfa.sonyconnect.ui.screens.TransfersScreen
import com.bi2qfa.sonyconnect.ui.theme.SonyConnectTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SonyConnectTheme {
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
}

private enum class Tab { HOME, FILES, TRANSFERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val state = ConnectionCenter.state
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val filesDir = remember { mutableStateOf("/") }

    androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
    androidx.activity.compose.BackHandler(
        enabled = !showSettings && state == ConnectionCenter.State.CONNECTED && tab != 0,
    ) { tab = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SonyConnect") },
                actions = {
                    ProgressIcon()
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_settings_gear), contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            if (state == ConnectionCenter.State.CONNECTED && !showSettings) {
                NavigationBar {
                    val items = listOf(
                        Triple(Tab.HOME, R.drawable.ic_nav_home, "主界面"),
                        Triple(Tab.FILES, R.drawable.ic_nav_files, "文件"),
                        Triple(Tab.TRANSFERS, R.drawable.ic_nav_transfers, "传输"),
                    )
                    items.forEachIndexed { idx, (t, icon, label) ->
                        val selected = tab == idx
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = idx },
                            icon = {
                                if (t == Tab.TRANSFERS && TransferStore.hasPending()) {
                                    BadgedBox(badge = { Badge() }) {
                                        Icon(painterResource(icon), contentDescription = label)
                                    }
                                } else {
                                    Icon(painterResource(icon), contentDescription = label)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { pad ->

        val screenKey = when {
            showSettings -> "settings"
            state != ConnectionCenter.State.CONNECTED -> "disconnected"
            else -> "tab$tab"
        }
        androidx.compose.animation.AnimatedContent(
            targetState = screenKey,
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))
                    .togetherWith(
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
                    )
            },
            label = "screen",
        ) { key ->            Surface(Modifier.fillMaxSize().padding(pad)) {
                when (key) {
                    "settings" -> SettingsScreen(onBack = { showSettings = false })
                    "disconnected" -> DisconnectedScreen()
                    "tab0" -> HomeScreen()
                    "tab1" -> FilesScreen(onGoTransfers = { tab = 2 }, dirState = filesDir)
                    else -> TransfersScreen()
                }
            }
        }
    }
}

@Composable
private fun ProgressIcon() {
    val batchRunning = TransferStore.batchRunning
    val thumbActive = ThumbStore.batchTotal > 0 &&
        (ThumbStore.batchDone < ThumbStore.batchTotal || ThumbStore.paused)
    if (!batchRunning && !thumbActive) return

    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { overallFraction(batchRunning).coerceIn(0f, 1f) },
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        )
        Icon(
            painterResource(if (batchRunning) R.drawable.ic_download else R.drawable.ic_thumb_download),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Box(
            Modifier.matchParentSize().clickable { expanded = !expanded },
        )
        ProgressPopup(visible = expanded, onDismiss = { expanded = false })
    }
}

private fun overallFraction(batchRunning: Boolean): Float = if (batchRunning) {
    val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
    TransferStore.batchDoneBytes.coerceIn(0, total) / total.toFloat()
} else {
    val t = ThumbStore.batchTotal
    if (t > 0) ThumbStore.batchDone.toFloat() / t else 0f
}

@Composable
private fun ProgressPopup(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return

    val tick = produceState(0) {
        while (true) {
            delay(1000)
            value++
        }
    }
    val density = LocalDensity.current
    val provider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val margin = with(density) { 6.dp.roundToPx() }
                return IntOffset(
                    windowSize.width - popupContentSize.width - margin,
                    anchorBounds.top + anchorBounds.height + margin,
                )
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            Modifier
                .widthIn(min = 250.dp, max = 320.dp)
                .padding(top = 4.dp),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("传输进度", style = MaterialTheme.typography.titleSmall)

                if (TransferStore.batchRunning) {
                    val total = TransferStore.batchTotalBytes.coerceAtLeast(1)
                    val done = TransferStore.batchDoneBytes.coerceIn(0, total)
                    val pct = (done * 100 / total).toInt()
                    val elapsedMs = (System.currentTimeMillis() - TransferStore.batchStartedAt).coerceAtLeast(1)
                    val speed = TransferStore.batchSpeedBps
                    val etaSec = if (speed > 0) (total - done) / speed else -1
                    Text(
                        "下载 $pct%",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SlimProgress(fraction = done / total.toFloat())

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("已用", formatDuration(elapsedMs), Modifier.weight(1f))
                        Metric(
                            "剩余",
                            if (etaSec >= 0) formatDuration(etaSec * 1000) else "—",
                            Modifier.weight(1f),
                        )
                        Metric("速度", formatSpeed(speed), Modifier.weight(1.2f))
                    }
                } else {
                    Text(
                        "下载 无进行中的传输",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                if (ThumbStore.batchTotal > 0) {
                    val tDone = ThumbStore.batchDone
                    val tTotal = ThumbStore.batchTotal
                    val complete = tDone >= tTotal
                    Text(

                        if (complete) "缩略图加载完成 $tDone/$tTotal"
                        else "缩略图 $tDone/$tTotal" + if (ThumbStore.paused) "（已暂停）" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SlimProgress(
                        fraction = if (tTotal > 0) tDone.toFloat() / tTotal else 0f,
                    )
                } else {
                    Text(
                        "缩略图 空闲",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
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
