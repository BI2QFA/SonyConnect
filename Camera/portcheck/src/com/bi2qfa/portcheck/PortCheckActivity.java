package com.bi2qfa.portcheck;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SonyConnect P0 前置实测工具（A6300 相机端）—— 只做两件事：
 *
 *   1. 候选端口可用性：用与生产代码完全相同的 new ServerSocket(port, 1) 试绑，
 *      绑上即"空闲"（试完立刻 close，不留任何占用）。
 *   2. AES / 加密能力：逐个 getInstance 并**实际跑一次运算**做往返校验，
 *      区分"类存在"与"真能用"（GCM 在 API 16 常是空壳）。
 *
 * 纯读取 + 内存内计算：不申请常驻服务、不改系统状态、**不落盘任何文件**。
 * 结果只显示在屏幕上（相机屏幕可直接看，截屏即可留档）。
 *
 * 按键（同时吃 keyCode 与 scanCode 两套，避免相机映射差异）：
 *   ENTER / 中键  → 立即重测
 *   UP / DOWN     → 滚动
 *   MENU          → 退出
 * 每 5 秒自动刷新端口（加密能力结果缓存，不重复跑）。
 *
 * 退出走 PMCA 官方路径：反射 DAConnectionManager.finish()（不打包任何 com.sony 类）。
 */
public class PortCheckActivity extends Activity {

    /** 相机端 scanCode 真值（与 SonyConnect 一致） */
    private static final int SC_UP = 103;
    private static final int SC_DOWN = 108;
    private static final int SC_ENTER = 232;
    private static final int SC_MENU = 514;

    /**
     * 候选端口：
     *   标准 PTP/IP 15740 及其邻近连续端口 → 找有没有空闲的；
     *   25740 系列与高位备用口；
     *   2121/2122（我们现用的 FTP / 私有协议口，作对照）。
     */
    private static final int[] CANDIDATES = {
            15740, 15741, 15742, 15743, 15744, 15745, 15746, 15747, 15748, 15749,
            25740, 25741, 25742, 35740, 45740, 55740,
            2121, 2122,
    };

    private static final long AUTO_REFRESH_MS = 5000L;
    private static final String TSX = "font";   // 占位，避免误删；见 setTextScaleX 用法

    private TextView body;
    private ScrollView scroll;
    private final Handler handler = new Handler();
    private volatile boolean busy = false;
    private volatile boolean destroyed = false;

    /** 加密能力结果缓存：自动刷新时只复用，不重算 */
    private volatile String cryptoCache = null;

