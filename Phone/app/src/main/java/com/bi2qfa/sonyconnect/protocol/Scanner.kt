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

    private fun subnetOf(localIp: String?): List<String> {
        if (localIp == null) return emptyList()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptyList()
        val b = min(254, parts[2].toIntOrNull() ?: return emptyList())
        val prefix = "${parts[0]}.${parts[1]}.$b."
        return (1..254).map { prefix + it }
    }

    fun formatIp(ip: Int): String? {
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
