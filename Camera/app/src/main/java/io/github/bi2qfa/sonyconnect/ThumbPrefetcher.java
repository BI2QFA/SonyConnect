package io.github.bi2qfa.sonyconnect;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 缩略图预取器（纯 Java，桌面可测）。
 *
 * 手机下发小图清单后，**两条常驻 worker** 从队列里一次领一小批（{@link #MICRO_BATCH} 张）
 * 顺序提取进有界 LRU 缓存；{@link PtpCameraHandler} 取小图时先查这里，命中即省去重复解析。
 * 未命中仍会实时提取，功能不受影响。
 *
 * <h3>为什么是 2 条 worker + 微批（2.6）</h3>
 * 相机是单核弱 CPU + SD 卡：纯并发不会更快，但**一个线程干等 IO 时另一个能顶上**，
 * 实测比单线程有明显收益；而"一次领一批顺序解"又比"一次领一张来回抢锁"少很多
 * 锁竞争与随机寻道。两者叠加就是"2 路 × 8 张微批"。
 *
 * <p>状态机：IDLE → RUNNING ⇄ PAUSED → DONE/CANCELLED。
 * 提取过程即开即关文件句柄；{@link #stop()} 逐线程 join（铁律：不留 SD 占用）。
 */
public class ThumbPrefetcher {

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";

    /**
     * 缓存条目上限。
     *
     * <p>典型目录是 300–400 张照片；清单不再截断后可能上千张（2.6：手机端去掉了 200 上限），
     * 所以这里放到 1024：一支小缩略图约 6 KB，1024 条约 6 MB —— 相机堆能承受，
     * 换来的是一次预热覆盖整个目录，用户滚到哪都命中。
     */
    private static final int MAX_ENTRIES = 1024;

    /** 常驻 worker 数（2.6：单线程 → 2）。回退改 1 即恢复旧行为。 */
    private static final int PREFETCH_WORKERS = 2;

    /** 一次领多少张（顺序解，减少锁竞争与随机寻道）。回退改 1 即"一次一张"。 */
    private static final int MICRO_BATCH = 8;

    private final File rootDir;
    private final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private final Object lock = new Object();
    private final List<Thread> workers = new ArrayList<Thread>();
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<String>();
    /** 已领出、正在解的张数（队列空了但还有在飞的，状态不能判 IDLE）。 */
    private int inFlight = 0;
    private String state = STATE_IDLE;
    private int doneCount = 0;
    private int totalCount = 0;
    private boolean cancelled = false;

    /** 微批解图结果回调：进锁计数 + 入缓存（解不出来也要计数，进度才会走）。 */
    private final ThumbnailExtractor.SmallCallback onThumb =
            new ThumbnailExtractor.SmallCallback() {
                public void onThumb(String relPath, byte[] jpg) {
                    synchronized (lock) {
                        doneCount++;
                        if (jpg != null) {
                            cache.put(relPath, jpg);
                        }
                    }
                }
            };

    public ThumbPrefetcher(File rootDir) {
        this.rootDir = rootDir;
    }

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
            queue.clear();
            if (relPaths != null) {
                // ★ 2.6：去重用 HashSet。名单不再截断后可能上千条，
                //   原来的 List.contains 是 O(n²)，上千条会明显卡住这条线程。
                HashSet<String> seen = new HashSet<String>();
                for (String p : relPaths) {
                    if (p != null && p.length() > 0 && seen.add(p)) {
                        queue.add(p);
                    }
                }
            }
            totalCount = queue.size();
            doneCount = 0;
            state = totalCount == 0 ? STATE_DONE : STATE_RUNNING;
            AppLog.i("Thumb", "预取开始：共 " + totalCount + " 张（" + PREFETCH_WORKERS + " 路解图）");
            ensureWorkersLocked();
            lock.notifyAll();
        }
    }

    /**
     * 保证有 {@link #PREFETCH_WORKERS} 条 worker 在跑（调用方须持有 lock）。
     *
     * <p>常驻而非"每次 begin 新建"：省掉反复建线程的开销，也让 stop() 的
     * "逐线程 join"有明确的清单可依；死掉的（理论上只有被中断的）顺手剔掉。
     */
    private void ensureWorkersLocked() {
        for (int i = workers.size() - 1; i >= 0; i--) {
            if (!workers.get(i).isAlive()) {
                workers.remove(i);
            }
        }
        while (workers.size() < PREFETCH_WORKERS) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    workLoop();
                }
            }, "ThumbPrefetch-" + (workers.size() + 1));
            t.setDaemon(true);
            workers.add(t);
            t.start();
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
            queue.clear();
            doneCount = 0;
            totalCount = 0;
            inFlight = 0;
            state = STATE_IDLE;
            lock.notifyAll();
        }
    }

    /** 退出流程用：停全部 worker、清队列（extractor 自身即开即关句柄，无句柄残留） */
    public void stop() {
        AppLog.i("Thumb", "预取停止（已提取 " + doneCount + "/" + totalCount + "）");
        List<Thread> toJoin;
        synchronized (lock) {
            cancelled = true;
            queue.clear();
            state = STATE_IDLE;
            lock.notifyAll();
            toJoin = new ArrayList<Thread>(workers);
            workers.clear();
        }
        // ★ 必须等它们**真的退出**再返回。
        //   某条 worker 可能正卡在 SD 卡的一次读取里（extractSmall 期间的
        //   RandomAccessFile 由 finally 关），而退出链最后会调
        //   DAConnectionManager.finish() 结束进程 —— 进程若在这个窗口被收掉，
        //   卡上就留下一个没归位的占用，相机下次开机会挂"正在修复数据"。
        //   等的是"读完手上这一批"，一批小缩略图远不到 2 秒；等不到也不强求，
        //   超时后照常返回，绝不让退出流程被它拖死。
        for (int i = 0; i < toJoin.size(); i++) {
            Thread t = toJoin.get(i);
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ===== 工作线程 =====

    /**
     * 领微批（持锁）→ 锁外解图 → 回锁结账。
     *
     * <p>★ 解图**绝不能持锁**：那是 SD IO，持锁期间另一条 worker 只能干等，
     * 2 路并发就退化成串行了。所以是"领批（快）→ 出锁 → 解 → 回锁"。
     */
    private void workLoop() {
        while (true) {
            List<String> batch;
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
                        // 队列空：在飞的收完才算真闲下来
                        if (inFlight == 0) state = STATE_IDLE;
                        try {
                            lock.wait(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    batch = new ArrayList<String>(MICRO_BATCH);
                    for (int i = 0; i < MICRO_BATCH; i++) {
                        String p = queue.poll();
                        if (p == null) break;
                        batch.add(p);
                    }
                    inFlight += batch.size();
                    break;
                }
            }

            // 锁外解（文件 IO）
            ThumbnailExtractor.extractSmallBatch(rootDir, batch, onThumb);

            synchronized (lock) {
                inFlight -= batch.size();
                if (inFlight < 0) inFlight = 0;
                if (queue.isEmpty() && inFlight == 0) state = STATE_IDLE;
                lock.notifyAll();
            }
        }
    }
}
