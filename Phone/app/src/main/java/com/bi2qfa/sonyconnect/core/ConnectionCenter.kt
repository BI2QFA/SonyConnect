package com.bi2qfa.sonyconnect.core

import android.content.Context
import android.net.Network
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.bi2qfa.sonyconnect.data.CameraInfo
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.IdentityRepo
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ptpip.Discovery
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.ptpip.PtpCodec
import com.bi2qfa.sonyconnect.ptpip.PtpIpClient
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 连接状态机：未连接 → 扫描中 → 已连接（PTP/IP 版）。
 *
 * 与旧版（FTP + 私有 TCP）的对应关系：
 * | 旧 | 新 |
 * |---|---|
 * | `Scanner.scan` TCP 2122 探活 | [Discovery.scan] UDP Probe（与协议端口同号） |
 * | `FtpRepository.connect` | [ObjectRepository.connect]（PTP/IP 握手 + 双向认证） |
 * | `ConnectClient.info()` | `OP_DEVICE_INFO` → [CameraInfo]（**只在连接时拉一次**） |
 * | `ConnectClient.heartbeat()` | `OP_PING`（**唯一的实时包**：电量 + 镜头名同包） |
 * | `ConnectClient.thumbBegin()` | `OP_THUMB_QUEUE_BEGIN` |
 *
 * 连接后行为：拉**一次**全量设备信息（型号/序列号/固件/模式/SSID，这些不会变）
 * → 启动 3s 心跳（电量与镜头都在这个包里，不再有第二个 5s 定时器）
 * → 提交缩略图预取批次；心跳连续 3 次失联即断开并收走全部连接。
 *
 * **强制配对**：未配对的相机一律连不上，必须先走 [requestPair] / [submitPairCode]。
 * 这是定案决策 —— 不再有任何"开发用固定密钥"回落（那种开关迟早被误留到发布版里）。
 */
object ConnectionCenter {

    private const val TAG = "ConnectionCenter"

    /** ★ 只有两态："正在扫描/正在连接"由 `scanning` / `connecting` 两个 boolean 表达，
     *  早先的 SCANNING 枚举项从来没被写过（清死代码时删掉）。 */
    enum class State { DISCONNECTED, CONNECTED }

    const val HEARTBEAT_INTERVAL_MS = 3000L
    /** 自动扫描单轮时长上限（用户定版：持续扫描超过半分钟报"未找到设备"） */
    private const val AUTO_SCAN_TIMEOUT_MS = 30_000L
    /** 心跳连续失联上限（协议设计定版 3 次：切后台被限网/冻结的瞬断不一票断开） */
    private const val HEARTBEAT_MAX_MISSES = 3

    /** 进软件自动连接：最多试几轮扫描（相机刚开机时 Wi-Fi 还没起来，一轮就报"未找到"太武断） */
    private const val AUTO_CONNECT_ROUNDS = 3
    private const val AUTO_CONNECT_RETRY_MS = 1500L

    var state by mutableStateOf(State.DISCONNECTED)
        private set

    var scanning by mutableStateOf(false)
        private set

    /** 正在进行连接握手（期间自动扫描循环挂起，防重复发起） */
    var connecting by mutableStateOf(false)
        private set

    /** 扫描结果（含 guid / 双端口 / 配对状态，供配对页展示） */
    var scanResults by mutableStateOf<List<Discovery.DiscoveredCamera>>(emptyList())
        private set

    var host by mutableStateOf<String?>(null)
        private set

    /** 当前连接的相机（含 guid / 双端口；未连接为 null） */
    var camera by mutableStateOf<Discovery.DiscoveredCamera?>(null)
        private set

    /**
     * 本次要连 / 正在连的相机设备码（未连接时主界面靠它显示"正在连接 xxx"）。
     *
     * 与 [camera] 分开是有意的：`camera` 只在**成功**之后才有值，而主界面在
     * "正在连接"和"未连接（可重连）"两种状态下都得知道用户点的是哪一台 ——
     * 否则那两行只能显示"未连接"而说不出是哪台相机没连上。
     */
    var targetGuid by mutableStateOf<String?>(null)
        private set

    var boundNetwork: Network? = null
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    // ===== 配对状态（UI 据此弹输码框） =====

    /**
     * 正在配对的相机；null = 没在进行配对。
     *
     * <p>★ 它从**点下那台相机**起就有值（那一刻开始连相机、等它亮码），不是"输码框弹出来
     * 才有值"—— 走了哪一步看 [pairingStage]。
     */
    var pairingTarget by mutableStateOf<Discovery.DiscoveredCamera?>(null)
        private set

    /** 配对失败原因（可直接上屏）；成功或取消后清空。 */
    var pairingError by mutableStateOf<String?>(null)
        private set

    private var heartbeatJob: Job? = null
    private var autoScanJob: Job? = null
    private var autoConnectJob: Job? = null
    private lateinit var scope: CoroutineScope
    private var appContext: Context? = null
    fun appContext(): Context? = appContext

    /**
     * 事件连接回调：相机主动推过来的消息。
     *
     * - `EV_THUMB_PROGRESS`：缩略图进度（当前只记日志）
     * - `EV_PAIRED_REMOVED`：相机在配对页把本机删了 —— 已连接时这就是"解除配对"
     *   送达对方的路径，收到必须清本地记录并断开当前会话
     * - `EV_APP_EXITING`：相机端正在退出 —— 会话马上就不存在了，收到即断；靠心跳
     *   失联发现要等三次 ≈ 9 秒，界面这段时间还写着"已连接"
     * - `EV_MODE_SWITCHING`：相机改连接方式了 —— 同样要断（无线电要重配），
     *   但给用户的话不一样（见 [onCameraModeSwitching]）
     * - `EV_DISCONNECTING`：相机主动断开的**通用声明**（进配对模式、服务异常…），
     *   见 [onCameraDisconnecting]
     * - `EV_REC`：遥控拍摄状态（对焦 / 录像计时等）
     *
     * ★ 用户定版："相机端协议应针对所有相机端主动断连的行为做出声明，在手机端显示；
     *   如果属于意外断连（也就是**没收到任何声明**），则统一按失去与相机的连接处理。"
     *   所以这里每一条事件都要**先置 [cameraDeclaredDisconnect]**，再走各自的断开路径 ——
     *   [disconnect] 靠这个标志区分"有交代的断"与"意外掉线"。
     */
    private val eventListener = PtpIpClient.EventListener { code, txId, params ->
        Log.d(TAG, "event code=$code tx=$txId n=${params?.size ?: 0}")
        when (code) {
            PtpCodec.EV_PAIRED_REMOVED -> onPairRemovedByCamera()
            PtpCodec.EV_APP_EXITING -> onCameraExiting()
            PtpCodec.EV_MODE_SWITCHING -> onCameraModeSwitching(params)
            PtpCodec.EV_REC -> {
                val a = params?.getOrNull(0) ?: 0
                val b = params?.getOrNull(1) ?: 0
                val c = params?.getOrNull(2) ?: 0
                RecController.onRecEvent(a, b, c)
            }
            PtpCodec.EV_DISCONNECTING -> onCameraDisconnecting(params)
        }
    }

