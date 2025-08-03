package nu.marginalia.array.pool;

import nu.marginalia.array.page.UnsafeLongArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

/** LRU for pool buffers
 * */
public class PoolLru {
    private static final Logger logger = LoggerFactory.getLogger(PoolLru.class);

    private final int maxSize;
    private final LinkedHashMap<Long, UnsafeLongArrayBuffer> backingMap;
    private final UnsafeLongArrayBuffer[] pages;

    private final int[] freeQueue;
    private final int[] clock;
    private int clockHand = 0;
    private volatile long reclaimCycles;
    private AtomicLong clockWriteIdx;
    private AtomicLong clockReadIdx;

    private final StampedLock lock = new StampedLock();
    Thread reclaimThread;

    private volatile boolean running = true;

    public PoolLru(UnsafeLongArrayBuffer[] pages) {
        backingMap = new LinkedHashMap<>(pages.length, 0.75f);
        this.pages = pages;
        // Pre-assign all entries with nonsense memory locations
        for (int i = 0; i < pages.length; i++) {
            backingMap.put(-i-1L, pages[i]);
        }
        maxSize = backingMap.size();

        clock = new int[pages.length];
        freeQueue = new int[pages.length];

        for (int i = 0; i < freeQueue.length; i++) {
            freeQueue[i] = i;
        }

        clockReadIdx = new AtomicLong();
        clockWriteIdx = new AtomicLong(freeQueue.length);

        reclaimThread = Thread.ofPlatform().start(this::reclaimThread);
    }

    public void stop() throws InterruptedException {
        running = false;
        reclaimThread.interrupt();
        reclaimThread.join();
    }
    /** Attempt to get a buffer already associated with the address */
    public UnsafeLongArrayBuffer get(long address) {
        var res = getAssociatedItem(address);
        if (res != null) {
            long stamp = lock.writeLock();
            try {
                clock[res.ord]+=2;
            }
            finally {
                lock.unlockWrite(stamp);
            }
        }
        return res;
    }

    private UnsafeLongArrayBuffer getAssociatedItem(long address) {
        long stamp = lock.tryOptimisticRead();
        UnsafeLongArrayBuffer res = backingMap.get(address);
        if (lock.validate(stamp)) {
            return res;
        }
        stamp = lock.readLock();
        try {
            return backingMap.get(address);
        }
        finally {
            lock.unlockRead(stamp);
        }
    }

    /** Associate the buffer with an address */
    public void register(UnsafeLongArrayBuffer buffer) {
        long stamp = lock.writeLock();
        try {
            backingMap.put(buffer.pageAddress(), buffer);
            clock[buffer.ord]++;
            // Evict the last entry if we've exceeded the
            while (backingMap.size() >= maxSize) {
                backingMap.pollFirstEntry();
            }
        }
        finally {
            lock.unlockWrite(stamp);
        }
    }

    /** Attempt to get a free buffer from the pool
     *
     * @return An unheld buffer, or null if the attempt failed
     * */
    public UnsafeLongArrayBuffer getFree() {
        for (; ; ) {
            var readIdx = clockReadIdx.get();
            var writeIdx = clockWriteIdx.get();
            if (writeIdx - readIdx == freeQueue.length / 4) {
                LockSupport.unpark(reclaimThread);
            }
            if (readIdx == writeIdx) {
                LockSupport.unpark(reclaimThread);
                synchronized (this) {
                    try {
                        wait(0, 1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                continue;
            }
            if (clockReadIdx.compareAndSet(readIdx, readIdx + 1)) {
                return pages[freeQueue[(int) (readIdx % freeQueue.length)]];
            }
        }
    }

    private void reclaimThread() {
        while (running) {
            long readIdx = clockReadIdx.get();
            long writeIdx = clockWriteIdx.get();
            int queueSize = (int) (writeIdx - readIdx);
            int targetQueueSize = freeQueue.length / 2;

            if (queueSize >= targetQueueSize) {
                LockSupport.parkNanos(100_000);
                continue;
            }

            int toClaim = targetQueueSize - queueSize;
            if (toClaim == 0)
                continue;

            reclaimCycles++;
            do {
                int idx = clockHand++ % clock.length;
                if (pages[idx].isCached()) {
                    if (clock[idx] <= 0) {
                        if (pages[idx].reclaim()) {
                            freeQueue[(int) (clockWriteIdx.getAndIncrement() % freeQueue.length)] = idx;
                            toClaim--;
                        }
                    }
                    else {
                        clock[idx]--;
                    }
                }
            } while (toClaim >= 0);

            synchronized (this) {
                notifyAll();
            }
        }
    }

    public int getFreeQueueSize() {
        return (int) (clockWriteIdx.get() - clockReadIdx.get());
    }

    public long getReclaimCycles() {
        return reclaimCycles;
    }
}
