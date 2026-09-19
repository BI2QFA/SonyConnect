package com.bi2qfa.sonyconnect.protocol

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

/**
 * 局域网设备扫描：向子网内各地址 2122 端口发心跳请求，有应答即为 SonyConnect
 * 相机（协议应答自带 app 标识，天然防误报）。无定位权限、不读 SSID。
 *
 * 子网推导：WiFi DHCP 的本机 IP + 网关（相机热点模式网关即 192.168.122.1）。
 * 并发 50 × 连接超时 250ms，/24 一轮约 2 秒。
 */
object Scanner {

    data class Candidate(
        val host: String,
        val model: String,
        val serial: String,
        val lens: String,
        val batteryPct: Int,
    )

    suspend fun scan(context: Context): List<Candidate> = withContext(Dispatchers.IO) {
        val (localIp, gateway) = wifiEndpoints(context)
        val hosts = LinkedHashSet<String>()
        gateway?.let { hosts.add(it) }
        localIp?.let { hosts.add(it) }
        hosts.addAll(subnetOf(localIp ?: gateway))
        if (hosts.isEmpty()) return@withContext emptyList()

        coroutineScope {
            hosts.sorted().map { host ->
                async {
                    probe(host)?.let {
                        Candidate(
                            host = host,
                            model = it.model.ifEmpty { "Sony 相机" },
                            serial = it.serial,
                            lens = it.lens,
                            batteryPct = it.batteryPct,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** TCP 2122 探活 + 心跳应答即命中，顺手取一次 INFO */
    private fun probe(host: String): ConnectClient.CameraInfo? {
        var ok = false
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, 2122), 250)
                ok = true
            }
        } catch (e: Exception) {
            return null
        }
        if (!ok) return null
        // 连接复用 ConnectClient：借其握手与请求通道做一次 INFO
        val prev = ConnectClient.boundNetwork
        return try {
            if (ConnectClient.connect(host)) {
                ConnectClient.info()
            } else null
        } finally {
            ConnectClient.boundNetwork = prev
            ConnectClient.close()
        }
    }

    private fun wifiEndpoints(context: Context): Pair<String?, String?> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null to null
            val dhcp = wm.dhcpInfo ?: return null to null
            val ip = formatIp(dhcp.ipAddress)
            val gw = formatIp(dhcp.gateway)
            ip to (gw?.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            null to null
        }
    }

    /** 本机 IP → /24 全量地址（不含 .0 与 .255） */
    private fun subnetOf(localIp: String?): List<String> {
        if (localIp == null) return emptyList()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptyList()
        val b = min(254, parts[2].toIntOrNull() ?: return emptyList())
        val prefix = "${parts[0]}.${parts[1]}.$b."
        return (1..254).map { prefix + it }
    }

    /** Android DHCP 的 int 地址是小端：低字节在前 */
    fun formatIp(ip: Int): String? {
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
