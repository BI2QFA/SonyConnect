package com.bi2qfa.sonyconnect.ptpip

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.bi2qfa.sonyconnect.data.IdentityRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.math.min
























object Discovery {

    private const val TAG = "Discovery"

    
    val CANDIDATE_PORTS = intArrayOf(15740, 15741, 25740, 25741)

    
    private const val SWEEP_TIMEOUT_MS = 420L

    
    private const val FAST_TIMEOUT_MS = 260L

    
    private const val QUIET_MS = 150L

    
    private const val RECV_SLICE_MS = 50

    
    data class DiscoveredCamera(
        val host: String,
        
        val protoPort: Int,
        
        val filePort: Int,
        val cameraGuid16: ByteArray,
        
        val name: String,
        
        val pairingMode: Boolean,
    ) {
        val guidHex: String get() = PtpCodec.hex(cameraGuid16)

        override fun equals(other: Any?): Boolean =
            other is DiscoveredCamera && cameraGuid16.contentEquals(other.cameraGuid16)

        override fun hashCode(): Int = cameraGuid16.contentHashCode()
    }

    













    suspend fun scan(
        context: Context,
        boundNetwork: Network? = null,
        preferredHosts: List<String> = emptyList(),
        thorough: Boolean = false,
        sweep: Boolean = true,
    ): List<DiscoveredCamera> =
        withContext(Dispatchers.IO) {
            val (localIp, gateway) = wifiEndpoints(context)
            val myGuid = IdentityRepo.guid16()
            val myName = IdentityRepo.friendlyName + " " + PtpCodec.VENDOR_TAG
            val foundByGuid = LinkedHashMap<String, DiscoveredCamera>()
            val request = PtpCodec.probe(
                request = true,
                guid = myGuid,
                friendlyName = myName,
                protoPort = 0,
                filePort = 0,
                pairingMode = false,
                paired = false,
            )
            val t0 = System.currentTimeMillis()
            var sweepMs = 0L

            DatagramSocket().use { sock ->
                sock.soTimeout = RECV_SLICE_MS
                boundNetwork?.let { n -> runCatching { n.bindSocket(sock) } }

                
                val known = LinkedHashSet<String>()
                preferredHosts.filter { it.isNotBlank() }.forEach { known.add(it) }
                gateway?.takeIf { it.isNotBlank() }?.let { known.add(it) }
                localIp?.takeIf { it.isNotBlank() }?.let { known.add(it) }
                if (known.isNotEmpty()) {
                    sendTo(sock, known, request)
                    collect(sock, foundByGuid, FAST_TIMEOUT_MS, stopOnFirst = !thorough)
                }

                
                
                
                if (sweep && foundByGuid.isEmpty()) {
                    val s0 = System.currentTimeMillis()
                    sendBroadcast(sock, localIp ?: gateway, request)
                    collect(sock, foundByGuid, SWEEP_TIMEOUT_MS, stopOnFirst = false)
                    sweepMs = System.currentTimeMillis() - s0
                }

                
                
                if (sweep && foundByGuid.isEmpty()) {
                    val rest = subnetOf(localIp ?: gateway).filterNot { known.contains(it) }
                    if (rest.isNotEmpty()) {
                        val s1 = System.currentTimeMillis()
                        sendTo(sock, rest, request)
                        collect(sock, foundByGuid, SWEEP_TIMEOUT_MS, stopOnFirst = false)
                        sweepMs += System.currentTimeMillis() - s1
                    }
                }
            }
            Log.d(
                TAG,
                "扫描完成：已知 ${preferredHosts.size} 条，命中 ${foundByGuid.size} 台，" +
                    "thorough=$thorough sweep=$sweep，耗时 ${System.currentTimeMillis() - t0} ms" +
                    if (sweepMs > 0) "（补扫占 $sweepMs ms）" else "",
            )
            foundByGuid.values.sortedByDescending { it.pairingMode }
        }

    
    private fun sendTo(sock: DatagramSocket, hosts: Collection<String>, request: ByteArray) {
        for (h in hosts) {
            for (port in CANDIDATE_PORTS) {
                runCatching {
                    sock.send(DatagramPacket(request, request.size, InetSocketAddress(h, port)))
                }
            }
        }
    }

    







    private fun sendBroadcast(sock: DatagramSocket, ip: String?, request: ByteArray) {
        runCatching { sock.broadcast = true }
        val targets = LinkedHashSet<String>()
        subnetBroadcastOf(ip)?.let { targets.add(it) }
        targets.add("255.255.255.255")
        sendTo(sock, targets, request)
    }

    
    private fun subnetBroadcastOf(ip: String?): String? {
        val p = ip?.split(".") ?: return null
        if (p.size != 4) return null
        return p[0] + "." + p[1] + "." + p[2] + ".255"
    }

    






    private suspend fun CoroutineScope.collect(
        sock: DatagramSocket,
        foundByGuid: MutableMap<String, DiscoveredCamera>,
        windowMs: Long,
        stopOnFirst: Boolean,
    ) {
        val deadline = System.currentTimeMillis() + windowMs
        var lastHit = 0L
        val buf = ByteArray(1500)
        while (isActive && System.currentTimeMillis() < deadline) {
            val pkt = DatagramPacket(buf, buf.size)
            val got = try {
                sock.receive(pkt)
                true
            } catch (e: Exception) {
                false   
            }
            if (!got) {
                if (lastHit != 0L && System.currentTimeMillis() - lastHit >= QUIET_MS) {
                    return
                }
                continue
            }
            val raw = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
            val cam = parse(raw, pkt.address?.hostAddress ?: continue) ?: continue
            foundByGuid[cam.guidHex] = cam
            lastHit = System.currentTimeMillis()
            if (stopOnFirst) return
        }
    }

    
    internal fun parse(raw: ByteArray, fromHost: String): DiscoveredCamera? = try {
        val m = PtpCodec.read(raw.inputStream())
        if (m == null || m.type != PtpCodec.T_PROBE_RESP) {
            null
        } else {
            val pr = PtpCodec.parseProbe(m.body)
            
            if (!PtpCodec.hasVendorTag(pr.name)) {
                null
            } else {
                DiscoveredCamera(
                    host = fromHost,
                    protoPort = if (pr.protoPort > 0) pr.protoPort else CANDIDATE_PORTS[0],
                    filePort = pr.filePort,
                    cameraGuid16 = pr.guid,
                    name = pr.name.removeSuffix(" " + PtpCodec.VENDOR_TAG).trim(),
                    pairingMode = pr.pairingMode,
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    
    
    

    private fun wifiEndpoints(context: Context): Pair<String?, String?> = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val dhcp = wm?.dhcpInfo
        if (dhcp == null) {
            null to null
        } else {
            formatIp(dhcp.ipAddress) to formatIp(dhcp.gateway)
        }
    } catch (e: Exception) {
        null to null
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
