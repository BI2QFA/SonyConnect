package com.bi2qfa.sonyconnect.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * "下载目录不可用"弹窗（用户定版：点开始传输时预检，目录不存在就弹这个、文件不进队列）。
 *
 * <p>两个入口共用：文件页的「开始传输」FAB 与传输页的开始按钮。文案里的目录名
 * 取 [com.bi2qfa.sonyconnect.data.SettingsRepo.downloadDirLabel] 的友好显示名。
 *
 * @param dirLabel 友好目录名（空 = 应用默认目录）
 * @param onPickDir "选择目录"：**直接拉起系统目录选择器**（用户定版：不再绕设置页）
 * @param onDismiss 取消/关闭
 */
@Composable
fun DownloadDirMissingDialog(
    dirLabel: String,
    onPickDir: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("下载目录不可用") },
        text = {
            Text(
                buildString {
                    append("当前下载目录")
                    if (dirLabel.isNotBlank()) append("「").append(dirLabel).append("」")
                    append("不存在或没有写入权限，请重新选择下载目录")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onPickDir()
            }) { Text("选择目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
