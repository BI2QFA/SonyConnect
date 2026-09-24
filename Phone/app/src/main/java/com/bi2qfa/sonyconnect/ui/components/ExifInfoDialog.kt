package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "照片信息"弹窗（用户定版版式；**两处预览器共用**，2.7.0）。
 *
 * <p>调用方（各管各的数据源、行序由共同的构建器保证一致）：
 * <ul>
 *   <li>传输页本地查看器：行来自 `LocalPhoto.readExif`（读本地原文件）；</li>
 *   <li>文件页相机预览：行来自 `LocalPhoto.buildExifRows` + 预览 sidecar 的 JSON
 *       （EXIF 随大预览一并传输/缓存）。</li>
 * </ul>
 *
 * <p>行序（用户定版）：文件名 / 文件格式 / 拍摄日期时间 / 相机型号 / 镜头型号 /
 * 焦距 / 光圈 / 快门速度 / EV / ISO。本组件只负责画，不关心数据从哪来。
 */
@Composable
fun ExifInfoDialog(rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    PlainDialog(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
            Text("照片信息", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // 关闭按钮：**右下角**（用户定版），但把三处间距权衡成这样才不显突兀：
            //   · 与最后一行信息的间距收紧到 4dp —— 它是"接着内容"的动作，不是独立一块；
            //   · 右边距沿用外层 Column 的 24dp（与上面的数值列同一条右边界，不再内缩）；
            //   · 底部留 12dp 收尾（MD3 对话框的惯例是 12~24dp，太紧会像贴边、太松会散）。
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
