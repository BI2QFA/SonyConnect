package com.bi2qfa.sonyconnect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ThumbPrefetcher {

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";

    private static final int MAX_ENTRIES = 64;

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

    public byte[] lookup(String relPath) {
        synchronized (lock) {
            return cache.get(relPath);
        }
    }

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

    public void stop() {
        synchronized (lock) {
            cancelled = true;
            queue = new ArrayList<String>();
            state = STATE_IDLE;
            lock.notifyAll();
            if (worker != null) worker.interrupt();
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
                            return;
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
