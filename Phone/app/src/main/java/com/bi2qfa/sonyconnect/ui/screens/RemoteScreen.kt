package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.RecController
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.pm.ActivityInfo

@Composable
fun RemoteScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rec = RecController.rec
    val preview = RecController.preview
    val postview = RecController.postview
    val busy = RecController.busy
    val err = RecController.error

    // 遥控界面跟随手机旋转（其余界面保持竖屏锁定）；离开时还原
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        scope.launch { RecController.enter() }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            scope.launch { RecController.leave() }
        }
    }

    LaunchedEffect(RecController.sessionActive) {
        while (RecController.sessionActive) {
            RecController.refresh()
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (postview != null) {
            Image(
                bitmap = postview.asImageBitmap(),
                contentDescription = "回看",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { RecController.dismissPostview() },
            )
            Text(
                "点击画面关闭回看 · 原图已加入传输",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 48.dp),
            )
        } else if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "实时取景",
                // Fit：完整呈现取景画面（不裁切）；点按坐标按实际显示区域
                // 换算成帧内千分比，黑边区域的点按会被钳制到边缘
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val bmp = RecController.preview ?: return@detectTapGestures
                            val bw = bmp.width
                            val bh = bmp.height
                            if (bw > 0 && bh > 0) {
                                val imgAspect = bw.toFloat() / bh
                                val boxAspect = size.width.toFloat() / size.height
                                var left = 0f
                                var top = 0f
                                var w = size.width.toFloat()
                                var h = size.height.toFloat()
                                if (imgAspect > boxAspect) {
                                    h = w / imgAspect
                                    top = (size.height - h) / 2f
                                } else {
                                    w = h * imgAspect
                                    left = (size.width - w) / 2f
                                }
                                val nx = ((offset.x - left) / w).coerceIn(0f, 1f)
                                val ny = ((offset.y - top) / h).coerceIn(0f, 1f)
                                scope.launch { RecController.touchAf(nx, ny) }
                            }
                        }
                    },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = Color.White)
                else Text("等待实时取景…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) {
                    Text("关闭", color = Color.White)
                }
                Text(
                    when {
                        rec.recording -> "REC ${rec.recSeconds}s"
                        rec.focus == "lock" -> "对焦锁定"
                        rec.focus == "working" -> "对焦中"
                        else -> rec.lens.ifBlank { "遥控拍摄" }
                    },
                    color = if (rec.recording) Color(0xFFFF5252) else Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.width(64.dp))
            }

            if (!err.isNullOrBlank()) {
                Text(err, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
            if (!rec.error.isNullOrBlank()) {
                Text("相机：$rec.error", color = Color(0xFFFFCC80), style = MaterialTheme.typography.bodySmall)
            }
            if (rec.active) {
                Text(
                    buildString {
                        append("源").append(rec.lvSrc)
                        append(" 帧").append(rec.seqFrames + rec.jpgFrames).append('/').append(rec.lvSent)
                        append(" 连").append(rec.lvClients)
                        if (RecController.lvStatus.isNotBlank()) append(" · ").append(RecController.lvStatus)
                        if (rec.shootFired.isNotBlank()) append(" · 快门").append(rec.shootFired)
                            .append(" onShutter=").append(rec.shutterSt)
                            .append(" 启动=").append(rec.capStart)
                            .append(" 抑制=").append(rec.inhibit)
                        if (rec.shootErr.isNotBlank()) append(" · ").append(rec.shootErr)
                        if (rec.seqErr.isNotBlank()) append(" · seqErr:").append(rec.seqErr)
                    },
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PropChip("ISO", rec.iso, rec.isoAvail, displayValue = if (rec.iso == "0") "auto" else null) {
                        scope.launch { RecController.setProp("iso", it) }
                    }
                    StepChip("F", rec.fnumber, {
                        scope.launch { RecController.setProp("fnumber", "-") }
                    }, {
                        scope.launch { RecController.setProp("fnumber", "+") }
                    })
                    StepChip("SS", rec.shutter, {
                        scope.launch { RecController.setProp("shutter", "-") }
                    }, {
                        scope.launch { RecController.setProp("shutter", "+") }
                    })
                    val ecAvail = (rec.expCompMin..rec.expCompMax).map { it.toString() }
                    PropChip("EV", rec.expComp, ecAvail) {
                        scope.launch { RecController.setProp("expComp", it) }
                    }
                    PropChip("WB", rec.wb, rec.wbAvail) {
                        scope.launch { RecController.setProp("wb", it) }
                    }
                    PropChip("对焦", rec.focusMode, rec.focusModeAvail) {
                        scope.launch { RecController.setProp("focusMode", it) }
                    }
                    PropChip("闪光", rec.flash, rec.flashAvail) {
                        scope.launch { RecController.setProp("flash", it) }
                    }
                    PropChip("模式", rec.expMode, rec.expModeAvail) {
                        scope.launch { RecController.setProp("expMode", it) }
                    }
                    PropChip("自拍", rec.selfTimer, rec.selfTimerAvail) {
                        scope.launch { RecController.setProp("selfTimer", it) }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZoomHold("W") {
                        scope.launch { RecController.zoom(1) }
                    }
                    MovieButton(recording = rec.recording, enabled = !busy) {
                        scope.launch { RecController.movie(!rec.recording) }
                    }
                    ShutterButton(enabled = !busy && !rec.recording, onHalf = {
                        scope.launch { RecController.halfPress(true) }
                    }, onReleaseHalf = {
                        scope.launch { RecController.halfPress(false) }
                    }, onShoot = {
                        scope.launch { RecController.shoot() }
                    })
                    ZoomHold("T") {
                        scope.launch { RecController.zoom(0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomHold(label: String, onHold: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val armed = remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pressed) {
        if (pressed) {
            armed.value = true
            onHold()
        } else if (armed.value) {
            scope.launch { RecController.zoom(-1) }
        }
    }
    FilledTonalButton(
        onClick = {},
        interactionSource = interaction,
    ) {
        Text(label)
    }
}

@Composable
private fun StepChip(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.45f)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("−", color = Color.White, modifier = Modifier.clickable(onClick = onMinus).padding(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                Text(
                    value.ifBlank { "—" },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("+", color = Color.White, modifier = Modifier.clickable(onClick = onPlus).padding(6.dp))
        }
    }
}

@Composable
private fun PropChip(
    label: String,
    value: String,
    avail: List<String>,
    displayValue: String? = null,
    onPick: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.45f),
        modifier = Modifier.clickable(enabled = avail.isNotEmpty()) {
            val i = avail.indexOf(value)
            onPick(avail[(i + 1).mod(avail.size)])
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Text(
                (displayValue ?: value).ifBlank { "—" },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    onHalf: () -> Unit,
    onReleaseHalf: () -> Unit,
    onShoot: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val armed = remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            armed.value = true
            onHalf()
        } else if (armed.value) {
            onReleaseHalf()
        }
    }
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.95f else 0.4f))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                onShoot()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935)),
        )
    }
}

@Composable
private fun MovieButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (recording) 16.dp else 22.dp)
                .clip(if (recording) RoundedCornerShape(3.dp) else CircleShape)
                .background(Color(0xFFFF5252)),
        )
    }
}