    /**
     * 相机**声明过**这次断开的原因（用户定版：主动断连必须声明）。
     *
     * <p>在事件回调里置位，由 [disconnect] 读取；任何一次新连接开始时清零。
     * 它的唯一用途是：区分"相机说了为什么断"（照实显示那句话）与"一声不吭断了"
     * （统一按"失去与相机的连接"处理）。
     */
    @Volatile
    private var cameraDeclaredDisconnect = false

    /**
     * 相机主动断开的通用声明（进配对模式 / 服务异常 / 其它）。
     *
     * <p>与 [onCameraExiting] / [onCameraModeSwitching] 是并列的三条"有交代的断"：
     * 那两条各自表达更具体的语义（退出、换方式），这一条补的是它们没覆盖到的原因。
     * 处理好它们**没覆盖到**的两种，其余给一句中性说法。
     */
    private fun onCameraDisconnecting(params: IntArray?) {
        if (!::scope.isInitialized) return
        scope.launch {
            val reason = params?.firstOrNull() ?: PtpCodec.DISC_REASON_OTHER
            Log.d(TAG, "相机声明主动断开（reason=$reason）")
            when (reason) {
                PtpCodec.DISC_REASON_PAIRING ->
                    disconnect("相机已进入配对模式，连接已断开")
                PtpCodec.DISC_REASON_ERROR ->
                    disconnect("相机服务出现异常，连接已断开")
                else ->
                    disconnect("相机已断开连接")
            }
        }
    }

    /**
     * 相机端退出了（真正退出软件时的最后一条事件）。
     *
     * 只断开当前会话，**不动配对表**：相机退出不改配对关系，下次进软件还得自动连它。
     */
    private fun onCameraExiting() {
        if (!::scope.isInitialized) return
        scope.launch {
            Log.d(TAG, "相机端已退出，收掉当前会话")
            cameraDeclaredDisconnect = true
            disconnect("相机端已退出")
        }
    }

    /**
     * 相机改了连接方式（相机端"连接设置"里换了方式，切换**开始前**推来的事件）。
     *
     * 处理与 [onCameraExiting] 一样是收掉会话 —— 换方式必须重配无线电，本机这条链路
     * 无论如何都会断，早断一步还能立刻把话说清楚。差别只在**给用户的那句话**：
     * 说"相机端已退出"是假话（用户会跑去相机上看它是不是真关了，其实相机开得好好的、
     * 屏幕上写着"正在切换模式···"）。
     *
     * ★ 两个方向**用同一句话**（用户定版）："相机切换了连接模式，请重新连接"。
     *   原来按参数分"切成相机热点"/"切成 Wi-Fi 网络"两种说法，但用户要做的动作
     *   一模一样（重新连一次），而"热点 / Wi-Fi 网络"这两个词对用户只是术语 ——
     *   参数仍然收着，只写进日志备查。
     */
    private fun onCameraModeSwitching(params: IntArray?) {
        if (!::scope.isInitialized) return
        scope.launch {
            val mode = params?.firstOrNull()
            Log.d(TAG, "相机切换连接方式（mode=$mode）")
            cameraDeclaredDisconnect = true
            disconnect("相机切换了连接模式，请重新连接")
        }
    }

    /**
     * 相机解除了与本机的配对（相机端在配对页删的，经事件通道送达）。
     *
     * 在事件读线程上被调用，所以这里只做一次跳线程：清记录与断开都归 scope 那侧
     * （Compose 状态、TransferStore 都由它管，别在线程上乱写）。
     */
    private fun onPairRemovedByCamera() {
        if (!::scope.isInitialized) return
        val g = camera?.guidHex ?: return
        scope.launch {
            Log.d(TAG, "相机解除了本机配对，清本地记录：$g")
            PairingStore.remove(g)
            cameraDeclaredDisconnect = true
            disconnect("相机已解除与本机的配对")
        }
    }

    fun init(scope: CoroutineScope, context: Context) {
        this.scope = scope
        this.appContext = context.applicationContext
    }

    // ===== 网络绑定（Wi-Fi + 移动数据双开连接问题的根治） =====

