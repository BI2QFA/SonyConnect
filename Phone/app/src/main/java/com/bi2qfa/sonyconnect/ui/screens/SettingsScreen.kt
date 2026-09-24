package com.bi2qfa.sonyconnect.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.bi2qfa.sonyconnect.data.IdentityRepo
import com.bi2qfa.sonyconnect.ui.theme.useDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ui.components.CardCorner
import com.bi2qfa.sonyconnect.ui.components.Chevron
import com.bi2qfa.sonyconnect.ui.components.GroupCard
import com.bi2qfa.sonyconnect.ui.components.IconCircle
import com.bi2qfa.sonyconnect.ui.components.PlainDialog
import com.bi2qfa.sonyconnect.ui.components.RowIconAction
import com.bi2qfa.sonyconnect.ui.components.Segment
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow
import com.bi2qfa.sonyconnect.ui.components.SwitchRow
import com.bi2qfa.sonyconnect.ui.theme.IconTints
import com.bi2qfa.sonyconnect.ui.theme.Seeds
import com.bi2qfa.sonyconnect.ui.components.MsIcon
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import com.bi2qfa.sonyconnect.ui.components.SectionHeader
import com.bi2qfa.sonyconnect.ui.components.ConnectedButtonGroup

/**
 * 设置的页面栈。**一级页只放入口**（一行一件事，行尾一个 "›"），
 * 具体开关搬到二级页 —— 用户反馈原话："设置所有设置项不要全都露在外面，
 * 要有点进去的二级页面"。
 *
 * 顶栏的标题与返回箭头由 `MainActivity` 按这个枚举决定（返回箭头回到上一级）。
 */
enum class SettingsPage(val title: String) {
    Hub("设置"),
    Cameras("已配对相机"),
    Files("文件保存位置"),
    Storage("预览图与缓存"),
    Appearance("外观"),
    About("关于"),
    // 赞助：从"关于"页进来（二级页），顶栏标题"赞助"
    Reward("赞助"),
}

/**
 * 设置页（一级 = 入口列表；二级 = 具体设置项）。
 *
 * 版式照参考图的 Google 账号设置页：**一屏都是"入口行"**（左彩色圆片 + 标题 +
 * 一行说明这页里有什么 + 行尾 "›"），行按语义**成组**（外圈大圆角、段间一道细缝）。
 *
 * ★ 说明行不是套话：参考图里每一行的副标题都在**列出这一页里能改什么**
 *   （"支付方式、交易"／"姓名、邮箱、电话、地址"）。这里照做，用户不用点进去就知道去哪儿找。
 */
@Composable
fun SettingsScreen(
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    onOpenPairing: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        when (page) {
            SettingsPage.Hub -> SettingsHub(onNavigate)
            SettingsPage.Cameras -> CamerasPage(onOpenPairing)
            SettingsPage.Files -> FilesPage()
            SettingsPage.Storage -> StoragePage()
            SettingsPage.Appearance -> AppearancePage()
            // 关于页要把"赞助"作为下一级入口，所以把 onNavigate 传下去
            SettingsPage.About -> AboutPage(onNavigate)
            SettingsPage.Reward -> RewardPage()
        }
    }
}

/**
 * 一级页：三组。
 *
 * ★ 「传输」那一栏已按用户要求**整栏删除**（连它二级页里那个
 *   "传输完成后自动关闭相机端"开关一起，两端都删了）。
 */
