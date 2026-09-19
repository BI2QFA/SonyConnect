package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.ui.FloatingNavInset
import com.bi2qfa.sonyconnect.ui.SlimProgress
import com.bi2qfa.sonyconnect.ui.components.Chevron
import com.bi2qfa.sonyconnect.ui.components.ExpressiveButton
import com.bi2qfa.sonyconnect.ui.components.GroupCard
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.Segment
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow
import com.bi2qfa.sonyconnect.ui.formatCapacity
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Motion
import com.bi2qfa.sonyconnect.ui.components.MsIcon

/**
 * 主界面：相机（含电量）、镜头、存储卡、连接状态。
 *
 * MD3E 版式（用户给的参考图那套）：**按语义分段**，每段一个小标题 + 一张 28dp 大圆角
 * 卡片，段与段之间靠留白分。原来是三张等权重的卡片居中堆叠，看不出主次；分段之后
 * "这台相机是谁 / 镜头是什么 / 卡里还剩多少 / 连上没有"四件事各有各的位置。
 *
 * ★ 这一页**未连接时也显示**（用户定版：进软件有配对设备就直接进主界面，期间自动连）。
 * 所以每一段都要在"没数据"时写成"—"，而不是整段消失 —— 版面跳来跳去比缺一行更难受。
 * 相机型号在未连接时从**配对表**取（`DeviceStore.info` 已在断开时清空），
 * 否则断开后用户看不出这个软件正准备连哪一台。
 *
 * ★★ **主页只外露电量**（用户定版）：型号只作卡片标题（不作标题就不知道这是哪台相机），
 *  序列号 / 地区 / 固件 / 安卓版本 / Java API 版本全部收进相机详情页 —— 点这张卡进去看。
 *  电量从原来独立的卡片**并进相机卡**：它本来就是"这台相机"的一个读数，
 *  单列一张卡等于把它和相机拆成了两件事。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(onOpenCameraDetail: () -> Unit) {
    val context = LocalContext.current
    val info = DeviceStore.info
    val state = ConnectionCenter.state
    val connecting = ConnectionCenter.connecting
    val connected = state == ConnectionCenter.State.CONNECTED
    // 未连接时的型号来源：本次目标那台（可能是上次连的，也可能是刚切过去的）。
    // ★ displayGuid() 末尾有启动兜底（targetGuid 没置位时退到"上次那台"），
    //   所以启动第一帧这里就有正确的设备可显示 —— 不再有"Sony 相机"占位（用户定版）。
    val target = ConnectionCenter.displayGuid()?.let { PairingStore.find(it) }

    // ★ 型号走 displayModel 推导（见 PairedCamera）：peerName 可能是
    //   "ILCE-6300 SN:05186914" 这种组合串，直接兜底到它就是主界面漏出组合串的成因。
    val model = info?.model?.takeIf { it.isNotBlank() }
        ?: target?.displayModel?.takeIf { it.isNotBlank() }
        ?: "未知相机"

    val batteryPct = info?.batteryPct ?: -1
    val remainMin = info?.batteryRemainMin ?: -1

    // 存储卡：总容量与已用**同时**有效才算数（只给一个算不出比例、画不出进度条，
    // 那就该老实显示"—"而不是画一条假的）
    val sdKnown = info?.sdKnown == true
    val sdTotal = if (sdKnown) info!!.sdTotalBytes else 0L
    val sdUsed = if (sdKnown) info!!.sdUsedBytes else 0L

    // ★★ "卡片之间不要留那么大的空"（用户定版）：Column 不再加自己的间距 ——
    //   每张卡外面的 [SettingsGroup] 自带上下 9dp 内边距，相邻卡片之间本来就是
    //   18dp 的缝。之前 Column 又叠了 16dp、缝胀到 34dp 才会显得松散。
    //   heightIn(min = 视口高) 保留：内容超出一屏（连接失败的错误行出现时）照旧滚动。
    BoxWithConstraints {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                // 悬浮导航盖在内容上：底部留出它占的高度，否则最后一段永远压在药丸下面
                .padding(bottom = FloatingNavInset),
        ) {
        // ===== 相机（整卡可点，进相机详情；电量并入这一张） =====
        // ★★ 分区标题（相机/镜头/存储卡/连接）**整行删掉**（用户定版）：卡片内容
        //   本来就自解释（型号就是相机、三个数字就是存储卡），四行小标题占了 130dp
        //   却没提供信息 —— 删掉之后整页正好一屏放下，还能少滚一屏。
        GroupCard(onClick = onOpenCameraDetail) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model,
                        style = MaterialTheme.typography.headlineMedium,
                        // 机型名可能很长（如 `SonyConnect ILCE-6300`）。让它换行会把行尾
                        // 那个 "›" 顶到下一行 —— 那是"这张卡能点"的唯一提示，宁可截断机型
                        // 也不能把它挤走
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Chevron()
                }
                // 标签与大号读数**同一行**：分开两行要多占一整行的行高，而主界面要
                // 一屏放下（用户定版）。标签仍必需 —— 24sp 的 "—" 有 40px 宽，
                // 没有标签就像一条横线（见下方存储卡同样的处理）。
                // ★ 三个 Text 都 `alignByBaseline()`：只靠 Row 的 `Alignment.Bottom`
                //   对齐的是**文本框底边**，而 14sp 与 24sp 的字框底部留白不一样多，
                //   于是"66%"的下边缘看着比"电量"低一截（用户反馈）。按**基线**对齐
                //   才是字形真正落到同一条线上。
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "电量",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Box(Modifier.width(8.dp))
                    // ★ 没有读数时那一条破折号要**再往下 4dp**：24sp 的 "—" 字形画在字框
                    //   偏上的位置，实测它的字形中心比旁边 14sp 汉字的视觉中心高 8px（@2x），
                    //   基线对齐之后看着是"飘在电量上头"（用户反馈）。有数字时不挪 ——
                    //   数字与汉字本来就落在同一条基线上。
                    //   ★ 必须用 graphicsLayer 的 translationY，不能用 Modifier.offset：
                    //   offset 会把这一行的测量高度也顶大 4dp，于是 Row 变高、标签跟着往下走，
                    //   实测两边一起下移、相对位置一点没变。graphicsLayer 只改绘制、不参与
                    //   测量，基线对齐取的仍是原来那条线 —— 动的就只有这一条破折号。
                    Text(
                        if (batteryPct >= 0) "$batteryPct%" else "—",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .alignByBaseline()
                            .graphicsLayer {
                                translationY = if (batteryPct >= 0) 0f else 4.dp.toPx()
                            },
                    )
                    if (remainMin > 0) {
                        Box(Modifier.width(10.dp))
                        Text(
                            "约可拍摄 $remainMin 分钟",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // ★ weight(1f) 不能省：行里第三段文字没有权重时，Row 会把它
                            //   压到几乎没有宽度，然后**一个字一行**竖着排下去
                            //   （装机实测：把卡片撑成了一根竖条）。给它剩余宽度 + 单行省略，
                            //   窄了也只是截断。
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .alignByBaseline(),
                        )
                    }
                }
                if (batteryPct >= 0) {
                    SlimProgress(fraction = batteryPct.coerceIn(0, 100) / 100f)
                }
            }
        }

        // ===== 镜头 =====
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = info?.lens?.takeIf { it.isNotBlank() } ?: "—",
                // 镜头名可能很长（E PZ 16-50mm F3.5-5.6 OSS II）：长了**换行**显示，
                // 不省略（用户定版）；圆片与文字块仍严格共中线
                titleMaxLines = 3,
                // 圆片底色统一走 [IconTints.Accent]（primaryFixed，当前色彩模式下的
                // 浅强调色；用户定版"圆形图标背景统一取一个比较浅的色"）。图标用
                // aperture 字形的 [MsIcon.LENS]：有光圈叶片，一眼是"镜头"。
                leading = { IconCircle(MsIcon.LENS, IconTints.Accent) },
            )
        }

        // ===== 存储卡（相机里那张卡：已用 / 剩余 / 总容量 三联） =====
        GroupCard {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row {
                    Stat("已用", if (sdKnown) formatCapacity(sdUsed) else "—",
                        Modifier.weight(1f))
                    Stat("剩余", if (sdKnown) formatCapacity(sdTotal - sdUsed) else "—",
                        Modifier.weight(1f))
                    Stat("总容量", if (sdKnown) formatCapacity(sdTotal) else "—",
                        Modifier.weight(1f))
                }
                if (sdKnown) {
                    SlimProgress(fraction = info!!.sdUsedFraction)
                }
            }
        }

        // ===== 连接 =====
        // 未连接的原因（心跳失联 / 相机端退出 / 未找到设备）—— 没这行用户只能猜
        val error = if (!connected && !connecting) ConnectionCenter.lastError else null
        // 这一段组里是几段：状态行 +（有错时）错误行 + 按钮行
        val segments = if (error != null) 3 else 2
        SettingsGroup {
            SettingsRow(
                index = 0, count = segments,
                title = when {
                    connected -> "已连接"
                    connecting -> "正在连接"
                    else -> "未连接"
                },
                support = when {
                    connected -> buildString {
                        // ★ 方式写在前、SSID 写在后（原来前面挂了个"方式："）：
                        //   这一行**只给一行**（见 supportMaxLines，Wi-Fi 名一长就折行，
                        //   左边的圆片跟右边的文字就对不齐），于是尾巴必然被省略 ——
                        //   那就把"最不能丢的"放前面。用户最需要一眼看到的是
                        //   "以什么方式连着"，SSID 截断还能接受。
                        append(when (info?.mode) { "wifi" -> "Wi-Fi 网络"; else -> "相机热点" })
                        if (!info?.ssid.isNullOrBlank()) append(" · ").append(info!!.ssid)
                    }
                    // 正在连接时不再写副标题（用户定版：只保留"正在连接"四个字）。
                    // 未连接时这句说明**该去查什么**（用户定版文案）：不说清原因，
                    // 用户只会反复点重连。
                    connecting -> null
                    else -> "确保相机服务正常运行"
                },
                // 说明文字单行省略（用户反馈：Wi-Fi 名过长时折行，圆片与文字就不对齐了）
                supportMaxLines = 1,
                // 圆片颜色跟着状态走：连上=绿、断开=紫（不再是"绿点/灰点"，那种小圆点
                // 在深色底上对比太弱，看不出状态）。正在连时换成 MD3E 的
                // LoadingIndicator —— 那枚会变形的多边形是这一版最认得出的一件东西，
                // 比一个灰圈更能说明"正在等"。
                // 转圈 ↔ 圆片之间**交叉淡变**（effects 弹簧）：连接成功那一刻
                // 两者直接硬切会"啪"地跳一下，淡变过去才连续
                leading = {
                    Crossfade(
                        targetState = connecting && !connected,
                        animationSpec = Motion.effectsDefault(),
                        label = "connLeading",
                    ) { waiting ->
                        if (waiting) {
                            Box(
                                Modifier.size(44.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator(Modifier.size(34.dp))
                            }
                        } else {
                            IconCircle(
                                MsIcon.WIFI,
                                // 连上=绿（语义色，保留"状态"的读法）；没连上跟主题走
                                // （primaryContainer，与镜头圆片同一套，不再出现暗紫红）
                                IconTints.Accent,
                            )
                        }
                    }
                },
            )
            if (error != null) {
                Segment(index = 1, count = 3) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Segment(index = segments - 1, count = segments) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (connected) {
                        ExpressiveButton(
                            "断开连接",
                            { ConnectionCenter.disconnect() },
                            modifier = Modifier.fillMaxWidth(),
                            tonal = true,
                        )
                    } else {
                        ExpressiveButton(
                            if (connecting) "正在连接…" else "重新连接",
                            { ConnectionCenter.reconnect(context) },
                            modifier = Modifier.fillMaxWidth(),
                            // 已经有一轮在连了就别让点：再叠一轮只会互相抢网
                            enabled = !connecting,
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * 一格读数：小标签 + 数值。
 *
 * ★ 存储卡用三联（已用 / 剩余 / 总容量）而不是"一个大号已用 + 一行小字"：三个数都要
 *   看得见（用户定版），并排各占一列**一行就摆完** —— 主界面要一屏不用滑动就放下，
 *   多一行就是多一行的高度（旧版把总容量挤进右侧小字，剩余量只能靠进度条去猜）。
 * ★ 数值用 titleMedium 不是 headlineSmall："119.3 GB" 在 24sp 下有 110dp 宽，
 *   而一列只有 97dp（360dp 屏 - 两侧卡片留白 - 卡片内边距，再三等分），
 *   用大号字会被截成 "119.3 G…"，反而读不出容量。
 */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
