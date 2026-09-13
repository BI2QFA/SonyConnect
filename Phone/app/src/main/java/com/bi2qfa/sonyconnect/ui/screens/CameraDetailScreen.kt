package com.bi2qfa.sonyconnect.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.ui.components.SectionHeader
import com.bi2qfa.sonyconnect.ui.components.SettingsGroup
import com.bi2qfa.sonyconnect.ui.components.SettingsRow

















@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraDetailScreen() {
    val info = DeviceStore.info
    
    val target = ConnectionCenter.displayGuid()?.let { PairingStore.find(it) }

    val model = info?.model?.takeIf { it.isNotBlank() }
        ?: target?.peerModel?.takeIf { it.isNotBlank() }
        ?: target?.peerName?.takeIf { it.isNotBlank() }
        ?: "—"
    val serial = info?.serial?.takeIf { it.isNotBlank() }
        ?: target?.peerSerial?.takeIf { it.isNotBlank() }
        ?: "—"

    val region = info?.region?.takeIf { it.isNotBlank() } ?: "—"
    val firmware = info?.firmware?.takeIf { it.isNotBlank() } ?: "—"
    val apiVersion = info?.apiVersion?.takeIf { it.isNotBlank() } ?: "—"

    
    
    val androidVersion = info?.androidVersion?.takeIf { it.isNotBlank() }
    val androidSdk = info?.androidSdk ?: -1
    val androidText = when {
        androidVersion != null && androidSdk > 0 -> "$androidVersion（SDK $androidSdk）"
        androidVersion != null -> androidVersion
        androidSdk > 0 -> "SDK $androidSdk"
        else -> "—"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            
            .padding(bottom = 28.dp),
    ) {
        SectionHeader("设备")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 4,
                title = "型号",
                trailing = { SpecValue(model) },
            )
            SettingsRow(
                index = 1, count = 4,
                title = "序列号",
                trailing = { SpecValue(serial) },
            )
            SettingsRow(
                index = 2, count = 4,
                title = "地区",
                trailing = { SpecValue(region) },
            )
            SettingsRow(
                index = 3, count = 4,
                title = "固件版本",
                trailing = { SpecValue(firmware) },
            )
        }

        SectionHeader("系统")
        SettingsGroup {
            SettingsRow(
                index = 0, count = 2,
                title = "安卓版本",
                trailing = { SpecValue(androidText) },
            )
            SettingsRow(
                index = 1, count = 2,
                title = "Java API 版本",
                trailing = { SpecValue(apiVersion) },
            )
        }

        
        Box(Modifier.padding(bottom = 8.dp))
    }
}


@Composable
private fun SpecValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
