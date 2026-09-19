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
import com.bi2qfa.sonyconnect.ui.components.MsIcon

/**
 * **配对页**（用户定版：只保留配对模式，连接模式已删）。
 *
 * 出现时机只有两个：本机一台相机都没配过（进软件的默认页），或从设置手动进入
 * （已连着一台时再配第二台）。**连接**这件事再也不经过这个页面 —— 进软件直接
 * 自动连"上次那台"，要换设备走悬浮窗的"已绑定设备"。
 *
 * 列表里的相机**只可能是正开着配对窗口的**（`ConnectionCenter.startPairingScan`
 * 按探测应答里的 `pairingMode` 过滤）：没开窗口的相机根本没码可核对，列出来只会
 * 让用户输完码才发现相机不收。点一台 → 弹 6 位码输入框。
 *
 * **强制配对**：那串 6 位码只显示在相机的"配对模式"页上，所以首次配对必须人在相机旁边。
 *
 * MD3E 版式：段落小标题 + 卡片；扫描按钮做成一整颗 56dp 的全宽主按钮（这一页
 * 只有一件主要的事，就该让它占满、一眼看见）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen() {
    val scanning = ConnectionCenter.scanning
    val results = ConnectionCenter.scanResults
    val error = ConnectionCenter.lastError
    val pairTarget = ConnectionCenter.pairingTarget
    val pairStage = ConnectionCenter.pairingStage
    val pairError = ConnectionCenter.pairingError
    val context = LocalContext.current

    // 进页面就扫一轮：配对窗口是有时限的（相机端 180 秒），缓存的列表不能用
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
                // 标题只留"去哪"，路径细节放说明里：行标题被限制成一行（见 SettingsRow），
                // "在相机上进入「连接设置 → 配对模式」"这一整句会被截成"配…"
                // ★ 标题与提示文案按用户定版的新稿（提示**不许出现省略号**，
                //   所以放宽 supportMaxLines 让整句折行显示完）
                title = "确保相机端软件正常运行",
                support = "相机端软件内无已配对设备时会自动进入配对模式，或者在选项菜单中点击「进入配对模式」",
                supportMaxLines = 4,
                leading = { IconCircle(MsIcon.CAMERA, IconTints.Accent) },
            )
        }

        Box(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            // 扫描中：按钮内转圈 + "扫描中"动画点（与通知一致，1s 循环 . .. ...）
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
                // ★ 扫描中**保持可点**（用户定版）：新扫描要求会终止老扫描重新开始，
                //   而不是没反应。禁止态让用户在扫描中途换条件时只能干等。
                leadingIcon = {
                    if (scanning) {
                        // 等待态用 MD3E 的 LoadingIndicator（会变形的多边形），
                        // 与主界面连接中那处一致
                        LoadingIndicator(Modifier.size(22.dp))
                    } else {
                        MsIcon(
                            icon = MsIcon.REFRESH,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            size = 20.dp,
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
                        // 用户定版：不再区分"这台相机绑定过手机"，只报地址与可配对状态
                        support = "${c.host}:${c.protoPort} · 可配对",
                        leading = { IconCircle(MsIcon.CAMERA, IconTints.Accent) },
                        trailing = { Chevron() },
                        onClick = { ConnectionCenter.requestPair(c) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ★ 用户定版的新流程（相机待机不亮码）：点一台相机之后**先把配对连接建起来**，
    //   相机这才在屏上亮出 6 位码；所以"正在连接相机"与"输入配对码"是**两个阶段**，
    //   界面必须分开显示 —— 连接还没成就弹输码框，用户会发现相机上根本没有码可看。
    when (pairStage) {
        ConnectionCenter.PairStage.CONNECTING, ConnectionCenter.PairStage.SUBMITTING -> {
            PairWaitingDialog(
                cameraName = pairTarget?.name?.ifBlank { "SonyConnect 相机" } ?: "",
                busy = pairStage == ConnectionCenter.PairStage.SUBMITTING,
                error = pairError,
                onDismiss = { ConnectionCenter.cancelPair() },
            )
        }
        ConnectionCenter.PairStage.AWAITING_CODE -> {
            PairCodeDialog(
                cameraName = pairTarget?.name?.ifBlank { "SonyConnect 相机" } ?: "",
                busy = false,
                error = pairError,
                onDismiss = { ConnectionCenter.cancelPair() },
                onSubmit = { code -> ConnectionCenter.submitPairCode(context, code) },
            )
        }
        ConnectionCenter.PairStage.IDLE -> {
            // ★ 失败必须**弹窗**说清（用户定版）：连接断开、连不上、配对码错误
            //   原来只是把弹窗一关、错误文案放在没人显示的字段里 —— 用户看到的就是
            //   "框自己消失了"，什么都没发生一样。现在 stage 归零且带着错误时
            //   弹一个带「确定」的提示框，关掉才算看完。
            if (pairError != null) {
                PairErrorDialog(
                    message = pairError,
                    onDismiss = { ConnectionCenter.dismissPairingError() },
                )
            }
        }
    }
}

/**
 * 配对失败提示框：标题按失败类型给（码错 = 配对码错误，其余 = 无法连接相机），
 * 正文是 [ConnectionCenter.pairingError] 里的具体原因（断开 / 占用 / 版本过旧…）。
 */
@Composable
private fun PairErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (message.contains("配对码")) "配对码错误" else "无法连接相机") },
        text = {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        },
    )
}

/**
 * "正在连接相机…"等待框（配对第一步：建连接 + 让相机亮码）。
 *
 * <p>这一步**必须有反馈**：连不上时用户什么都不会看到（相机不会亮码），
 * 而这个框里的说明文字告诉他要等什么、以及连不上时该去检查什么。
 */
@Composable
private fun PairWaitingDialog(
    cameraName: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("与 $cameraName 配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (busy) "正在验证配对码…" else "正在连接相机…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // ★ 底注跟阶段走（用户定版）：建连接阶段告诉用户"接下来相机会亮码"；
                //   验证配对码阶段的提示是"配对成功后将自动连接相机"。
                Text(
                    if (busy) "配对成功后将自动连接相机"
                    else "连接成功后，相机屏幕上会显示 6 位配对码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消")
            }
        },
    )
}

/**
 * 6 位配对码输入框。
 *
 * 输入框只接受数字、最多 6 位（`PairingClient.CODE_LEN`），到 6 位就允许提交 ——
 * 减少一次"按了确定却报长度不对"的来回。码本身不做任何本地校验，
 * 对不对由相机说了算（错了回来会说"配对码不正确或已失效"）。
 *
 * 码用大号等宽间距居中显示：6 位数字要一眼能对着相机屏幕核，小字挤在一起最容易看错。
 */
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
                        // 只留数字、截到 6 位，避免用户输错还被拒
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
