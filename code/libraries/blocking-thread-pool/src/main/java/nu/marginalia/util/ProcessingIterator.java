package nu.marginalia.util;

import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Abstraction for exposing a (typically) read-from-disk -> parallel processing -> sequential output
 * workflow as an iterator, where the number of tasks is much larger than the number of cores
 */
public class ProcessingIterator<T> implements Iterator<T> {
    private static final Logger logger = LoggerFactory.getLogger(ProcessingIterator.class);

    private final LinkedBlockingQueue<T> queue;
    private final AtomicBoolean isFinished = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final Semaphore sem;

    private T next = null;

    private final int parallelism;

    public ProcessingIterator(int queueSize, int parallelism, ProcessingJob<T> task) {
        this.parallelism = parallelism;

        queue = new LinkedBlockingQueue<>(queueSize);
        executorService = Executors.newFixedThreadPool(parallelism);
        sem = new Semaphore(parallelism);

        executorService.submit(() -> executeJob(task));
    }

    private void executeJob(ProcessingJob<T> job) {
        try {
            job.run(this::executeTask);
        } catch (Exception e) {
            logger.warn("Exception while processing", e);
        } finally {
            isFinished.set(true);
        }
    }

    private void executeTask(Task<T> task) {
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            return;
        }

        executorService.submit(() -> {
            try {
                queue.put(task.get());
            } catch (Exception e) {
                logger.warn("Exception while processing", e);
            } finally {
                sem.release();
            }
        });
    }

    /** Returns true if there are more documents to be processed.
     * This method may block until we are certain this is true.
     * <p>
     * This method must be invoked from the same thread that invokes next(),
     * (or synchronize between the two)
     */
    @Override
    @SneakyThrows
    public boolean hasNext() {
        if (next != null)
            return true;

        do {
            next = queue.poll(50, TimeUnit.MILLISECONDS);
            if (next != null) {
                return true;
            }
        } while (expectMore());

        if (!executorService.isShutdown()) {
            executorService.shutdown();
        }

        return false;
    }

    /** Heuristic for if we should expect more documents to be processed,
     * _trust but verify_ since we don't run this in an exclusive section
     * and may get a false positive.  We never expect a false negative though.
     */
    private boolean expectMore() {
        return !isFinished.get() // we are still reading from the database
                || !queue.isEmpty()   // ... or we have documents in the queue
                || sem.availablePermits() < parallelism;  // ... or we are still processing documents
    }

    /** Returns the next document to be processed.
     * This method may block until we are certain there is a document to be processed.
     * <p>
     * This method must be invoked from the same thread that invokes hasNext(),
     * (or synchronize between the two)
     * <p>
     * If this is run after hasNext() returns false, a NoSuchElementException is thrown.
     */
    @SneakyThrows
    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        try {
            return next;
        }
        finally {
            next = null;
        }
    }

    /**
     * A job that produces a sequence of processing tasks that are to be
     * performed in parallel
     */
    public interface ProcessingJob<T2> {
        void run(Consumer<Task<T2>> output) throws Exception;
    }

    /**
     * A single task that produces a result to be iterable via the Iterator interface
     * (along with other tasks' outputs)
     */
    public interface Task<T> {
        T get() throws Exception;
    }
}
