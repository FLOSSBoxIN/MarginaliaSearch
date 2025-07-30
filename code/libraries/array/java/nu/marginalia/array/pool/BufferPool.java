package nu.marginalia.array.pool;

import nu.marginalia.NativeAlgos;
import nu.marginalia.array.algo.LongArrayBuffer;
import nu.marginalia.array.page.UnsafeLongArrayBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BufferPool implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BufferPool.class);

    private final UnsafeLongArrayBuffer[] pages;
    private final Arena arena;
    private final int fd;
    private final int pageSize;
    private final Thread readaheadThread;
    private volatile UnsafeLongArrayBuffer lastAccessedBuffer;

    final AtomicInteger diskReadCount = new AtomicInteger();
    final AtomicInteger cacheReadCount = new AtomicInteger();
    final AtomicInteger readaheadFetchCount = new AtomicInteger();

    private final ArrayBlockingQueue<BufferPoolFetchInstruction> instructionsQueue = new ArrayBlockingQueue<>(16);

    private volatile boolean running = true;

    public BufferPool(Path filename, int pageSizeBytes, int poolSize) {
        this.fd = NativeAlgos.openDirect(filename);
        this.pageSize = pageSizeBytes;

        this.arena = Arena.ofShared();
        this.pages = new UnsafeLongArrayBuffer[poolSize];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new UnsafeLongArrayBuffer(arena.allocate(pageSizeBytes, 8));
        }

        readaheadThread = Thread.ofPlatform().start(this::readaheadThread);
    }

    public void close() throws InterruptedException {
        running = false;
        readaheadThread.interrupt();
        readaheadThread.join();

        NativeAlgos.closeFd(fd);
        arena.close();

        System.out.println("Disk read count: " + diskReadCount.get());
        System.out.println("Cached read count: " + cacheReadCount.get());
        System.out.println("Readahead fetch count: " + readaheadFetchCount.get());
    }

    public void readaheadThread() {
        try {
            mainLoop:
            while (running) {
                BufferPoolFetchInstruction instruction = instructionsQueue.take();

                for (var page : pages) {
                    // Check if we already hold the address
                    if (page.pageAddress() == instruction.address())
                        continue mainLoop;
                }

                var page = acquireFreePage(instruction.address());
                if (page.pinCount().get() != -1) {
                    throw new RuntimeException();
                }
                populateBuffer(page);
                page.pageAddress(instruction.address());

                // Mark the page as
                if (!page.pinCount().compareAndSet(-1, 0)) {
                    throw new IllegalStateException("Unexpected pin state");
                }
                readaheadFetchCount.incrementAndGet();
            }
        }
        catch (InterruptedException ex) {
            logger.info("readAhead thread interrupted");
        }
    }

    public void submitInstructions(BufferPoolFetchInstruction... instructions) {
        for (var instruction : instructions) {
            if (!instructionsQueue.offer(instruction))
                break;
        }
    }
    public void submitInstruction(BufferPoolFetchInstruction instruction) {
        instructionsQueue.offer(instruction);
    }

    @Nullable
    public UnsafeLongArrayBuffer getExistingBufferForReading(long address) {

        // Fast path for checking the last buffer we accessed

        var lastAccessedBuffer = this.lastAccessedBuffer;
        if (lastAccessedBuffer != null && lastAccessedBuffer.pageAddress() == address) {
            if (lastAccessedBuffer.acquireAsReader(address)) {
                cacheReadCount.incrementAndGet();
                return lastAccessedBuffer;
            }
        }

        for (var page : pages) {

            if (page.pageAddress() != address) continue;

            // Try to acquire the page normally
            if (page.acquireAsReader(address)) {
                cacheReadCount.incrementAndGet();

                return page;
            }

            if (page.pageAddress() != address)
                continue;

            // The page we are looking for is currently being written
            waitForPageWrite(page);

            if (page.acquireAsReader(address)) {
                this.cacheReadCount.incrementAndGet();
                return page;
            }
        }
        return null;
    }

    public UnsafeLongArrayBuffer get(long address, BufferEvictionPolicy eviction, BufferReadaheadPolicy readahead) {

        // Look through available pages for the one we're looking for
        UnsafeLongArrayBuffer buffer = getExistingBufferForReading(address);

        if (buffer == null) {
            // If the page is not available, read it from the caller's thread
            buffer = acquireFreePage(address);
            populateBuffer(buffer);
            buffer.evictionPolicy(eviction);
            buffer.access();

            // Flip from a write lock to a read lock immediately
            if (!buffer.pinCount().compareAndSet(-1, 1)) {
                throw new IllegalStateException("Panic! Write lock was not held during write!");
            }

            diskReadCount.incrementAndGet();
        }

        if (eviction != BufferEvictionPolicy.READ_ONCE)
            this.lastAccessedBuffer = buffer;

        switch (readahead) {
            case NONE -> {}
            case SMALL -> submitInstruction(new BufferPoolFetchInstruction(PoolInstructionPriority.READAHEAD, eviction, address + pageSize));
            case AGGRESSIVE -> submitInstructions(
                                    new BufferPoolFetchInstruction(PoolInstructionPriority.READAHEAD, eviction, address + pageSize),
                                    new BufferPoolFetchInstruction(PoolInstructionPriority.READAHEAD, eviction, address + 2L*pageSize),
                                    new BufferPoolFetchInstruction(PoolInstructionPriority.READAHEAD, eviction, address + 3L*pageSize)
                                );
        }

        return buffer;
    }

    private UnsafeLongArrayBuffer acquireFreePage(long address) {
        // First try to grab any page that's not have cached data

        for (var page : pages) {
            if (page.isHeld())
                continue;
            if (page.pageAddress() != -1)
                continue;

            if (page.acquireForWriting(address))
                return page;
        }

        // Among the pages that do have cached data, try to find the oldest

        long[] lruOrder = new long[pages.length];
        int lruCnt = 0;

        // Sort buffers by access time
        for (int i = 0; i < pages.length; i++) {
            var page = pages[i];

            if (page.isHeld())
                continue;

            long sortOrder = (page.accessOrder() << 10) | i;
            lruOrder[lruCnt++] = sortOrder;
        }

        Arrays.sort(lruOrder, 0, lruCnt);

        for (;;) {
            for (int i = 0; i < lruCnt; i++) {
                int idx = (int) (lruOrder[i] & ~((~0L) << 10));
                var page = pages[idx];

                if (page.isHeld())
                    continue;
                if (page.acquireForWriting(address))
                    return page;
            }
        }

        // FIXME
//        throw new IllegalStateException("No free pages!");

    }

    private void populateBuffer(LongArrayBuffer buffer) {
        NativeAlgos.readAt(fd, buffer.getMemorySegment(), buffer.pageAddress());
        buffer.dirty(false);
    }

    private void waitForPageWrite(LongArrayBuffer page) {
        if (!page.dirty()) {
            return;
        }

        synchronized (page) {
            while (page.dirty()) {
                try {
                    page.wait(1);
                }
                catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }


}