@Composable
private fun SettingsHub(onNavigate: (SettingsPage) -> Unit) {
    // 相机自己一组：它是这个软件的主角，和"文件"那些配套设施不是一类
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "相机",
            support = "已配对相机、配对新相机",
            leading = { IconCircle(MsIcon.CAMERA, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Cameras) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "文件",
            support = "文件保存位置",
            leading = { IconCircle(MsIcon.NAV_FILES, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Files) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "预览图与缓存",
            support = "自动传输预览图、缓存大小、清除缓存",
            leading = { IconCircle(MsIcon.IMAGE_PLACEHOLDER, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Storage) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 2,
            title = "外观",
            support = "跟随系统主题色、调色盘、深浅色",
            leading = { IconCircle(MsIcon.PALETTE_DOTS, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Appearance) },
        )
        SettingsRow(
            index = 1, count = 2,
            title = "关于",
            support = "版本信息、开发者、赞助",
            leading = { IconCircle(MsIcon.INFO, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.About) },
        )
    }
}

/** 二级页：已配对相机（解除配对 / 切设备 / 配新的）。 */
@Composable
private fun CamerasPage(onOpenPairing: () -> Unit) {
    val context = LocalContext.current
    // 待确认解除的相机（非空即弹确认框）
    var pendingUnpair by remember { mutableStateOf<String?>(null) }
    val cameras = PairingStore.cameras

    pendingUnpair?.let { id ->
        val cam = PairingStore.find(id)
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("解除配对？") },
            text = {
                Text(
                    // 用户定版：只说"解除配对"，不再解释重连要重新取码
                    "将解除与「${if (cam != null) labelOf(cam) else id}」的配对。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnpair = null
                    ConnectionCenter.unpair(id)
                }) {
                    Text("解除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpair = null }) { Text("取消") }
            },
        )
    }

    if (cameras.isEmpty()) {
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = "尚无已配对相机",
                support = "在相机上进入配对模式",
                leading = { IconCircle(MsIcon.CAMERA, IconTints.Accent) },
            )
        }
    } else {
        SettingsGroup {
            cameras.forEachIndexed { idx, cam ->
                val connectedGuid = ConnectionCenter.camera?.guidHex
                val connected = cam.peerDeviceId.equals(connectedGuid, ignoreCase = true)
                // ★ 这一组**不用 [SettingsRow]（= 库的 ListItem）**，改用自己的 Row：
                //   ListItem 在有 supportingContent 时把 leading 对齐到**文字块的顶边**
                //   （不是行高的中线）。两行文字时那点差看不出（44dp 圆片 vs ~40dp 文字块），
                //   而这个说明是**三行**（型号 / 状态+SN / 设备码），实测圆片中心比整行中心
                //   高 23.5px ≈ 12dp —— 用户一眼就看出来了（"图标不在所属框的纵轴中央"）。
                //   换成 Row(verticalAlignment = CenterVertically) 之后，圆片按整行居中，
                //   与传输页那种两行卡片的做法也一致。
                //   形状与底色仍走同一套分段件（segmentShape / segmentSurfaceColor），
                //   所以"几小块拼一大块"的外观不变。
                Segment(
                    index = idx, count = cameras.size,
                    onClick = { ConnectionCenter.switchTo(context, cam.peerDeviceId) },
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconCircle(
                            MsIcon.CAMERA,
                            IconTints.Accent,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            // 标题只放型号（用户定版那条"相机型号 + SN"整串放不进这一行：
                            // 行首 44dp 圆片 + 行尾垃圾桶吃掉了大半宽度，写成一整串会被截断，
                            // 而 SN 恰恰是不能被截掉的那一段）。
                            Text(
                                cam.peerModel.ifBlank { cam.peerName }.ifBlank { "未知相机" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // ★ 设备码要显示（用户定版：同一个机身换过设备码时，配对表里会有
                            //   多条型号+SN 完全一样的记录，靠它才分得清该解除哪一条），
                            //   但**不能让它自己折行**：原来 `已连接 · SN:05186914 · e0fa60c4`
                            //   挤不下就断在半截处（用户反馈"换行了、不美观"）。这里**显式
                            //   分两行**：第一行状态+SN，第二行用"设备码 xxx"起头 ——
                            //   断点由我们定，那串十六进制也有了名分。
                            Text(
                                buildString {
                                    append(if (connected) "已连接" else "未连接")
                                    if (cam.peerSerial.isNotBlank()) {
                                        append(" · SN:").append(cam.peerSerial)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val code = ConnectionCenter.shortCode(cam.peerDeviceId)
                            if (code.isNotEmpty()) {
                                Text(
                                    "设备码 $code",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        RowIconAction(
                            MsIcon.TRASH,
                            "解除配对",
                            onClick = { pendingUnpair = cam.peerDeviceId },
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "配对新相机",
            support = "输入配对码与相机配对",
            leading = { IconCircle(MsIcon.ADD, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = onOpenPairing,
        )
    }
}

/** 二级页：文件保存位置。 */
@Composable
private fun FilesPage() {
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SettingsRepo.updateDownloadTreeUri(uri.toString())
        }
    }

    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "文件下载目录",
            support = when {
                SettingsRepo.downloadTreeUri.isBlank() ->
                    "当前：应用目录（Android/data/com.bi2qfa.sonyconnect）"
                else -> "当前：" + SettingsRepo.downloadDirLabel()
            },
            leading = { IconCircle(MsIcon.NAV_FILES, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { treePicker.launch(null) },
        )
    }
    if (SettingsRepo.downloadTreeUri.isNotBlank()) {
        SettingsGroup {
            SettingsRow(
                index = 0, count = 1,
                title = "恢复默认目录",
                support = "恢复到默认下载目录，已经下载的文件不会被移动或删除",
                leading = { IconCircle(MsIcon.REFRESH, IconTints.Accent) },
                onClick = { SettingsRepo.updateDownloadTreeUri("") },
            )
        }
    }
}

/** 二级页：预览图与缓存（自动传输开关 + 缓存上限 + 占用 + 清除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoragePage() {
    val context = LocalContext.current
    // 占用在进入页面时算一次（不监听 —— 页面停留期间由本页动作引起的变化自己刷新）
    var usageBytes by remember { mutableStateOf(-1L) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun refreshUsage() {
        usageBytes = -1L
        Thread({
            val n = ThumbStore.cacheSizeBytes()
            android.os.Handler(android.os.Looper.getMainLooper()).post { usageBytes = n }
        }, "CacheUsage").apply { isDaemon = true; start() }
    }
    LaunchedEffect(Unit) { refreshUsage() }

    if (showClearConfirm) {
        PlainDialog(onDismissRequest = { showClearConfirm = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("清除缓存？", style = MaterialTheme.typography.titleLarge)
                Text(
                    "将删除手机里全部缩略图与预览图缓存（不影响已下载的照片）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                    TextButton(onClick = {
                        showClearConfirm = false
                        ThumbStore.clearCache()
                        // ★ 2.6（用户定版）：清完缓存立刻按当前模式重新触发传输 ——
                        //   自动传输开着就重拉"小图+大预览"，关着就只重拉小图。
                        //   名单为空（未连接）时 restartAutoFetch 走清空分支，无事发生。
                        ThumbStore.restartAutoFetch()
                        android.widget.Toast.makeText(context, "缓存已清除", android.widget.Toast.LENGTH_SHORT).show()
                        refreshUsage()
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    SettingsGroup {
        SwitchRow(
            index = 0, count = 1,
            title = "自动传输预览图",
            support = "同时自动传输缩略图和预览图，开启后会使预览传输速度变慢",
            checked = SettingsRepo.autoPreviewFetch,
            onCheckedChange = { SettingsRepo.updateAutoPreviewFetch(it) },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 2,
            title = "缓存大小",
            support = "图片缓存所能使用的最大空间",
            leading = { IconCircle(MsIcon.NAV_FILES, IconTints.Accent) },
        )
        // ★ 用户定版：档位选择改用**连体变形选择框**（与"深浅色"同款 ConnectedButtonGroup）
        Segment(index = 1, count = 2) {
            val labels = SettingsRepo.CACHE_LIMIT_OPTIONS.map { SettingsRepo.formatCacheLimit(it) }
            ConnectedButtonGroup(
                options = labels,
                selectedIndex = SettingsRepo.CACHE_LIMIT_OPTIONS
                    .indexOf(SettingsRepo.cacheLimitBytes).coerceIn(0, labels.lastIndex),
                onSelect = { i ->
                    SettingsRepo.updateCacheLimitBytes(SettingsRepo.CACHE_LIMIT_OPTIONS[i])
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            )
        }
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "当前占用",
            support = when {
                usageBytes < 0 -> "统计中…"
                else -> formatBytes(usageBytes)
            },
            leading = { IconCircle(MsIcon.GRID, IconTints.Accent) },
            trailing = {
                // 手动刷新一次占用（IconCircle 自身不吃 modifier，包一层 Box 承接点击）
                // ★ 用户定版：**不要水波纹**（旧的方形高光太丑），只要**图标弹一下** ——
                //   indication = null + 显式交互源，源同时喂给圆片里的图标
                val refreshSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier.clickable(
                        interactionSource = refreshSrc,
                        indication = null,
                    ) { refreshUsage() },
                ) {
                    IconCircle(MsIcon.REFRESH, IconTints.Accent, source = refreshSrc)
                }
            },
        )
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "清除缓存",
            support = "清除缩略图与预览图的缓存文件",
            leading = { IconCircle(MsIcon.TRASH, IconTints.Accent) },
            onClick = { showClearConfirm = true },
        )
    }
}

/** 字节数 → "x.x MB" / "xxx KB"。 */
private fun formatBytes(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
    n >= 1024L * 1024 -> "%.1f MB".format(n / (1024.0 * 1024))
    n >= 1024L -> "%d KB".format(n / 1024)
    else -> "$n B"
}

/** 二级页：外观。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearancePage() {
    SettingsGroup {
        SwitchRow(
            index = 0, count = 1,
            title = "跟随系统主题色",
            support = "可用时使用设备动态配色",
            checked = SettingsRepo.dynamicColor,
            onCheckedChange = { SettingsRepo.updateDynamicColor(it) },
        )
    }
    if (!SettingsRepo.dynamicColor) {
        // 色点行**自己是一段**（不是挂在标签行下面的 extra）：分组列表的原生写法就是
        // 一段一件事，段的形状由库按 index/count 算，两段天生同形、中间自带那道缝
        SettingsGroup {
            SettingsRow(
                index = 0, count = 2,
                title = "调色盘",
                support = SeedNames.getOrElse(SettingsRepo.seedIndex) { "" },
                leading = { IconCircle(MsIcon.PALETTE_DOTS, IconTints.Accent) },
            )
            Segment(index = 1, count = 2) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // ★ 左右内边距必须**对称**（原来 start=76、end=20：左边为了对齐
                        //   标签列留了 76dp，于是 4 颗色点整体偏左、右边空一大截 ——
                        //   用户实测"四个颜色组合起来看不居中"）。
                        //   改成左右各 20dp + SpaceEvenly：4 颗色点在这条宽度里等距铺开，
                        //   组合的重心自然落在卡片中线上。
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Seeds.forEachIndexed { idx, seed ->
                        SeedSwatch(
                            color = seed.swatch,
                            selected = SettingsRepo.seedIndex == idx,
                            onClick = { SettingsRepo.updateSeed(idx) },
                        )
                    }
                }
            }
        }
    }
    SettingsGroup {
        SettingsRow(
            index = 0, count = 2,
            title = "深浅色",
            support = when (SettingsRepo.darkMode) {
                1 -> "当前：浅色"
                2 -> "当前：深色"
                else -> "当前：跟随系统"
            },
            leading = { IconCircle(MsIcon.CONTRAST, IconTints.Accent) },
        )
        Segment(index = 1, count = 2) {
            // ★ MD3E **连体按钮组**（用户定版："会变形的那种"）：选中那颗形变成完整
            //   药丸、其余保持小圆角、切换时圆角走弹簧 —— 老的 SingleChoiceSegmentedButton
            //   是"选中项换底色 + 打勾"，没有形变。
            val labels = listOf("跟随系统", "浅色", "深色")
            ConnectedButtonGroup(
                options = labels,
                selectedIndex = SettingsRepo.darkMode.coerceIn(0, labels.lastIndex),
                onSelect = { SettingsRepo.updateDarkMode(it) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            )
        }
    }
}

/** 二级页：关于。 */
@Composable
private fun AboutPage(onNavigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    GroupCard {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("SonyConnect", style = MaterialTheme.typography.headlineSmall)
            Text(
                "版本 ${versionNameOf(context)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "配套相机端 SonyConnect 2.6 使用\n开发者：BI2QFA",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ★ 本机设备码（用户要求：加在"开发者"下方，与相机端关于页同一版式）：
            //   取 IdentityRepo 落盘的那 16 位 hex 的**前 8 位** —— 与相机端那份"本机
            //   设备码"对仗，排障时两头各报一个码就能对上谁是谁。
            Text(
                "本机设备码：${ConnectionCenter.shortCode(IdentityRepo.deviceId)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 赞助入口：单独一组（它不是"关于"正文的一部分，而是一个动作）
    SettingsGroup {
        SettingsRow(
            index = 0, count = 1,
            title = "赞助",
            // 与赞助页里那句说明**同一句话**（用户改过措辞，两处一起改）
            support = "给开发者买杯咖啡吧",
            leading = { IconCircle(MsIcon.REWARD, IconTints.Accent) },
            trailing = { Chevron() },
            onClick = { onNavigate(SettingsPage.Reward) },
        )
    }
}

/**
 * 赞助页：一张 MD3E 圆角卡里放赞赏码。
 *
 * ★ 两张码按**深浅色**换（用户给的两张图就是照这个做的：深色底那张给深色模式用）：
 *   判据直接取主题自己的 [useDarkTheme]（"跟随系统/浅色/深色"三态都收敛在它里面），
 *   不要另写 `isSystemInDarkTheme()` —— 那样用户在设置里手动选"深色"时会拿错图。
 */
@Composable
private fun RewardPage() {
    val dark = useDarkTheme()
    Column(Modifier.fillMaxSize()) {
        // ★ 不要再套一层 SettingsGroup：`GroupCard` 内部**已经是** SettingsGroup
        //   （16dp 横向边距 + 32dp 圆角）。套两层就是 32dp 边距、圆角也看着不一致 ——
        //   用户实测"赞助页那两个卡片的 R 角和离屏幕边缘的距离要和别处统一"。
        GroupCard {
            Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painterResource(
                            if (dark) R.drawable.reward_qr_dark else R.drawable.reward_qr_light
                        ),
                        contentDescription = "赞赏码",
                        // ★ 内层圆角要和外框**同心**（用户反馈"内层图片的 r 角该和外框一致"）：
                        //   外框 CardCorner（32dp）、图片四周内缩 16dp，
                        //   所以内层半径 = CardCorner − 16 = 16dp。
                        //   两边都写同一个值时圆弧走向对不上，看着就是"内框比外框圆" ——
                        //   同一条规矩见悬浮面板的高亮行（FloatingNav 的注释）。
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(CardCorner - 16.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                Text(
                    "给开发者买杯咖啡吧",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ★ 赞助用户列表（用户定版）：赞赏码下面**另开一张框**，框上写小标题，
        //   内部**一行四个**。
        //   · 小标题用与全应用同一枚 [SectionHeader]（字号/留白一致，不另写一套）；
        //   · 框用同一枚 [GroupCard] → 圆角就是 [CardCorner]（32dp），
        //     与设置页/主界面那些卡片完全一致（用户特别提醒"注意框的 r 角"）；
        //   · 一行四个的"四个"用**等宽格子**保证：每个格子 weight(1f)，
        //     现在有三位赞助者（strive. / 糖醋白鸽 / 经久鱼水情，**按赞助先后排序**），
        //     第四个是空格子 —— 每个格子都在四分之一格里居中，
        //     将来补到第四位时不会因为人数变化而整体位移。
        SectionHeader("赞助用户列表")
        GroupCard {
            Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SponsorCell(
                        R.drawable.sponsor_strive, "strive. ",
                    modifier = Modifier.weight(1f),
                )
                // ★ 2.7.0：新增两位赞助者 —— **按赞助先后排**（糖醋白鸽先，经久鱼水情后）
                SponsorCell(
                    R.drawable.sponsor_tangcu_baige, "糖醋白鸽",
                    modifier = Modifier.weight(1f),
                )
                SponsorCell(
                    R.drawable.sponsor_jingjiu_yushuiqing, "经久鱼水情",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 赞助列表里的一格：**圆形头像 + 名字**（用户定版：名字放在圆形头像下面）。
 *
 * 头像固定 56dp、`ContentScale.Crop` 后按圆形裁剪 —— 原图是方的，不裁会露出四个角。
 * 名字限一行、居中、超出省略（人名再长也不该把格子撑变形）。
 */
@Composable
private fun SponsorCell(
    @DrawableRes icon: Int,
    name: String,
    /** 由调用方（Row 里）传 `Modifier.weight(1f)` —— `weight` 是 RowScope 的扩展，
     *  普通 composable 内部拿不到，必须从外面给。 */
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painterResource(icon),
            contentDescription = name,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 调色盘上的一颗色点：选中时套一圈描边，比只做粗边更好认。 */
@Composable
private fun SeedSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 30.dp else 34.dp)
                .background(color, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else Modifier
                ),
        )
    }
}

private val SeedNames = listOf("蓝", "紫", "绿", "琥珀")

/**
 * 已配对相机的展示名：**相机型号 + SN**（用户定版）。
 *
 * 型号/SN 来自配对成功后立刻拉取的 `OP_DEVICE_INFO`；万一那次拉取失败，
 * 这里逐级回退到友好名、最后才是设备码 —— 总之不显示空白行。
 */
private fun labelOf(cam: PairingStore.PairedCamera): String {
    val model = cam.peerModel.ifBlank { cam.peerName }
    if (model.isBlank()) return cam.peerDeviceId
    return if (cam.peerSerial.isNotBlank()) "$model SN:${cam.peerSerial}" else model
}

private fun versionNameOf(context: android.content.Context): String = try {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    pm.versionName ?: ""
} catch (e: Exception) {
    ""
}