    private final Runnable autoRefresh = new Runnable() {
        public void run() {
            if (destroyed) return;
            rescan(false);
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(8, 6, 8, 4);

        TextView title = new TextView(this);
        title.setText("AES 能力 + 端口可用性 · P0");
        title.setTextColor(Color.parseColor("#ffdd6600"));
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextScaleX(0.7001f);   // 相机 4:3 帧缓冲被拉到 16:9，索尼同款矫正值
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);

        body = new TextView(this);
        body.setTextColor(Color.parseColor("#ffdddddd"));
        body.setTextSize(20f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextScaleX(0.7001f);
        body.setLineSpacing(6f, 1f);
        body.setText("检测中…");
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("ENTER 重测 · 方向键 滚动 · MENU 退出");
        foot.setTextColor(Color.parseColor("#ff777777"));
        foot.setTextSize(20f);
        foot.setTextScaleX(0.7001f);
        root.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        rescan(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(autoRefresh);
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ===== 按键 =====

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int sc = event.getScanCode();
        if (keyCode == KeyEvent.KEYCODE_MENU || sc == SC_MENU) {
            exitApp();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || sc == SC_ENTER) {
            rescan(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || sc == SC_UP) {
            scroll.smoothScrollBy(0, -80);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || sc == SC_DOWN) {
            scroll.smoothScrollBy(0, 80);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 退出。相机是 PMCA 平台：官方的会话结束方式是 DAConnectionManager.finish()。
     * 该类是索尼私有 API，这里用反射调用（不打包任何 com.sony 类），失败再退回 finish()。
     */
    private void exitApp() {
        try {
            Class<?> c = Class.forName("android.app.DAConnectionManager");
            Object mgr = c.getConstructor(Context.class).newInstance(getApplicationContext());
            c.getMethod("finish").invoke(mgr);
        } catch (Throwable t) {
            // 拿不到就退回标准退出
        }
        finish();
    }

    // ===== 检测主流程 =====

    private void rescan(final boolean withCrypto) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                final String report = safeBuildReport(withCrypto);
                runOnUiThread(new Runnable() {
                    public void run() {
                        body.setText(report);
                        busy = false;
                    }
                });
            }
        }, "PortCheckScan").start();
    }

    /** 包一层异常兜底：保证 report 单次赋值，可被匿名类捕获 */
    private String safeBuildReport(boolean withCrypto) {
        try {
            return buildReport(withCrypto);
        } catch (Throwable t) {
            return "检测异常：" + t.getClass().getSimpleName() + " " + t.getMessage();
        }
    }

    private String buildReport(boolean withCrypto) {
        StringBuilder sb = new StringBuilder();

        sb.append(now()).append("  本机 ").append(primaryIpv4()).append('\n');

        // ---- 1. 候选端口 bind 测试 ----
        Map<Integer, String> listen = listenPorts();

        sb.append("\n===== 候选端口（同生产代码 bind 测试）=====\n");
        int free = 0, used = 0, nopriv = 0;
        for (int i = 0; i < CANDIDATES.length; i++) {
            int p = CANDIDATES[i];
            String r = testBind(p);
            String mark = "";
            if ("被占用".equals(r)) {
                if (listen.containsKey(p)) {
                    mark = "  ← 有监听";
                } else if (connectProbe(p)) {
                    mark = "  ← 有监听(connect 可达)";
                } else {
                    mark = "  ← 无监听(被保留)";
                }
            }
            if ("空闲".equals(r)) free++;
            else if ("被占用".equals(r)) used++;
            else nopriv++;
            sb.append(pad(p, 6)).append(pad(r, 10)).append(mark).append('\n');
        }
        sb.append("小计：空闲 ").append(free).append(" · 被占用 ").append(used)
                .append(" · 其它 ").append(nopriv).append('\n');

        // ---- 2. AES / 加密能力（缓存） ----
        if (withCrypto || cryptoCache == null) {
            cryptoCache = cryptoSection();
        }
        sb.append(cryptoCache);

        sb.append('\n').append("空闲=可绑定（我们能用）；被占用=bind 失败。\n");
        return sb.toString();
    }

    /** 与生产代码完全一致的绑定方式：new ServerSocket(port, 1)，默认 SO_REUSEADDR */
    private static String testBind(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port, 1);
            return "空闲";
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage()).toLowerCase();
            if (t instanceof SecurityException || m.indexOf("permission") >= 0
                    || m.indexOf("denied") >= 0) {
                return "无权限";
            }
            if (t instanceof java.net.BindException) {
                return "被占用";
            }
            return "错误-" + t.getClass().getSimpleName();
        } finally {
            if (ss != null) {
                try { ss.close(); } catch (Throwable t) { }
            }
        }
    }

    /** 本机回环 connect 探测：区分"真有监听"与"端口被保留" */
    private static boolean connectProbe(int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Throwable t) { }
            }
        }
    }

    /** 解析 /proc/net/tcp 与 tcp6，返回 监听端口 → 描述（仅用于给端口行做旁证） */
    private static Map<Integer, String> listenPorts() {
        Map<Integer, String> out = new LinkedHashMap<Integer, String>();
        collectListen("/proc/net/tcp", out);
        collectListen("/proc/net/tcp6", out);
        return out;
    }

    private static void collectListen(String path, Map<Integer, String> out) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();   // 跳过表头
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 4) continue;
                if (!"0A".equalsIgnoreCase(f[3])) continue;   // 0A = LISTEN
                String local = f[1];
                int colon = local.lastIndexOf(':');
                if (colon < 0) continue;
                int port;
                try {
                    port = Integer.parseInt(local.substring(colon + 1), 16);
                } catch (Throwable t) {
                    continue;
                }
                out.put(port, "监听中 (" + local.substring(0, colon) + ")");
            }
        } catch (Throwable t) {
            // 读不到就留空
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
    }

    // ===== AES / 加密能力 =====

    private String cryptoSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== AES / 加密能力（API 16 实跑校验）=====\n");

        // --- 密钥生成 ---
        boolean key128 = keyGenOk(128);
        sb.append(pad("AES-128 密钥生成", 24)).append(key128 ? "OK" : "✗ 失败").append('\n');

        boolean key256 = keyGenOk(256);
        sb.append(pad("AES-256 密钥生成", 24))
                .append(key256 ? "OK（无强度限制）" : "✗ 失败（可能受策略限制）").append('\n');

        // --- 分组密码：真跑往返 ---
        sb.append(cipherLine("AES/CBC/PKCS5Padding"));
        sb.append(cipherLine("AES/ECB/PKCS5Padding"));
        sb.append(cipherLine("AES/CTR/NoPadding"));
        sb.append(cipherLine("AES/CFB/NoPadding"));
        sb.append(cipherLine("AES/GCM/NoPadding"));

        // --- MAC ---
        boolean mac = macOk("HmacSHA256", 32);
        sb.append(pad("HmacSHA256", 24)).append(mac ? "OK (32B)" : "✗ 失败").append('\n');

        // --- 口令派生 ---
        boolean kdf1 = pbkdf2Ok("PBKDF2WithHmacSHA1");
        sb.append(pad("PBKDF2WithHmacSHA1", 24)).append(kdf1 ? "OK" : "✗ 失败").append('\n');

        boolean kdf2 = pbkdf2Ok("PBKDF2WithHmacSHA256");
        sb.append(pad("PBKDF2WithHmacSHA256", 24)).append(kdf2 ? "OK" : "✗ 失败").append('\n');

        // --- 摘要 / 随机 ---
        sb.append(pad("SHA-256 摘要", 24)).append(digestOk() ? "OK" : "✗ 失败").append('\n');
        sb.append(pad("SecureRandom 熵", 24))
                .append(randomOk() ? "OK (32B 非零)" : "✗ 失败").append('\n');

        // --- ECDH（若将来要密钥协商） ---
        sb.append(pad("ECDH 密钥协商", 24)).append(ecdhOk() ? "OK" : "✗ 不可用").append('\n');

        sb.append("--- 结论 ---\n");
        sb.append("CBC+HMAC(").append(mac ? "有" : "无").append(") / GCM(")
                .append(cipherOk("AES/GCM/NoPadding") ? "有" : "无").append(") → ")
                .append(mac ? "走 Encrypt-then-MAC" : "需另寻方案").append('\n');
        return sb.toString();
    }

    /** 真跑一次 AES/CBC 往返，返回可读结果行 */
    private static String cipherLine(String name) {
        String r = cipherRoundTrip(name);
        return pad(name, 24) + r + '\n';
    }

    /**
     * 对给定 transform 做一次真实的加密→解密往返：
     *   返回 "OK (16B→32B)" / "✗ NoSuchAlgorithm" / "✗ BadPadding" …
     * GCM 在 API 16 上通常 getInstance 就抛异常——正是我们要确认的事。
     */
    private static String cipherRoundTrip(String transform) {
        try {
            byte[] key = new byte[16];
            for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
            SecretKeySpec ks = new SecretKeySpec(key, "AES");

            Cipher enc = Cipher.getInstance(transform);
            byte[] iv = new byte[enc.getBlockSize() > 0 ? enc.getBlockSize() : 16];
            for (int i = 0; i < iv.length; i++) iv[i] = (byte) (0xA0 + i);

            byte[] plain = new byte[16];
            for (int i = 0; i < plain.length; i++) plain[i] = (byte) ('a' + (i % 26));

            if (needsIv(transform)) {
                enc.init(Cipher.ENCRYPT_MODE, ks, new IvParameterSpec(iv));
            } else {
                enc.init(Cipher.ENCRYPT_MODE, ks);
            }
            byte[] ct = enc.doFinal(plain);

            Cipher dec = Cipher.getInstance(transform);
            if (needsIv(transform)) {
                dec.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            } else {
                dec.init(Cipher.DECRYPT_MODE, ks);
            }
            byte[] rt = dec.doFinal(ct);

            boolean same = rt.length == plain.length;
            for (int i = 0; same && i < plain.length; i++) same = rt[i] == plain[i];

            if (!same) return "✗ 往返不一致";
            return "OK (" + plain.length + "B→" + ct.length + "B)";
        } catch (Throwable t) {
            return "✗ " + t.getClass().getSimpleName();
        }
    }

    private static boolean cipherOk(String transform) {
        return cipherRoundTrip(transform).startsWith("OK");
    }

    private static boolean needsIv(String transform) {
        return transform.indexOf("/CBC/") >= 0 || transform.indexOf("/CTR/") >= 0
                || transform.indexOf("/CFB/") >= 0 || transform.indexOf("/OFB/") >= 0
                || transform.indexOf("/GCM/") >= 0;
    }

    private static boolean keyGenOk(int bits) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(bits);
            SecretKey k = kg.generateKey();
            return k != null && "AES".equals(k.getAlgorithm()) && k.getEncoded() != null
                    && k.getEncoded().length == bits / 8;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean macOk(String algo, int expectLen) {
        try {
            byte[] key = new byte[16];
            for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 3);
            Mac m = Mac.getInstance(algo);
            m.init(new SecretKeySpec(key, algo));
            byte[] out = m.doFinal("sonyconnect".getBytes("UTF-8"));
            return out != null && out.length == expectLen;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean pbkdf2Ok(String algo) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance(algo);
            PBEKeySpec spec = new PBEKeySpec("pw".toCharArray(),
                    "0123456789abcdef".getBytes("UTF-8"), 1000, 128);
            SecretKey k = f.generateSecret(spec);
            byte[] b = k.getEncoded();
            return b != null && b.length == 16;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean digestOk() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest("sonyconnect".getBytes("UTF-8"));
            return d != null && d.length == 32;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean randomOk() {
        try {
            byte[] b = new byte[32];
            SecureRandom r = new SecureRandom();
            r.nextBytes(b);
            for (int i = 0; i < b.length; i++) {
                if (b[i] != 0) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ECDH：API 16 上 KeyAgreement("ECDH") 是否存在且能跑完两步协商 */
    private static boolean ecdhOk() {
        try {
            KeyPairX a = genEc();
            KeyPairX b = genEc();
            if (a == null || b == null) return false;
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(a.priv);
            ka.doPhase(b.pub, true);
            byte[] s1 = ka.generateSecret();
            KeyAgreement kb = KeyAgreement.getInstance("ECDH");
            kb.init(b.priv);
            kb.doPhase(a.pub, true);
            byte[] s2 = kb.generateSecret();
            if (s1 == null || s2 == null || s1.length != s2.length) return false;
            for (int i = 0; i < s1.length; i++) {
                if (s1[i] != s2[i]) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 极小的密钥对容器，避免顶层 import java.security.KeyPair 之类造成困惑 */
    private static final class KeyPairX {
        final java.security.PrivateKey priv;
        final java.security.PublicKey pub;
        KeyPairX(java.security.PrivateKey priv, java.security.PublicKey pub) {
            this.priv = priv;
            this.pub = pub;
        }
    }

    private static KeyPairX genEc() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            java.security.KeyPair kp = g.generateKeyPair();
            return new KeyPairX(kp.getPrivate(), kp.getPublic());
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== 网络 =====

    private static String primaryIpv4() {
        List<String> ips = localIpv4();
        if (ips.isEmpty()) return "(无 IPv4)";
        return ips.get(0);
    }

    private static List<String> localIpv4() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis == null) return out;
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (ni.isLoopback() || !ni.isUp()) continue;
                } catch (Throwable t) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    String h = a.getHostAddress();
                    if (a instanceof java.net.Inet4Address && h != null
                            && !h.startsWith("127.")) {
                        out.add(h);
                    }
                }
            }
            Collections.sort(out);
        } catch (Throwable t) {
            // 忽略
        }
        return out;
    }

    private static String now() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    private static String pad(int v, int width) {
        return pad(String.valueOf(v), width);
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}
