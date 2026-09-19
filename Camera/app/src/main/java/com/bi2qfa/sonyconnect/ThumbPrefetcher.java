package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缩略图预取器（纯 Java，桌面可测）。
 *
 * 手机 THUMB_BEGIN 提交路径清单后，工作线程按顺序把小缩略图提取进有界 LRU
 * 缓存；FtpServer 的虚拟 RETR 经 PayloadSource 钩子先查这里，命中即省去
 * 重复解析（相机 CPU 弱）。未命中时 FtpServer 仍会实时提取，功能不受影响。
 *
 * 状态机：IDLE → RUNNING ⇄ PAUSED → DONE/CANCELLED（协议 INFO 上报用）。
 * 提取过程即开即关文件句柄；stop() 只需停线程（铁律：不留 SD 占用）。
 */
public class ThumbPrefetcher {

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";

    /**
     * 缓存条目上限。
     *
     * <p>典型目录是 300–400 张照片。上限若只有 64，预取刚跑完就开始自我淘汰 ——
     * 用户滚到后面时前面的已经不在缓存里，预取等于白干。一支小缩略图约 6 KB，
     * 256 条约占 1.5 MB，对相机那点堆内存是可接受的代价；剩下没进缓存的
     * 由按需提取兜住（服务端还有一层单条记忆，见 PtpCameraHandler）。
     */
    private static final int MAX_ENTRIES = 256;

    private final File rootDir;
    private final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private final Object lock = new Object();
    private Thread worker;
    private List<String> queue = new ArrayList<String>();
    private String state = STATE_IDLE;
    private int doneCount = 0;
    private int totalCount = 0;
    private boolean cancelled = false;

    public ThumbPrefetcher(File rootDir) {
        this.rootDir = rootDir;
    }

    // ===== 供 FtpServer 调用（虚拟 RETR 的缓存直读） =====

    /** 命中返回缓存字节；未命中返回 null（调用方回落实时提取） */
    public byte[] lookup(String relPath) {
        synchronized (lock) {
            return cache.get(relPath);
        }
    }

    // ===== 协议命令 =====

    public void begin(List<String> relPaths) {
        synchronized (lock) {
            cancelled = false;
            queue = new ArrayList<String>();
            if (relPaths != null) {
                for (String p : relPaths) {
                    if (p != null && p.length() > 0 && !queue.contains(p)) {
                        queue.add(p);
                    }
                }
            }
            totalCount = queue.size();
            doneCount = 0;
            state = totalCount == 0 ? STATE_DONE : STATE_RUNNING;
            AppLog.i("Thumb", "预取开始：共 " + totalCount + " 张");
            if (worker == null || !worker.isAlive()) {
                worker = new Thread(new Runnable() {
                    public void run() {
                        workLoop();
                    }
                }, "ThumbPrefetch");
                worker.setDaemon(true);
                worker.start();
            } else {
                lock.notifyAll();
            }
        }
    }

    public void pause() {
        synchronized (lock) {
            if (STATE_RUNNING.equals(state)) state = STATE_PAUSED;
        }
    }

    public void resume() {
        synchronized (lock) {
            if (STATE_PAUSED.equals(state)) state = STATE_RUNNING;
            lock.notifyAll();
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            queue = new ArrayList<String>();
            totalCount = doneCount = 0;
            state = STATE_IDLE;
            lock.notifyAll();
        }
    }

    /** 退出流程用：停线程、清缓存（extractor 自身即开即关句柄，无句柄残留） */
    public void stop() {
        AppLog.i("Thumb", "预取停止（已提取 " + doneCount + "/" + totalCount + "）");
        Thread t;
        synchronized (lock) {
            cancelled = true;
            queue = new ArrayList<String>();
            state = STATE_IDLE;
            lock.notifyAll();
            t = worker;
            worker = null;
        }
        if (t == null) return;
        t.interrupt();
        // ★ 必须等它**真的退出**再返回。
        //   工作线程可能正卡在 SD 卡的一次缩略图读取里（extractSmall 期间的
        //   RandomAccessFile 由 finally 关），而退出链最后会调
        //   DAConnectionManager.finish() 结束进程 —— 进程若在这个窗口被收掉，
        //   卡上就留下一个没归位的占用，相机下次开机会挂"正在修复数据"。
        //   等的是"读完手上这一张"，一张小缩略图远不到 2 秒；等不到也不强求，
        //   超时后照常返回，绝不让退出流程被它拖死。
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String getState() {
        synchronized (lock) {
            return state;
        }
    }

    public int getDone() {
        synchronized (lock) {
            return doneCount;
        }
    }

    public int getTotal() {
        synchronized (lock) {
            return totalCount;
        }
    }

    // ===== 工作线程 =====

    private void workLoop() {
        while (true) {
            String relPath;
            synchronized (lock) {
                while (true) {
                    if (cancelled) return;
                    if (STATE_PAUSED.equals(state)) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return; // stop()
                        }
                        continue;
                    }
                    if (queue.isEmpty()) {
                        state = STATE_IDLE;
                        return;
                    }
                    break;
                }
                relPath = queue.get(0);
            }

            // 提取在锁外做（文件 IO 不持锁）
            byte[] payload = null;
            File f = new File(rootDir, relPath);
            if (f.isFile() && ThumbnailExtractor.supports(f)) {
                try {
                    payload = ThumbnailExtractor.extractSmall(f);
                } catch (IOException e) {
                    payload = null;
                } catch (Throwable t) {
                    payload = null;
                }
            }

            synchronized (lock) {
                if (!queue.isEmpty() && relPath.equals(queue.get(0))) {
                    queue.remove(0);
                }
                doneCount++;
                if (payload != null) {
                    cache.put(relPath, payload);
                }
                if (queue.isEmpty()) state = STATE_IDLE;
            }
        }
    }
}
