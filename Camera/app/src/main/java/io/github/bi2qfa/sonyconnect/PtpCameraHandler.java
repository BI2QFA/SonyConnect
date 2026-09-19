package io.github.bi2qfa.sonyconnect;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 相机侧业务实现：把 {@link PtpIpServer.Handler} 的 16 个方法与可选的
 * {@link PtpIpServer.PairingHandler} 桥接到现有的 {@link DeviceInfo} /
 * {@link ThumbPrefetcher} / {@link ThumbnailExtractor} / {@link PairingStore}。
 *
 * ★ **本类绝不引用任何 Android 类型。**
 * 原因很实在：desktop-test 用**空 classpath** 编译内核（见 run-tests.sh），
 * 一旦这里出现 {@code android.content.Context} 之类，桌面回归立刻编译不过。
 * 所以与平台相关的能力（电量/型号/序列号/固件/镜头/模式/SSID）全部通过
 * {@link Platform} 注入，由 MainActivity 用 {@code Context} 去实现。
 *
 * 路径约定（★ 最容易踩的一处）：
 * 协议里的路径是**绝对**形态 {@code /DCIM/100MSDCF/DSC00001.ARW}，而
 * {@link ThumbPrefetcher} 内部用 {@code new File(rootDir, rel)} 解析、缓存键
 * 就是喂进 {@code begin()} 的那个字符串。所以本类统一用 {@link #relOf} 把绝对
 * 路径转成相对形态后再碰 prefetcher，两处必须始终一致，否则缩略图会
 * **静默全部 miss**（功能看不出来坏，只是永远走实时提取）。
 */
public class PtpCameraHandler implements PtpIpServer.Handler, PtpIpServer.PairingHandler {

    /** 平台能力注入点；由 MainActivity 实现（内部用 Context 调 DeviceInfo）。 */
    public interface Platform {
        /** 电量百分比；未知返回 -1。 */
        int batteryPct();

        String model();

        String serial();

        String firmware();

        /** 镜头名；无镜头或读取失败返回 null。 */
        String lens();

        /** 连接方式（沿用 MainActivity 的 savedMode 原值）。 */
        String mode();

        /** 当前网络名（热点 SSID 或所连 Wi-Fi 名）。 */
        String ssid();

        // ===== 以下为**固定信息**（装好之后不变），只在 DEVICE_INFO 快照里下发 =====

        /** 地区（backup region）；取不到返回 null。 */
        String region();

        /** 相机侧 Java API 版本（形如 "2.3"）；取不到返回 null。 */
        String apiVersion();

        /** 相机系统安卓版本（形如 "4.1.2"）。 */
        String androidVersion();

        /** 相机系统安卓 SDK 级别（形如 16）；未知 -1。 */
        int androidSdk();

        /** SD 卡总容量（字节）；未知 -1。 */
        long sdTotalBytes();

        /** SD 卡已用空间（字节）；未知 -1。 */
        long sdUsedBytes();
    }

    private final File rootDir;
    private final ThumbPrefetcher prefetcher;
    private final PairingStore pairing;
    private final Platform platform;

    /** 缩略图提取的记忆化上限：只记小图，别让 425 KB 的大预览长期占住相机堆内存。 */
    private static final int MEMO_MAX_BYTES = 64 * 1024;
    private final Object memoLock = new Object();
    private String memoKey;
    private byte[] memoBytes;

    public PtpCameraHandler(File rootDir,
                            ThumbPrefetcher prefetcher,
                            PairingStore pairing,
                            Platform platform) {
        this.rootDir = rootDir;
        this.prefetcher = prefetcher;
        this.pairing = pairing;
        this.platform = platform;
    }

    /**
     * 配对状态变化回调（可选注入）。
     *
     * <p>内核保持零 Android 依赖，所以这里只是一个接口：UI 侧实现它接住事件，
     * 自己 post 回主线程。**不注入时行为与引入前完全一致**（桌面回归依赖这条）。
     */
    public interface PairingEvents {
        /** 配对成功（配对表已落好、窗口已关）。UI 据此自动回主页面。 */
        void onPaired(String peerDeviceName);

        /**
         * 一次配对失败。
         *
         * @param used         本次窗口已失败次数（含这一次）
         * @param max          失败上限
         * @param windowClosed 用满上限、窗口已因此关闭（UI 应提示并自动换新码）
         */
        void onPairingAttemptFailed(int used, int max, boolean windowClosed);
    }

    private volatile PairingEvents pairingEvents;

    public void setPairingEvents(PairingEvents e) {
        this.pairingEvents = e;
    }

    // ============================================================
    // PtpIpServer.Handler
    // ============================================================

    public byte[] guid() {
        // 设备码由 PairingStore 管理：随机生成一次、落盘、此后永不变。
        // 绝不从序列号等真机信息派生（用户定版）。
        return pairing == null ? Rand.bytes(PairingStore.DEVICE_ID_LEN)
                : pairing.deviceId();
    }

    public String deviceName() {
        String model = safe(platform == null ? null : platform.model());
        String serial = safe(platform == null ? null : platform.serial());
        if (model.length() > 0 && serial.length() > 0) {
            // ★ 序列号前带 `SN:`（用户定版：**无论在哪里显示相机名称都要带**）。
            //   这个名字是"相机对外自称"的那一份，手机端所有显示相机名称的地方
            //   （配对页的发现列表、配对码弹窗标题、配对表里 peerName 的兜底）都取它 ——
            //   改这一处，各处自然一致；在手机端逐个拼反而会漏。
            return model + " SN:" + serial;
        }
        if (model.length() > 0) {
            return model;
        }
        return "SonyConnect Camera";
    }

    /**
     * 静态快照。字段名与手机端 {@code CameraInfo.fromDeviceInfo} 逐字对齐：
     * {@code name / model / serial / firmware / region / apiVersion / androidVersion /
     * androidSdk / sdTotal / sdUsed / mode / ssid}。
     *
     * <p>★ **只含不会变的数据。** 电量与镜头名是实时数据，已移到 {@code OP_PING}
     * 单独下发（一次往返带全部实时数据，不让两个短周期定时器各跑一趟）。
     * 连接时拉一次本快照即可，之后整个会话都不再需要。
     *
     * <p>地区 / Java API 版本 / 安卓版本 / SD 容量都属于**固定信息**（用户定版：
     * "不用实时更新"），所以落在这里而不是心跳里。注意其中 SD 容量口径上会变
     * （换卡、边拍边填），但与其余字段同为"一个会话一份快照"的语义，重连即刷新 ——
     * 不额外给它开一条实时通道。
     *
     * <p>未知字符串一律发**空串**而不是 JSON {@code null} —— 手机端 {@code optString}
     * 遇到 JSON null 的行为在不同 org.json 版本上不一致，空串是唯一稳妥的表示。
     * 手机端对缺失的数值键会回落到 -1（未知），所以这里不发电量也不会被误读成 0；
     * {@code androidSdk / sdTotal / sdUsed} 同理，未知就发 -1。
     */
    public byte[] deviceInfo() {
        StringBuilder sb = SJson.startObj();
        SJson.member(sb, "name", deviceName());
        SJson.member(sb, "model", safe(platform == null ? null : platform.model()));
        SJson.member(sb, "serial", safe(platform == null ? null : platform.serial()));
        SJson.member(sb, "firmware", safe(platform == null ? null : platform.firmware()));
        SJson.member(sb, "region", safe(platform == null ? null : platform.region()));
        SJson.member(sb, "apiVersion", safe(platform == null ? null : platform.apiVersion()));
        SJson.member(sb, "androidVersion",
                safe(platform == null ? null : platform.androidVersion()));
        SJson.member(sb, "androidSdk", platform == null ? -1 : platform.androidSdk());
        SJson.member(sb, "sdTotal", platform == null ? -1L : platform.sdTotalBytes());
        SJson.member(sb, "sdUsed", platform == null ? -1L : platform.sdUsedBytes());
        SJson.member(sb, "mode", safe(platform == null ? null : platform.mode()));
        SJson.member(sb, "ssid", safe(platform == null ? null : platform.ssid()));
        return utf8(SJson.endObj(sb));
    }

    public int batteryPct() {
        return platform == null ? -1 : platform.batteryPct();
    }

    public boolean hasLens() {
        return lensName().length() > 0;
    }

    /** 镜头名；无镜头或读取失败返回空串。随 {@code OP_PING} 下发（实时数据）。 */
    public String lensName() {
        return safe(platform == null ? null : platform.lens());
    }

    /**
     * 是否放行该手机。只有在**不**走配对路径时才会被问到（见
     * {@code PtpIpServer.serveControl} 的门顺序），所以这里就是「已配对即放行」。
     * 窗口开着但未配对的情况由配对阶段接管，这里不必也不该放行。
     */
    public boolean allowInitiator(byte[] guid8) {
        return pairing != null && pairing.contains(guid8);
    }

    /**
     * 配对页是否正开着（MainActivity 进/出配对页时置位）。
     *
     * ★ 用户要求："首次启动先有等待手机连接的环节，手机来连了才亮码" —— 也就是说
     *   **配对模式激活**与**码窗口已开**是两个状态：进配对页只进模式不亮码，
     *   手机发起配对（PAIR_BEGIN 来取码）的那一刻才开窗。发现应答的 pairingMode
     *   在"等待"阶段也必须报真，否则手机根本扫不到这台相机。
     */
    private volatile boolean pairingUiActive;

    public void setPairingUiActive(boolean b) {
        pairingUiActive = b;
    }

    /** 探测应答里的 pairingMode：配对页开着即真（码窗未开=等待手机连接，也算）。 */
    public boolean isPairingMode() {
        return pairing != null && (pairing.isOpen(now()) || pairingUiActive);
    }

    /** 探测应答里的 paired：表里至少有一台就是真。 */
    public boolean hasPairedInitiator() {
        return pairing != null && pairing.size() > 0;
    }

    public byte[] readObject(String path, int kind, long offset, int length) {
        File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return null;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            return readRange(f, offset, length);
        }
        byte[] whole = mediaBytes(f, path, kind);
        if (whole == null) {
            return null;
        }
        return slice(whole, offset, length);
    }

    /**
     * 传输期只读句柄：**整段传输只 open 一次**。
     *
     * <p>逐块 {@link #readObject} 在相机上的代价是每块一次
     * {@code new RandomAccessFile} + {@code close} + 一次 {@code isFile()} 的
     * stat。128 KiB 一块时每 MB 要付 8 次，几十 MB 的 ARW 光这些固定开销就是
     * 好几秒 —— 比 SD 顺序读本身还贵。这里把句柄留给整段传输用。
     *
     * <p>缩略图/预览本来就是"整张解析一次"的产物（还有记忆化兜着），
     * 句柄直接包住那份字节，省掉逐块切片分配。
     *
     * @return null 表示本对象不可用（不存在/非文件/打不开），调用方回退逐块读
     */
    public PtpIpServer.Handler.ObjectReader openObject(String path, int kind) {
        final File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return null;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            final RandomAccessFile raf;
            try {
                raf = new RandomAccessFile(f, "r");
            } catch (Throwable t) {
                return null;
            }
            final long size = f.length();
            return new PtpIpServer.Handler.ObjectReader() {
                public int readInto(long offset, byte[] dst, int off, int length) {
                    if (offset < 0 || offset > size || length <= 0) {
                        return 0;
                    }
                    int len = (int) Math.min((long) length, size - offset);
                    if (len <= 0) {
                        return 0;
                    }
                    try {
                        raf.seek(offset);
                        int got = 0;
                        while (got < len) {
                            int r = raf.read(dst, off + got, len - got);
                            if (r < 0) {
                                break;
                            }
                            got += r;
                        }
                        return got;
                    } catch (Throwable t) {
                        return -1;
                    }
                }

                public void close() {
                    try {
                        raf.close();
                    } catch (Throwable ignored) {
                    }
                }
            };
        }
        final byte[] whole = mediaBytes(f, path, kind);
        if (whole == null) {
            return null;
        }
        return new PtpIpServer.Handler.ObjectReader() {
            public int readInto(long offset, byte[] dst, int off, int length) {
                if (offset < 0 || offset >= whole.length || length <= 0) {
                    return 0;
                }
                int n = (int) Math.min((long) length, whole.length - offset);
                System.arraycopy(whole, (int) offset, dst, off, n);
                return n;
            }

            public void close() {
                // 小图整份在内存里，没有句柄要放
            }
        };
    }

    public long objectSize(String path, int kind) {
        File f = fileOf(path);
        if (f == null || !f.isFile()) {
            return -1L;
        }
        if (kind == PtpCodec.KIND_ORIGINAL) {
            return f.length();
        }
        byte[] b = mediaBytes(f, path, kind);
        return b == null ? -1L : b.length;
    }

    public long objectMtime(String path) {
        File f = fileOf(path);
        if (f == null || !f.exists()) {
            return -1L;
        }
        return f.lastModified();
    }

    /**
     * 目录列表。**不过滤**（沿用旧 FTP 端 {@code LIST} 的全量语义：目录 + 全部文件），
     * 由手机端按扩展名自己筛（{@code FilesScreen} 只认 jpg/jpeg/arw），
     * 这样两端行为与 1.0 完全一致。
     * <p>JSON 是**手工拼**的：相机端 {@link SJson} 的编码与解析都不支持嵌套对象，
     * 写不出「数组套对象」。键名严格只用 {@code name / dir / size / mtime} ——
     * 手机端 {@code ObjectRepository.parseEntries} 只读这四个。
     */
    public byte[] listDir(String path, int offset, int limit) {
        File dir = fileOf(path);
        if (dir == null || !dir.isDirectory()) {
            AppLog.w("File", "列目录失败（不存在/不是目录）：" + path);
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        AppLog.i("File", "列目录 " + path + " → " + files.length + " 项");
        List<File> ordered = new ArrayList<File>(files.length);
        for (int i = 0; i < files.length; i++) {
            if (files[i] != null) {
                ordered.add(files[i]);
            }
        }
        int start = Math.max(0, Math.min(offset, ordered.size()));
        int pageLimit = limit > 0 ? Math.min(limit, 256) : 0;
        int end = pageLimit > 0 ? Math.min(ordered.size(), start + pageLimit) : ordered.size();
        boolean hasMore = end < ordered.size();

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append(SJson.str("dir")).append(':').append(SJson.str(path));
        sb.append(',').append(SJson.str("offset")).append(':').append(start);
        sb.append(',').append(SJson.str("nextOffset")).append(':').append(end);
        sb.append(',').append(SJson.str("hasMore")).append(':').append(hasMore ? "true" : "false");
        sb.append(',').append(SJson.str("entries")).append(":[");
        int n = 0;
        for (int i = start; i < end; i++) {
            File f = ordered.get(i);
            String name = f.getName();
            if (name == null || name.length() == 0) {
                continue;
            }
            boolean isDir = f.isDirectory();
            if (n > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append(SJson.str("name")).append(':').append(SJson.str(name));
            sb.append(',').append(SJson.str("dir")).append(':').append(isDir ? "true" : "false");
            sb.append(',').append(SJson.str("size")).append(':').append(isDir ? 0L : f.length());
            sb.append(',').append(SJson.str("mtime")).append(':').append(f.lastModified());
            sb.append('}');
            n++;
        }
        sb.append("]}");
        return utf8(sb.toString());
    }

    public void onThumbControl(int op, String[] paths) {
        if (prefetcher == null) {
            return;
        }
        switch (op) {
            case PtpCodec.OP_THUMB_QUEUE_BEGIN: {
                List<String> rel = new ArrayList<String>();
                if (paths != null) {
                    for (int i = 0; i < paths.length; i++) {
                        String r = relOf(paths[i]);
                        if (r != null && r.length() > 0) {
                            rel.add(r);
                        }
                    }
                }
                prefetcher.begin(rel);
                break;
            }
            case PtpCodec.OP_THUMB_QUEUE_PAUSE:
                prefetcher.pause();
                break;
            case PtpCodec.OP_THUMB_QUEUE_RESUME:
                prefetcher.resume();
                break;
            case PtpCodec.OP_THUMB_QUEUE_CANCEL:
                prefetcher.cancel();
                break;
            default:
                break;
        }
    }

    // ============================================================
    // PtpIpServer.PairingHandler
    // ============================================================

    public boolean isDevicePaired(byte[] guid8) {
        return pairing != null && pairing.contains(guid8);
    }

    public boolean isPairingOpen() {
        return pairing != null && (pairing.isOpen(now()) || pairingUiActive);
    }

    public String pairingCode() {
        if (pairing == null) {
            return null;
        }
        String code = pairing.code(now());
        // ★ 懒开窗：手机发起配对（PAIR_BEGIN 进来取码）的这一刻才把码亮到屏上。
        //   之前是进配对页就开窗 —— 手机还没来，码就杵在屏上了，没有"等待手机连接"。
        if (code == null && pairingUiActive) {
            pairing.openWindow(now());
            code = pairing.code(now());
        }
        return code;
    }

    /**
     * 配对成功：落表 + 关窗。
     *
     * <p>落表之后这台手机就在配对表里了，{@code allowInitiator} 随之放行 ——
     * 加解密与双向认证都已移除，配对表就是唯一的准入依据。同一连接会立刻进入
     * 控制循环，手机端不需要重连。
     */
    public void onPaired(byte[] guid8, String peerDeviceName) {
        if (pairing == null) {
            return;
        }
        PairingStore.Paired p = new PairingStore.Paired();
        p.peerDeviceId = PtpCodec.hex(guid8);
        p.peerName = safe(peerDeviceName);
        p.pairedAt = now();
        p.lastSeenAt = now();
        pairing.upsert(p);
        pairing.closeWindow();
        PairingEvents pe = pairingEvents;
        if (pe != null) {
            try {
                pe.onPaired(p.peerName);
            } catch (Throwable t) {
                // UI 回调出问题不许带崩配对流程（配对表已经落好了）
            }
        }
    }

    /**
     * 已配对设备连上来：名字变了就把表里那份改成新的。
     *
     * <p>配对列表显示的就是 {@code peerName}，而它只在配对那一刻抄一次；手机在系统设置里
     * 改名（或换机沿用同一设备码）之后，不跟新就会一直显示旧名 —— 用户实测反馈过这条。
     * 只动名字，不碰准入（准入在 {@code allowInitiator} 已经过了）。
     */
    public void onPairedDeviceSeen(byte[] guid8, String peerDeviceName) {
        if (pairing == null || guid8 == null) {
            return;
        }
        PairingStore.Paired p = pairing.find(guid8);
        if (p == null) {
            return;
        }
        String name = safe(peerDeviceName);
        if (name.length() == 0 || name.equals(p.peerName)) {
            return;
        }
        p.peerName = name;
        p.lastSeenAt = now();
        pairing.upsert(p);
    }

    /** 一次配对失败：窗口侧计次（用满 5 次会自行关窗），并把结果报给 UI。 */
    public void onPairingFailed() {
        if (pairing == null) {
            return;
        }
        // used 要在 noteFailure 之前算：用满次数时 closeWindow() 会把计数清零，
        // 之后再读就永远是 0，"第几次失败"就丢了。
        int used = pairing.failures() + 1;
        pairing.noteFailure(now());
        boolean closed = !pairing.isOpen(now());
        PairingEvents pe = pairingEvents;
        if (pe != null) {
            try {
                pe.onPairingAttemptFailed(used, PairingStore.MAX_FAILURES, closed);
            } catch (Throwable t) {
                // 同上：通知失败不影响配对窗口本身的纪律
            }
        }
    }

    public void onPairingAborted() {
    }

    /** 已配对手机请求解除配对：删掉它那条记录。手机端随后也会清本地那份。 */
    public void onUnpair(byte[] guid8) {
        if (pairing == null || guid8 == null) {
            return;
        }
        boolean removed = pairing.remove(guid8);
    }

    // ============================================================
    // 内部
    // ============================================================

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    /**
     * 绝对协议路径 → 相对路径（去掉开头那一个 '/'）。
     * 非法或就是根目录返回 null。这是与 {@link ThumbPrefetcher} 约定的唯一形态。
     */
    static String relOf(String absPath) {
        if (absPath == null || absPath.length() == 0 || absPath.charAt(0) != '/') {
            return null;
        }
        String rel = absPath.substring(1);
        return rel.length() == 0 ? null : rel;
    }

    /**
     * 协议路径 → {@link File}。两道防线：
     * ① {@link PtpIpServer#isPathSafe} 挡掉 {@code ..} / 反斜杠 / NUL / 非绝对路径；
     * ② 再确认拼出来的绝对路径确实落在 rootDir 之内（纵深防御）。
     * 路径非法或越界返回 null。
     */
    File fileOf(String path) {
        if (rootDir == null || !PtpIpServer.isPathSafe(path)) {
            return null;
        }
        String rel = path.substring(1);
        File f = rel.length() == 0 ? rootDir : new File(rootDir, rel);
        String abs = f.getAbsolutePath();
        String rootAbs = rootDir.getAbsolutePath();
        if (!abs.equals(rootAbs) && !abs.startsWith(rootAbs + File.separator)) {
            return null;
        }
        return f;
    }

    /** 小缩略图 / 大预览的字节。命中预取缓存就直接用，省一次解析。 */
    private byte[] mediaBytes(File f, String absPath, int kind) {
        if (!ThumbnailExtractor.supports(f)) {
            return null;
        }
        String rel = relOf(absPath);
        if (kind == PtpCodec.KIND_THUMB && prefetcher != null && rel != null) {
            byte[] hit = prefetcher.lookup(rel);
            if (hit != null) {
                return hit;
            }
        }
        // 记忆化：一次 GET_OBJECT 会先问大小（objectSize）再取字节（readObject），
        // 两者都落到这里 → 不做记忆就是同一张图解析两遍。手机端是串行取图的，
        // 所以单条记忆必然命中。只记小图（≤ 64 KB），避免为 425 KB 的大预览
        // 长期占住相机那点堆内存；大预览打开频率低，重复解析一次无所谓。
        String key = (rel == null ? absPath : rel);
        synchronized (memoLock) {
            if (key.equals(memoKey) && memoBytes != null) {
                return memoBytes;
            }
        }
        byte[] out;
        try {
            out = kind == PtpCodec.KIND_PREVIEW
                    ? ThumbnailExtractor.extractPreview(f)
                    : ThumbnailExtractor.extractSmall(f);
        } catch (Throwable t) {
            out = null;
        }
        if (out != null && out.length <= MEMO_MAX_BYTES) {
            synchronized (memoLock) {
                memoKey = key;
                memoBytes = out;
            }
        }
        return out;
    }

    private static byte[] slice(byte[] src, long offset, int length) {
        if (src == null || offset < 0 || offset > src.length) {
            return null;
        }
        int start = (int) offset;
        int max = src.length - start;
        int len = (length < 0 || length > max) ? max : length;
        byte[] out = new byte[len];
        System.arraycopy(src, start, out, 0, len);
        return out;
    }

    private static byte[] readRange(File f, long offset, int length) {
        long size = f.length();
        if (offset < 0 || offset > size) {
            return null;
        }
        long max = size - offset;
        int len = (length < 0 || (long) length > max) ? (int) max : length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.seek(offset);
            byte[] out = new byte[len];
            raf.readFully(out);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
