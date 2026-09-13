package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.ptpip.PairingClient
import com.bi2qfa.sonyconnect.ui.FloatingNavInset
import com.bi2qfa.sonyconnect.ui.components.Chevron
import com.bi2qfa.sonyconnect.ui.components.ExpressiveButton
import com.bi2qfa.sonyconnect.ui.components.GroupCard
import com.bi2qfa.sonyconnect.ui.components.HintText
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SectionHeader
import com.bi2qfa.sonyconnect.ui.components.SettingsRow
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import kotlinx.coroutines.delay

















@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen() {
    val scanning = ConnectionCenter.scanning
    val results = ConnectionCenter.scanResults
    val error = ConnectionCenter.lastError
    val pairTarget = ConnectionCenter.pairingTarget
    val pairBusy = ConnectionCenter.pairingBusy
    val pairError = ConnectionCenter.pairingError
    val context = LocalContext.current

    
    LaunchedEffect(Unit) {
        ConnectionCenter.startPairingScan(context, force = true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = FloatingNavInset),
    ) {
        SectionHeader("与相机配对")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                
                
                title = "在相机上进入「配对模式」",
                support = "选项 → 进入配对模式 → 确定。选择要配对的相机后输入相机屏幕上显示的 6 位配对码",
                leading = { IconCircle(R.drawable.ic_camera, IconTints.Purple) },
            )
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            
            val dots = produceState(1) {
                while (true) {
                    delay(1000)
                    value = value % 3 + 1
                }
            }
            ExpressiveButton(
                text = if (scanning) "扫描中" + ".".repeat(dots.value) else "扫描可配对相机",
                onClick = { ConnectionCenter.startPairingScan(context, force = true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
                leadingIcon = {
                    if (scanning) {
                        
                        
                        LoadingIndicator(Modifier.size(22.dp))
                    } else {
                        androidx.compose.material3.Icon(
                            painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        }

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        SectionHeader("发现的相机")
        if (results.isEmpty()) {
            GroupCard {
                HintText(
                    if (scanning) "正在扫描同一网络下的相机…"
                    else "没有找到相机，确认相机已进入「配对模式」，且手机与相机处于同一网络",
                )
            }
        } else {
            SettingsGroup {
                results.forEachIndexed { idx, c ->
                    SettingsRow(
                        index = idx, count = results.size,
                        title = c.name.ifBlank { "SonyConnect 相机" },
                        
                        support = "${c.host}:${c.protoPort} · 可配对",
                        leading = { IconCircle(R.drawable.ic_camera, IconTints.Blue) },
                        trailing = { Chevron() },
                        onClick = { ConnectionCenter.requestPair(c) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pairTarget != null) {
        PairCodeDialog(
            cameraName = pairTarget.name.ifBlank { "SonyConnect 相机" },
            busy = pairBusy,
            error = pairError,
            onDismiss = { ConnectionCenter.cancelPair() },
            onSubmit = { code -> ConnectionCenter.submitPairCode(context, code) },
        )
    }
}










@Composable
private fun PairCodeDialog(
    cameraName: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("与 $cameraName 配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "输入相机屏幕上显示的 ${PairingClient.CODE_LEN} 位配对码",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { v ->
                        
                        code = v.filter { it.isDigit() }.take(PairingClient.CODE_LEN)
                    },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("配对码") },
                    textStyle = TextStyle(
                        textAlign = TextAlign.Center,
                        letterSpacing = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("配对中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = !busy && code.length == PairingClient.CODE_LEN,
            ) {
                Text("配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}