    /**
     * 把相机会话钉到 Wi-Fi 网络：Android/MIUI 会把"无互联网"的相机
     * Wi-Fi 判为劣质网络，双开时把流量甩到蜂窝 —— 所以要把 socket 与整个进程
     * 都绑到相机那条链路上。断开时 [unpinNetwork] 解绑。
     *
     * <h3>选哪条 Wi-Fi（★ 2026-09-19 修正）</h3>
     *
     * 原来只做一件事：**取 `allNetworks` 里第一个 `TRANSPORT_WIFI`**。这在
     * "手机同时开着相机热点 + 家路由器 Wi-Fi"时会绑错 —— 而这两种网络在我们的
     * 使用场景里**必然同时存在**（相机热点本身没有互联网，手机通常保留着家里的 Wi-Fi）。
     * 绑错的表现是探测包发不出去、用户看到"找不到相机"，而相机其实好好的。
     *
     * 现在按**目标地址**选：
     * <ul>
     *   <li>给了 [targetHost]（连接/重连某台已知相机）→ 逐条 Wi-Fi 网络问
     *       "这个地址你路由得到吗"，取能路由的那条；</li>
     *   <li>没给（首次发现/扫描）→ 优先挑**本地地址与某个候选主机同网段**的那条
     *       （相机热点是 `192.168.122.x` 这种固定私有段），挑不出就退回"第一条 Wi-Fi"。</li>
     * </ul>
     * 最后那条回退是必要的：拿不到任何可用信息时，"绑一条 Wi-Fi"总好过"绑到蜂窝"。
     *
     * @param targetHost 要连的相机地址；扫描时传 null（那时还不知道目标是哪台）
     * @param probeHosts 扫描时用于"同网段判断"的候选地址（配对表里记过的相机 IP + 网关）
     */
    private fun pinWifiNetwork(
        context: Context,
        targetHost: String? = null,
        probeHosts: List<String> = emptyList(),
    ) {
        val net = try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            var found: Network? = null
            if (cm != null) {
                val wifiNets = cm.allNetworks.filter { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_WIFI,
                    ) == true
                }
                found = when {
                    // ① 有明确目标：挑"本地地址与目标同 /24"的那条网络
                    targetHost != null ->
                        wifiNets.firstOrNull { sameSubnet(it, cm, targetHost) }
                            ?: wifiNets.firstOrNull()

                    // ② 扫描阶段：挑与候选主机同网段的那条（相机热点网段认得出来）
                    probeHosts.isNotEmpty() ->
                        wifiNets.firstOrNull { n -> probeHosts.any { sameSubnet(n, cm, it) } }
                            ?: wifiNets.firstOrNull()

                    else -> wifiNets.firstOrNull()
                }
                runCatching { cm.bindProcessToNetwork(found) }
            }
            found
        } catch (e: Exception) {
            null
        }
        boundNetwork = net
        ObjectRepository.boundNetwork = net
    }

    /**
     * 这条网络的本地地址与 [host] 是否同 /24。
     *
     * <p>为什么用同网段而不是 `canReach()`：`canReach` 依赖系统的路由表判断，
     * 在"相机热点还没被系统认成可用网络"的那几秒里会返回 false（而那时它恰恰是
     * 唯一能到相机的链路）。同网段比较只看本地地址，不依赖系统路由状态，更可靠。
     *
     * <p>只比 /24：相机热点与家路由器都是标准 /24，本项目不做跨网段连接。
     */
    private fun sameSubnet(network: Network, cm: android.net.ConnectivityManager, host: String): Boolean {
        val localIp = runCatching {
            cm.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address }
                ?.address?.hostAddress
        }.getOrNull() ?: return false
        val a = localIp.split(".")
        val b = host.split(".")
        if (a.size != 4 || b.size != 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }

    /** 断开时解除进程级绑定，恢复系统默认选网 */
    private fun unpinNetwork() {
        try {
            val c = appContext ?: return
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.let { runCatching { it.bindProcessToNetwork(null) } }
        } catch (e: Exception) {
            // 解绑失败无补救动作
        }
        boundNetwork = null
        ObjectRepository.boundNetwork = null
    }

    // ===== 扫描 =====

    /**
     * **配对扫描**（配对页专用）：进页面 / 手动重扫都走它。
     *
     * 只列**正开着配对窗口**的相机（探测应答里的 `pairingMode`，不是靠名字过滤）：
     * 没开窗口的相机根本没有码可核对，列出来只会让用户输完码才发现相机不收 ——
     * 用户定版"配对界面仅保留配对模式"的另一半就是这里：扫到的必须真的能配。
     *
     * 旧版这里还有个"连接模式"（列没开窗口的相机），已随配对页重写一起删掉：
     * 现在**进软件自动连上次那台**，不再需要让用户从列表里挑一台来连。
     */
    fun startPairingScan(context: Context, force: Boolean = false) {
        // ★★ 手动扫描**终止正在跑的"自动连上次那台"**（用户两次点名）：
        //   进软件自动连不上时的三轮扫描（第三轮补扫整个 /24 要 9 秒多）把
        //   `connecting` 顶住，配对页点"扫描可配对相机"静默 return —— 用户要
        //   等它自己超时才有反应。现在 force 扫描直接取消那个任务（此时它还在
        //   扫描/重试延时里，没进握手，取消是安全的；万一切进握手，connectTo
        //   的异常路径自己会 rollback），并把 connecting 复位。
        if (force && autoConnectJob?.isActive == true) {
            autoConnectJob?.cancel()
            connecting = false
        }
        // 注意**不是** `state != DISCONNECTED` 就返回：已连着一台时也能从设置进配对页
        // 再配第二台（用户定版：悬浮窗里要能切设备，前提就是有多台已绑定）。
        // force 已把自动连接让位；非 force（进页面自动扫）撞上 connecting 仍让路。
        if (connecting) return
        if (autoScanJob?.isActive == true) {
            if (!force) return
            autoScanJob?.cancel()
        }
        autoScanJob = scope.launch {
            val deadline = System.currentTimeMillis() + AUTO_SCAN_TIMEOUT_MS
            scanning = true
            scanResults = emptyList()
            lastError = null
            while (!connecting && System.currentTimeMillis() < deadline) {
                val found = try {
                    // 扫描阶段还不知道目标是哪台 → 用配对表里的相机 IP + 网关做同网段判断
                    pinWifiNetwork(context, probeHosts = knownCameraHosts())
                    // force=手动重扫：要列全（不早退），否则第二台待配对的相机永远看不见
                    Discovery.scan(context, boundNetwork, knownCameraHosts(), thorough = force)
                        .filter { it.pairingMode }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "scan failed: ${e.message}")
                    null
                }
                if (connecting) break
                // ★ 新扫描请求已把本任务取消时，老任务**到此为止**：
                //   不许把扫到的旧结果盖到新一轮头上（用户实测：扫描中再点扫描没反应，
                //   现在点 = 终止老的重新扫，这里保证两轮不打架）
                ensureActive()
                scanResults = found ?: emptyList()
                // 扫到可配对相机就停下展示列表：配哪个由用户自己挑
                if (!found.isNullOrEmpty()) break
                val remain = deadline - System.currentTimeMillis()
                if (remain > 0) delay(minOf(3000L, remain))
            }
            scanning = false
            // 超时且一无所获：报告原因，不再自动扫描
            if (!connecting && scanResults.isEmpty()) {
                lastError = "未发现处于配对模式的相机"
            }
        }
    }

    /**
     * 配对表里记录过的相机地址，交给探测排在最前面。
     * 相机（尤其热点模式）的地址基本固定，绝大多数情况下第一轮就能命中，
     * 不必等整段 /24 扫完 —— 这是"进软件后干等 30 秒"的主要来源。
     */
    private fun knownCameraHosts(): List<String> =
        PairingStore.all().mapNotNull { it.lastIp?.takeIf { ip -> ip.isNotBlank() } }.distinct()

    // ===== 自动连接上次那台（多设备适配的入口） =====

    /**
     * "上一次最后连接过的设备"的设备码。
     *
     * 优先用持久化的 [SettingsRepo.lastConnectedDevice]；它为空或那条记录已被解除配对时，
     * 退到配对表里最近联系过的那台（`lastSeenAt` 最新的）。
     */
    private fun lastTargetGuid(): String? {
        val saved = SettingsRepo.lastConnectedDevice
        if (saved.isNotBlank() && PairingStore.contains(saved)) return saved
        return PairingStore.mostRecent()?.peerDeviceId
    }

    /**
     * 主界面要显示 / 要连的那台相机的设备码（连着的是它，否则是本次的目标）。
     *
     * ★ 末尾的 `?: lastTargetGuid()` 是**启动兜底**：`targetGuid` 要等自动连接跑起来
     *   才置位，而用户点名的场景是"启动刚完成就要显示真实型号"—— 没连上时直接退到
     *   "上次那台"，主界面/顶栏/面板高亮在第一帧就有正确的设备可指。
     */
    fun displayGuid(): String? = camera?.guidHex ?: targetGuid ?: lastTargetGuid()

    /**
     * 让**传输队列**跟着"当前选中哪台设备"走（用户定版：传输列表里的一切都按设备码隔离）。
     *
     * ★ 关键点：切队列**不能只在连接成功时发生**。相机没开机/连不上时用户照样会在悬浮窗里
     *   选设备 —— 那时标题（取自 displayGuid）已经换成新那台，而列表若还停在上一台的任务上，
     *   看上去就是"三个设备共用一份列表，根本没隔离"（用户实测反馈）。
     *   所以选中即切队列：`/DCIM/100MSDCF/DSC00001.ARW` 这种路径两台相机上完全一样，
     *   列表错位不只是观感问题，还会让"开始传输"拿 A 的任务去 B 上取文件。
     */
    private fun selectQueue(guidHex: String?) {
        guidHex?.takeIf { it.isNotBlank() }?.let { TransferStore.onCameraChanged(it) }
    }

    /** 主界面上那台相机该怎么称呼（"ILCE-6300 SN:05186914"）。 */
    fun labelOf(guidHex: String?): String {
        val p = guidHex?.let { PairingStore.find(it) } ?: return "相机"
        val model = p.peerModel.ifBlank { p.peerName }
        if (model.isBlank()) return p.peerDeviceId
        return if (p.peerSerial.isNotBlank()) "$model SN:${p.peerSerial}" else model
    }

    /**
     * 设备短码：设备码前 8 个 hex 字符（如 `a1339c31`）。
     *
     * ★ 必须露出来的一截：设备码是 8 字节 + 8 字节补齐（后 16 个 hex 恒为 0），
     *   而"型号 + SN"是**机身属性**，同一个机身重装/清过相机端数据就会重新生成设备码，
     *   配对表里于是留下多条**型号+SN 完全一样**的记录。用户实测就是三条一模一样的
     *   "ILCE-6300 05186914" —— 只写型号和 SN，人分不清哪条是哪条，也看不出队列、
     *   缩略图缓存、下载目录其实是按设备码分开的。
     */
    fun shortCode(guidHex: String?): String =
        guidHex.orEmpty().trim().lowercase().take(8)

    /**
     * 按设备码扫一台相机；找不到返回 null。认人只认设备码不认地址（Wi-Fi 下地址会变）。
     *
     * @param sweep 要不要在"已知地址没命中"时补扫整个 /24。**这一扫实测要 9.3 秒**
     *   （往 251 个没人应答的地址发 1004 个小包，发送本身阻塞，详见 [Discovery.scan]），
     *   所以只有"地址可能变了"的场合才传 true：常规连接（相机地址没变）0.3 秒就命中。
     */
    private suspend fun scanFor(
        context: Context,
        guidHex: String,
        thorough: Boolean,
        sweep: Boolean = true,
    ): Discovery.DiscoveredCamera? =
        try {
            val knownIp = PairingStore.find(guidHex)?.lastIp?.takeIf { it.isNotBlank() }
            // 目标是这台相机 → 按它的地址选网络（多 Wi-Fi 环境下才不会绑错）
            pinWifiNetwork(context, targetHost = knownIp)
            // 目标那台记录过的地址排在最前：绝大多数情况第一轮就命中
            val prefer = listOfNotNull(knownIp)
            Discovery.scan(context, boundNetwork, prefer, thorough = thorough, sweep = sweep)
                .firstOrNull { it.guidHex == guidHex }
        } catch (e: Exception) {
            Log.d(TAG, "scanFor failed guid=$guidHex: ${e.message}")
            null
        }

    /**
     * 进软件就自动连上"上次那台"（用户定版）——主界面直接进入那台设备并自动连接。
     *
     * 三轮：**前两轮只打已知地址**（各 0.3 秒，相机开机后联网通常几秒内就能命中），
     * 第三轮才补扫整个子网（那一轮要 9 秒多）。这样"相机就在原地"的常规情况一秒内连上，
     * 只有地址真的变了才付那 9 秒；三轮都没命中才报"未找到"，界面留在主界面给"重新连接"。
     */
    fun autoConnectLast(context: Context) = startConnect(context, lastTargetGuid())

    /**
     * 重连（主界面"重新连接"按钮、切换设备失败后再试）。
     *
     * 先用 [targetGuid]：用户刚在悬浮窗里点了哪台，就该重试哪台 —— 只有没有目标时
     * 才退回配对表里最近联系过的那台。
     */
    fun reconnect(context: Context) = startConnect(context, targetGuid ?: lastTargetGuid())

    private fun startConnect(context: Context, guid: String?) {
        if (state != State.DISCONNECTED || connecting) return
        if (guid.isNullOrBlank()) return
        // 已经有一轮在跑就别叠加：叠加会让多轮扫描互相抢 UDP 套接字，反而更慢
        if (autoConnectJob?.isActive == true) return
        targetGuid = guid
        lastError = null
        selectQueue(guid)   // 队列跟着选中走（进软件自动连的那台就是它的队列）
        autoConnectJob = scope.launch {
            connecting = true
            var target: Discovery.DiscoveredCamera? = null
            for (round in 0 until AUTO_CONNECT_ROUNDS) {
                target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 最后一轮才补扫全子网（9 秒级）；前几轮只打已知地址（0.3 秒级）
                    scanFor(context, guid, thorough = false, sweep = round == AUTO_CONNECT_ROUNDS - 1)
                }
                if (target != null) break
                // 用户在这几秒里点了别的设备：放弃这次，别再报"没找到上一台"
                if (targetGuid != guid) {
                    connecting = false
                    return@launch
                }
                delay(AUTO_CONNECT_RETRY_MS)
            }
            connecting = false
            if (targetGuid != guid) return@launch
            if (target == null) {
                lastError = "未找到 ${labelOf(guid)}，请确认相机端 SonyConnect 服务正常运行并与本机在同一网络"
                return@launch
            }
            connectTo(context, target)
        }
    }

    // ===== 连接 =====

    /**
     * 切换到另一台**已配对**相机（悬浮窗"已绑定设备"里点一行）。
     *
     * **选中是立刻的、连接是异步的**（用户定版：点哪台就立刻切到哪台的主界面）：
     * `targetGuid` 在**第一行**就置位 —— 悬浮窗的高亮与主界面标题当场跟着走，
     * 不等扫描与握手。扫描完成后再复核一次 targetGuid，用户在扫描期间改了主意就
     * 放弃这次（否则会把界面拉回他没选的那台）。
     *
     * ★★ **选中就落盘**（用户定版："无论连接成没成功，只要选择了这个设备，
     *   退出软件下次都自动回到这个设备"）。以前只在**握手成功**时才记
     *   （见 [connectTo]），于是"选了 B 但 B 没开机 → 退出 → 下次又回到 A" ——
     *   用户的意图明明是 B。所以这里在第一行就持久化，与连接结果解耦。
     *   落盘失败也不影响本次切换（只是个下次启动的目标）。
     *
     * 顺序：先收掉当前会话（相机端同一时刻只服务一台手机，必须让位）→ 扫一轮按
     * **设备码**认人 → 连接。认人只认设备码不认地址：Wi-Fi 模式下相机地址由 DHCP
     * 分配、会变，而设备码不变 —— 这也是"按设备切换"能成立的前提。
     *
     * 已连着这台时只是"回它的主界面"（调用方负责切页签）；**正在握手中**点击不
     * 立刻发起第二次握手（相机只认一条控制连接，两条会互相踢），但选中照样先移过去，
     * 那一次握完会接力切到新选的那台（见 [connectTo] 末尾）。
     */
    fun switchTo(context: Context, guidHex: String) {
        if (guidHex.isBlank()) return
        targetGuid = guidHex
        SettingsRepo.updateLastDevice(guidHex)   // 选中即落盘，不等连接结果
        selectQueue(guidHex)   // 队列当场跟着选中走（不等连接结果）
        if (camera?.guidHex == guidHex && state == State.CONNECTED) return
        if (connecting) return
        scope.launch {
            lastError = null
            autoConnectJob?.cancel()
            if (state == State.CONNECTED) disconnect()
            connecting = true
            // 先快扫（只打记录过的地址，0.3 秒级）：用户点"切换"要的是立刻有反应
            var target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                scanFor(context, guidHex, thorough = false, sweep = false)
            }
            // 没命中才补扫整个子网（9 秒级）——相机换了 IP 只有这一条路能找回来
            if (target == null && targetGuid == guidHex) {
                target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanFor(context, guidHex, thorough = true, sweep = true)
                }
            }
            connecting = false
            // 扫描期间用户又点了一台：这次作废（新那次已经在跑或即将触发）
            if (targetGuid != guidHex) {
                Log.d(TAG, "扫描期间目标改为 $targetGuid，放弃这次切换")
                return@launch
            }
            if (target == null) {
                lastError = "未找到 ${labelOf(guidHex)}（请确认相机端 SonyConnect 服务正常运行并与本机在同一网络）"
                return@launch
            }
            connectTo(context, target)
        }
    }

    /**
     * 连接到指定相机：PTP/IP 握手 + 双向认证 → 拉全量设备信息 → 心跳保活 →
     * 提交缩略图批次。任何一步失败都完整回滚到未连接。
     * connecting 期间自动扫描循环挂起，防止重复发起。
     */
    fun connectTo(context: Context, cam: Discovery.DiscoveredCamera) {
        if (state == State.CONNECTED || connecting) return
        lastError = null   // 入口先清：否则上次的"配对码不正确"会一直挂在界面上
        targetGuid = cam.guidHex   // 主界面"正在连接 xxx"要立刻有东西可显示
        scope.launch {
            connecting = true
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // 先钉住 Wi-Fi 网络（双开蜂窝时防甩网），再走 PTP/IP。
                    // 目标地址交给它选网：手机同时开着多个 Wi-Fi 时必须绑对那条。
                    pinWifiNetwork(context, targetHost = cam.host)
                    ObjectRepository.boundNetwork = boundNetwork

                    // 强制配对：配对表是唯一准入依据（相机端也这么判），未配对就引导去配对
                    if (!PairingStore.contains(cam.guidHex)) {
                        Log.d(TAG, "未配对，需要先配对：${cam.guidHex}")
                        lastError = "该相机尚未配对，请先配对"
                        return@withContext false
                    }

                    when (ObjectRepository.connect(
                        host = cam.host,
                        protoPort = cam.protoPort,
                        // ★★★ **本机自己的 guid16**，不是相机的（曾经错填 cam.cameraGuid16）：
                        //   PTP/IP 的 `Init Command Request` 里那个 GUID 是**发起方（手机）的身份**，
                        //   相机端 `serveControl` 拿它当"这台手机是谁"——查配对表、建会话、签发
                        //   传输令牌全用它。填成相机码的后果（实测）：所有手机在相机眼里都是同一个
                        //   身份（= 相机自己的设备码）→ 相机配对表里永远只有一条记录、名字随最后
                        //   连接者变；某台手机配对成功后，**其它手机**再来配对会被当成"已配对"而
                        //   拒绝握手（回 NOT_SUPPORTED，手机端报"相机端版本过旧"）。
                        guid16 = IdentityRepo.guid16(),
                        friendlyName = IdentityRepo.friendlyName,
                        onEvent = eventListener,
                    )) {
                        ObjectRepository.ConnectOutcome.OK -> Unit

                        ObjectRepository.ConnectOutcome.NOT_PAIRED -> {
                            // 相机侧已解除本机配对（本机记录是旧的）：**必须清掉本地记录**，
                            // 否则 UI 会因为"本地认为已配对"而永远只给"连接"按钮，用户怎么点
                            // 都连不上。清掉后界面自然回到"配对"入口。
                            Log.d(TAG, "相机已解除本机配对，清本地记录：${cam.guidHex}")
                            PairingStore.remove(cam.guidHex)
                            lastError = "相机已解除与本机的配对，请重新配对"
                            return@withContext false
                        }

                        ObjectRepository.ConnectOutcome.BUSY -> {
                            lastError = "相机正被其他设备占用（配对中或已连接），请稍后重试"
                            return@withContext false
                        }

                        ObjectRepository.ConnectOutcome.FAILED -> {
                            Log.d(TAG, "connect failed host=${cam.host}:${cam.protoPort}")
                            return@withContext false
                        }
                    }
                    // 先落占位信息，让 UI 立刻有相机名；随后被全量信息覆盖
                    DeviceStore.apply(
                        CameraInfo.placeholder(cam.guidHex, cam.name, cam.protoPort, cam.filePort)
                    )

                    // 全量设备信息：**整个会话只拉这一次**（型号/序列号/固件/模式/SSID
                    // 都不会变）。失败不致命，重连即可。
                    val info = pullDeviceInfo(cam)
                    if (info != null) {
                        DeviceStore.apply(info)
                        // ★ 回填配对表：老记录的型号/序列号可能是空的（配对那一刻
                        //   DEVICE_INFO 没拉到，此后常规重连只 touch 不补全）——
                        //   显示层虽然能推导，但正式值趁连接成功补进去才算治本。
                        PairingStore.find(cam.guidHex)?.let { p ->
                            val fillModel = p.peerModel.isBlank() && info.model.isNotBlank()
                            val fillSerial = p.peerSerial.isBlank() && info.serial.isNotBlank()
                            if (fillModel || fillSerial) {
                                PairingStore.upsert(
                                    p.copy(
                                        peerModel = if (fillModel) info.model else p.peerModel,
                                        peerSerial = if (fillSerial) info.serial else p.peerSerial,
                                    )
                                )
                            }
                        }
                    }

                    // ★★ 实时信息（电量/镜头）**必须放在最后**：它是 OP_PING 的产物，而
                    //   上面那份 DEVICE_INFO 快照里没有这两个字段 —— 顺序反了就会被清成未知，
                    //   界面上表现为"连上了还得等 3 秒心跳才出电量"（用户实测反馈）。
                    //   这就是"连接完成时立刻同步一次实时数据"那句话的落点。
                    applyLivePing(cam.host)

                    // 切到这台设备的传输队列（每台一套，见 TransferStore 的隔离说明），
                    // 顺带把"最近可见"与协议代次写进配对表（相机侧也会同步）
                    TransferStore.onCameraChanged(cam.guidHex)
                    ObjectRepository.setOnDisconnectedListener(cam.host) {
                        if (!::scope.isInitialized) return@setOnDisconnectedListener
                        scope.launch {
                            if (host == cam.host && state == State.CONNECTED) {
                                // ★ 用户定版：**没收到任何声明**的断开 = 意外断连，
                                //   统一按"失去与相机的连接"处理；有声明的那些走各自
                                //   的事件处理（它们已经在事件回调里置位并给出说法）。
                                disconnect(if (cameraDeclaredDisconnect) null else "失去与相机的连接")
                            }
                        }
                    }

                    // 刷新配对表"最近可见"与协议代次（相机侧也会同步）
                    PairingStore.touch(cam.guidHex, cam.host, cam.protoPort)
                    ObjectRepository.sessionOf(cam.host)?.let { s ->
                        PairingStore.updateProtoVersion(cam.guidHex, s.client.protoVersion)
                    }
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "connectTo exception: ${e.javaClass.simpleName} ${e.message}")
                    rollback()
                    false
                }
            }
            connecting = false
            if (ok) {
                camera = cam
                // ★ 这里**不重写** targetGuid："当前选中哪台"是用户的意图，不是连接结果 ——
                //   握手这几秒里他可能已经点了别的设备（见下面的接力）。
                host = cam.host
                state = State.CONNECTED
                // 记下"上次连的就是它"：下次进软件直接自动连这台（多设备适配）
                appContext?.let { SettingsRepo.updateLastDevice(cam.guidHex) }
                // 前台保活：切后台不被 MIUI 冻结/限网，心跳不因后台而断
                appContext?.let { runCatching { ConnectionService.start(it) } }
                startHeartbeat()
                startThumbBatch()
            } else {
                rollback()
                if (lastError == null) lastError = "连接失败"
            }
            // 接力：握手期间用户又选了别的设备（选中指示当场就移过去了），这次的结果
            // 不改变选中，但要接着把他选的那台切过来 —— 否则界面停在这台，而选中指示
            // 指着另一台，用户点完发现"没反应"。
            val wanted = targetGuid
            if (wanted != null && !wanted.equals(cam.guidHex, ignoreCase = true)) {
                Log.d(TAG, "握手期间目标改为 $wanted，接力切换")
                switchTo(context, wanted)
            }
        }
    }

    /** 拉一次 `OP_DEVICE_INFO` 并组装 [CameraInfo]；失败返回 null。 */
    private fun pullDeviceInfo(cam: Discovery.DiscoveredCamera): CameraInfo? =
        CameraInfo.fromDeviceInfo(
            json = ObjectRepository.deviceInfo(cam.host),
            guid = cam.guidHex,
            fallbackName = cam.name,
            protoPort = cam.protoPort,
            filePort = cam.filePort,
        )

    /**
     * 立刻拉一次实时信息（电量 + 镜头）并落进 [DeviceStore]。
     *
     * 这两项平时只跟着 3 秒心跳更新；建立连接后不主动补这一发，主界面就会先空几秒
     * （用户实测反馈：过几秒才刷出来）。失败无所谓 —— 下一个心跳会补上，但要留一行日志，
     * 否则"电量一直不显示"就只能靠猜。
     */
    private fun applyLivePing(host: String) {
        val p = runCatching { ObjectRepository.ping(host) }.getOrNull()
        if (p == null) {
            Log.d(TAG, "连上后首拉实时信息失败，等下一个心跳补")
            return
        }
        DeviceStore.applyPing(p.batteryPct, p.lens)
        Log.d(TAG, "连上后首拉实时信息：电量 ${p.batteryPct}%，镜头 ${p.lens}")
    }

    /** 收走全部会话并清空设备信息（不触碰配对表——配对是长期状态）。 */
    private fun rollback() {
        ObjectRepository.closeAll()
        DeviceStore.clear()
        RecController.reset()
    }

    /** 连接成功后提交缩略图批次：当前目录树下的**全部**图片文件由相机端开始预取 */
    /**
     * ★ 2.6（用户定版）：**不再截断**——"有多少文件就应该加载多少"。
     *
     * <p>旧实现把预热名单卡在 200（`THUMB_WARMUP_LIMIT`），带来一串连带问题：
     * 小图预热、自动传输预览图、相机端预取命令共用这份名单，于是 200 张之外的
     * 图不在批次里、点开/滚动才逐张按需拉，用户看到的是"点开后面的图、进度条
     * 又开始重走"。现在名单 = `/DCIM` 下全部图片（深度≤3）。
     *
     * <p>放开的前提都已核实（见 PLAN-RemoveWarmupLimit.md）：
     * 协议 blob 一次发全量（每条约 30 字节，`MAX_PACKET=1MB` 两端一致，几千张无压力）；
     * 相机端预取队列无长度上限、LRU 容量已随本次改到 1024；手机端内存有 LruCache
     * 淘汰 + 磁盘兜底。
     */
    private fun startThumbBatch() {
        scope.launch {
            runCatching {
                val h = host ?: return@launch
                val entries = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    collectImageEntries(h, "/DCIM")
                }
                // 留一行可核对的统计（全量名单可能很多，排障时先看这行）
                Log.d(TAG, "缩略图预热名单：${entries.size} 张")
                ThumbStore.markBatchQueued(entries.map { it.path })
                val keys = entries.map {
                    ThumbStore.ObjectCacheKey(
                        cameraGuid = camera?.guidHex.orEmpty(),
                        path = it.path,
                        size = it.size,
                        mtime = it.timestamp,
                    )
                }
                if (SettingsRepo.autoPreviewFetch) {
                    // ★ 2.7.0：自动传输开着时，**相机端预取与手机端小图队列都跳过** ——
                    //   自动通道的批里已包含每个文件的小图（T,P 成对），这两条路再各拉
                    //   一遍纯属重复；更糟的是相机端预取器的两个 worker 会和批量解析
                    //   **抢 SD/CPU**（实测：含预览批每项 372ms，而纯小图批每项仅 24ms
                    //   —— 瓶颈在解析+SD 读，不在网络）。关掉它们，把相机的 SD/CPU 全
                    //   让给批量解析。名单仍登记（restartAutoFetch 用它"以当前状态重启"）。
                    ThumbStore.rememberWarmup(keys)
                    ThumbStore.startAutoFetch(keys)
                } else {
                    // 自动传输关闭：维持原行为（相机端预取热身 + 手机端小图队列）
                    if (entries.isNotEmpty()) {
                        ObjectRepository.thumbQueue(h, PtpCodec.OP_THUMB_QUEUE_BEGIN, entries.map { it.path })
                    }
                    ThumbStore.request(keys)
                    // ★ 登记名单：设置里开关自动传输 / 清除缓存时，ThumbStore.restartAutoFetch()
                    //   要靠它"以当前状态重启传输流程"，不必等重连（用户定版）。
                    ThumbStore.rememberWarmup(keys)
                }
            }
        }
    }

    /**
     * 递归收集图片条目（真实深度≤3，**不限量**）—— 返回**完整条目**（路径+size+mtime）。
     *
     * <p>★ 2.6：原来这里有个 `max` 上限（200），把预热/自动传输的名单截断了；
     * 用户定版"有多少文件就应该加载多少"，参数与两处截断判断一并删除。
     * 每目录分页 256 条走 listPage，几千张也只是多翻几页，毫秒级。
     */
    private fun collectImageEntries(host: String, dir: String): List<ObjectRepository.FtpEntry> {
        data class DirTask(val path: String, val depth: Int)
        val out = ArrayList<ObjectRepository.FtpEntry>()
        val queue = ArrayDeque<DirTask>()
        queue.add(DirTask(dir, 1))
        while (queue.isNotEmpty()) {
            val task = queue.removeFirst()
            var offset = 0
            while (true) {
                val page = try {
                    ObjectRepository.listPage(host, task.path, offset, 256)
                } catch (e: Exception) {
                    break
                }
                for (e in page.entries) {
                    if (e.isDir) {
                        if (task.depth < 3) queue.add(DirTask(e.path, task.depth + 1))
                    } else {
                        val ext = e.ext
                        if (ext == "jpg" || ext == "jpeg" || ext == "arw") out.add(e)
                    }
                }
                if (!page.hasMore || page.nextOffset <= offset) break
                offset = page.nextOffset
            }
        }
        return out
    }

    // ===== 心跳 =====

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var misses = 0
            while (state == State.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_MS)
                val h = host ?: continue
                val ping = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ObjectRepository.ping(h) }.getOrNull()
                }
                if (ping == null) {
                    misses++
                    if (misses >= HEARTBEAT_MAX_MISSES) {
                        disconnect("失去与相机的连接")
                        return@launch
                    }
                    continue
                }
                misses = 0
                // 唯一的实时包：电量与镜头名一起增量合并（不再有第二个定时器）
                DeviceStore.applyPing(ping.batteryPct, ping.lens)
            }
        }
    }

    // ===== 配对（强制配对定案：唯一的入网途径） =====

    /**
     * 配对流程的**阶段**（用户定版的新流程：相机待机不亮码，所以必须分两步）。
     *
     * - [PairStage.IDLE]：没在配对
     * - [PairStage.CONNECTING]：正在连相机并发 `PAIR_BEGIN`（相机这时才亮码）
     * - [PairStage.AWAITING_CODE]：**配对连接已建立**，相机屏上已显示码，等用户输入
     * - [PairStage.SUBMITTING]：正在提交码核对
     *
     * ★ 与 [pairingTarget] 的分工：target 是"配哪台"，stage 是"走到哪一步了"。
     *   界面按 stage 决定显示"正在连接相机…"还是输码框。
     */
    enum class PairStage { IDLE, CONNECTING, AWAITING_CODE, SUBMITTING }

    var pairingStage by mutableStateOf(PairStage.IDLE)
        private set

    /**
     * 开始配对：**连上相机 + 发 PAIR_BEGIN**，让相机亮出配对码（第一步）。
     *
     * <p>用户定版流程："手机扫描到相机发送配对请求后建立一次配对连接 双方要能知道对方
     * 是否连接"，"配对连接建立后相机进入验证码显示界面手机进入验证码输入界面"。
     *
     * <p>所以点一台相机之后**先建连接**，成功了才让用户输码 —— 连接建立不起来就没码可输。
     * 这条连接会**一直留着**到用户输完码（见 [submitPairCode]），中途断了立刻回待机。
     *
     * **不断开**当前会话：已连着一台时也能再配第二台（用户定版：悬浮窗里要能切设备，
     * 前提就是有多台已绑定）。让位发生在配对成功之后。
     */
    fun requestPair(cam: Discovery.DiscoveredCamera) {
        if (connecting || pairingStage != PairStage.IDLE) return
        val ctx = appContext ?: return
        pairingTarget = cam
        pairingError = null
        pairingStage = PairStage.CONNECTING
        scope.launch {
            val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    pinWifiNetwork(ctx, targetHost = cam.host)
                    ObjectRepository.boundNetwork = boundNetwork
                    ObjectRepository.pairBegin(
                        host = cam.host,
                        protoPort = cam.protoPort,
                        // ★ 同上：配对连接也要报**本机自己的身份**（相机靠它在配对表里落键）
                        guid16 = IdentityRepo.guid16(),
                        friendlyName = IdentityRepo.friendlyName,
                        // "相机已认识本机"那条分支要用它开事件通道 —— 否则相机侧的
                        // 主动通知（解除配对/切换方式/退出）全收不到
                        onEvent = eventListener,
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "pairBegin exception: ${e.javaClass.simpleName} ${e.message}")
                    ObjectRepository.PairOutcome.FAILED
                }
            }
            when (outcome) {
                ObjectRepository.PairOutcome.PAIRED -> {
                    // 相机已亮码 → 等用户输入
                    Log.d(TAG, "配对连接已建立，等待输入配对码")
                    pairingStage = PairStage.AWAITING_CODE
                }
                ObjectRepository.PairOutcome.BUSY -> {
                    pairingStage = PairStage.IDLE
                    pairingError = "相机正被其他设备占用（配对中或已连接），请稍后重试"
                }
                ObjectRepository.PairOutcome.ALREADY_PAIRED -> {
                    // ★ 2026-09-19 起这条**走不到了**：相机端已改成"已授权会话里收到
                    //   PAIR_BEGIN 也走正常配对流程（亮码 + 验码）"。
                    //   保留分支只为兼容旧版相机端；真走到这里说明相机端是旧版，
                    //   而那种连接状态不对（后续请求会超时），所以不再"跳过输码"。
                    Log.w(TAG, "相机端仍走旧的「已配对直接放行」路径（版本过旧）")
                    pairingStage = PairStage.IDLE
                    pairingError = "相机端版本过旧，请更新相机端后再配对"
                }
                else -> {
                    pairingStage = PairStage.IDLE
                    pairingError = "无法连接相机，请确认相机已进入配对模式，且与手机在同一网络"
                }
            }
        }
    }

    /**
     * 关掉配对失败提示框（UI「确定」按钮）：只清错误文案，不动任何流程状态 ——
     * 失败详情已经弹窗交代过，用户看完点确定，页面回到干净的待扫描状态。
     */
    fun dismissPairingError() {
        pairingError = null
    }

    /**
     * 取消配对（用户关掉输码框 / 离开配对页）。
     *
     * <p>会**主动放弃那条配对连接**（发 `PAIR_ABORT`），相机端收到立刻退回待机、
     * 把码收起来 —— 用户定版："如果断连立刻退出到等待配对状态"。
     */
    fun cancelPair() {
        if (pairingStage == PairStage.SUBMITTING) return
        pairingTarget = null
        pairingError = null
        pairingStage = PairStage.IDLE
        scope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) { ObjectRepository.pairAbort() }
        }
    }

    /**
     * 提交配对码（第二步）：把用户看着相机屏输入的码交给相机核对。
     *
     * <p>配对码是 6 位数字，只显示在相机端"配对模式"页上 —— 用户定版流程的
     * "配对连接建立后相机进入验证码显示界面手机进入验证码输入界面输入验证码配对"。
     */
    fun submitPairCode(context: Context, code: String) {
        val cam = pairingTarget ?: return
        if (pairingStage != PairStage.AWAITING_CODE) return
        lastError = null
        pairingError = null
        pairingStage = PairStage.SUBMITTING
        scope.launch {
            val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    pinWifiNetwork(context, targetHost = cam.host)
                    ObjectRepository.boundNetwork = boundNetwork
                    ObjectRepository.pairExchange(cam.host, code, eventListener)
                } catch (e: Exception) {
                    Log.d(TAG, "pairExchange exception: ${e.javaClass.simpleName} ${e.message}")
                    ObjectRepository.PairOutcome.FAILED
                }
            }
            if (outcome == ObjectRepository.PairOutcome.FAILED) {
                // 失败：配对连接已在 ObjectRepository 里关掉了，相机端随之退回待机。
                // 让用户重新走一遍（重新点那台相机 = 重新建连接 = 相机重新亮码），
                // 比让他在一个已经失效的连接上反复试更清楚。
                pairingStage = PairStage.IDLE
                pairingError = "配对码不正确或连接已断开，请重试"
                return@launch
            }
            if (outcome == ObjectRepository.PairOutcome.BUSY) {
                pairingStage = PairStage.IDLE
                pairingError = "相机正被其他设备占用（配对中或已连接），请稍后重试"
                return@launch
            }
            finishPairSuccess(cam)
        }
    }

    /**
     * 配对成功的共同收尾：落表 → 拉设备信息 → 实时信息 → 切到已连接。
     *
     * <p>[submitPairCode] 与"相机本来就认识本机"两条路都走这里 —— 保证两条路
     * 之后的状态**完全一致**（原来这段是内联在 submitPairCode 里的）。
     */
    private suspend fun finishPairSuccess(cam: Discovery.DiscoveredCamera) {
        // 落配对表：此刻还不知道相机型号/序列号，紧接着拉一次 DEVICE_INFO 补全
        // （手机端"已配对相机"列表要显示的正是"型号 SN:xxxx"）
        val record = PairingStore.PairedCamera(
            peerDeviceId = cam.guidHex,
            peerName = cam.name,
            pairedAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis(),
            lastIp = cam.host,
            lastPort = cam.protoPort,
        )
        PairingStore.upsert(record)
        val info = withContext(kotlinx.coroutines.Dispatchers.IO) { pullDeviceInfo(cam) }
        if (info != null) {
            PairingStore.upsert(
                record.copy(peerName = cam.name, peerModel = info.model, peerSerial = info.serial)
            )
            DeviceStore.apply(info)
        } else {
            DeviceStore.apply(
                CameraInfo.placeholder(cam.guidHex, cam.name, cam.protoPort, cam.filePort)
            )
        }
        // ★ 实时信息**放在 DEVICE_INFO 之后**（与常规连接同一条纪律）：那份快照里
        //   没有电量/镜头字段，先拉会被它清成未知 —— 表现就是"配对成功回主界面后
        //   还要等 3 秒心跳才出电量"。
        withContext(kotlinx.coroutines.Dispatchers.IO) { applyLivePing(cam.host) }
        TransferStore.onCameraChanged(cam.guidHex)
        ObjectRepository.setOnDisconnectedListener(cam.host) {
            if (!::scope.isInitialized) return@setOnDisconnectedListener
            scope.launch {
                if (host == cam.host && state == State.CONNECTED) {
                    // ★ 用户定版：**没收到任何声明**的断开 = 意外断连，
                    //   统一按"失去与相机的连接"处理；有声明的那些走各自
                    //   的事件处理（它们已经在事件回调里置位并给出说法）。
                    disconnect(if (cameraDeclaredDisconnect) null else "失去与相机的连接")
                }
            }
        }
        ObjectRepository.sessionOf(cam.host)?.let { s ->
            PairingStore.updateProtoVersion(cam.guidHex, s.client.protoVersion)
        }
        cameraDeclaredDisconnect = false   // 新会话开始，声明标志归零
        pairingStage = PairStage.IDLE
        pairingTarget = null
        // ★ 让位放在**配对成功之后**，不是拿到码之前：先断的话，用户只是输错一次码
        //   就会白丢当前那台的连接。此刻新会话已经建好，旧会话（另一台相机）该收了
        //   —— 不收就留下一条连着旧相机的连接（心跳已改指向新相机，那条再无人问，
        //   白占相机的一个客户端位直到进程结束）。
        if (state == State.CONNECTED && camera?.guidHex != cam.guidHex) {
            disconnect("切换到新配对的相机")
        }
        // 配对这条连接已经是认证好的完整会话，直接接管为当前连接，不再重连
        camera = cam
        targetGuid = cam.guidHex
        host = cam.host
        state = State.CONNECTED
        lastError = null
        // 刚配好的这台就是"最后一次连接的设备"：配对成功即把它设成下次自动连接的目标
        SettingsRepo.updateLastDevice(cam.guidHex)
        appContext?.let { runCatching { ConnectionService.start(it) } }
        startHeartbeat()
        startThumbBatch()
    }


    /**
     * 解除配对：先请相机删掉它那侧的记录，再清本地。
     *
     * 相机侧失败也照样清本地 —— 用户的意图是"别再自动连它了"，本地清掉即达成；
     * 相机那侧残留一条记录没有实际危害（密钥已不在手机上）。
     */
    fun unpair(peerDeviceId: String) {
        val h = host
        val isCurrent = camera?.guidHex == peerDeviceId
        scope.launch {
            if (isCurrent && h != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ObjectRepository.pairRemove(h) }
                }
            }
            PairingStore.remove(peerDeviceId)
            // 解掉的若是"下次要连的那台"，目标也得撤掉：留着它，进软件就会去连一台
            // 已经被用户明确解除的相机（而且必然连不上，因为准入判定就是配对表）
            if (targetGuid.equals(peerDeviceId, ignoreCase = true)) {
                targetGuid = null
                // 队列也得跟着离开这台（它的任务还留在自己的桶里，但界面不该再停在那儿）：
                // 退到配对表里最近联系过的那台，没有别的了就先不动（任务不会丢）
                PairingStore.mostRecent()?.peerDeviceId?.let {
                    targetGuid = it
                    selectQueue(it)
                }
            }
            // 解除的若是当前连着的相机：密钥已废，连接必须断
            if (isCurrent && state == State.CONNECTED) {
                disconnect("已解除与该相机的配对")
            }
        }
    }

    /**
     * 心跳失联/主动断开：立即回未连接并收走全部连接。
     *
     * [targetGuid] **刻意不清**：它记的是"要连哪台"，断线不代表用户改主意了 ——
     * 主界面要靠它显示"未连接 · ILCE-6300"并给出"重新连接"，清掉就只能显示"相机"。
     */
    /**
     * 收掉当前会话。
     *
     * @param reason 直接上屏的说辞；**null = 不报错**（用户主动断开、或相机已经
     *   自己声明过了、那句话已经显示在界面上，这里不该再用第二句覆盖它）。
     */
    fun disconnect(reason: String? = null) {
        // 留痕：自动断开（心跳失联）原先一行日志都没有，事后只能靠猜
        Log.d(TAG, "disconnect: ${reason ?: "无（用户主动 / 已声明过）"}")
        cameraDeclaredDisconnect = false   // 本次断开的声明已消费，下次连接重新计
        host?.let { ObjectRepository.setOnDisconnectedListener(it, null) }
        heartbeatJob?.cancel()
        heartbeatJob = null
        autoScanJob?.cancel()
        autoScanJob = null
        autoConnectJob?.cancel()
        autoConnectJob = null
        connecting = false
        // 传输进行中不允许断开拖死服务：先停传输
        TransferStore.onConnectionLost()
        rollback()
        ThumbStore.reset()
        unpinNetwork()
        appContext?.let { runCatching { ConnectionService.stop(it) } }
        host = null
        camera = null
        state = State.DISCONNECTED
        if (reason != null) lastError = reason
    }
}
