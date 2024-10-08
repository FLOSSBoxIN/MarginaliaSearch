package nu.marginalia.array.algo;

import nu.marginalia.array.LongArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

@SuppressWarnings("preview")
public interface LongArrayBase extends BulkTransferArray<LongBuffer> {

    /** Get a value from the array at the specified position */
    long get(long pos);

    /** Set a value in the array at the specified position */
    void set(long pos, long value);


    /** Return the memory segment backing the array */
    MemorySegment getMemorySegment();

    /** Set a sequence of value in the array starting at the specified position */
    default void set(long pos, long... value) {
        for (int i = 0; i < value.length; i++) {
            set(pos+i, value[i]);
        }
    }

    /** Return the size of the array */
    long size();


    /** Fill the array with the specified value at the provided range */
    default void fill(long start, long end, long val) {
        for (long v = start; v < end; v++) {
            set(v, val);
        }
    }

    default void increment(long pos) {
        set(pos, get(pos) + 1);
    }

    default void swap(long pos1, long pos2) {
        long tmp = get(pos1);
        set(pos1, get(pos2));
        set(pos2, tmp);
    }

    /** Behavior not defined for overlapping ranges */
    default void swapn(int n, long pos1, long pos2) {
        for (int i = 0; i < n; i++) {
            long tmp = get(pos1+i);
            set(pos1+i, get(pos2+i));
            set(pos2+i, tmp);
        }
    }

    default void swap2(long pos1, long pos2) {
        long tmp = get(pos1);
        set(pos1, get(pos2));
        set(pos2, tmp);

        tmp = get(pos1 + 1);
        set(pos1 + 1, get(pos2 + 1));
        set(pos2 + 1, tmp);
    }

    default long getAndIncrement(long pos) {
        long val = get(pos);
        set(pos, val + 1);
        return val;
    }

    default void set(long start, long end, LongBuffer buffer, int bufferStart) {
        for (int i = 0; i < (end-start); i++) {
            set(start+i, buffer.get(i + bufferStart));
        }
    }

    default void get(long start, long end, LongBuffer buffer, int bufferStart) {
        for (int i = 0; i < (end-start); i++) {
            buffer.put(i + bufferStart, get(start + i));
        }
    }

    default void get(long start, long end, LongArray buffer, int bufferStart) {
        for (int i = 0; i < (end-start); i++) {
            buffer.set(i + bufferStart, get(start + i));
        }
    }

    default void get(long start, LongBuffer buffer) {
        get(start, start + buffer.remaining(), buffer, buffer.position());
    }

    default void get(long start, long end, long[] buffer) {
        for (long i = 0; i < (end-start); i++) {
            buffer[(int) i] = get(start + i);
        }
    }
    default void get(long start, long[] buffer) {
        get(start, start + buffer.length, buffer);
    }

    void write(Path file) throws IOException;

    void transferFrom(FileChannel source, long sourceStart, long arrayStart, long arrayEnd) throws IOException;
    void transferFrom(LongArray source, long sourceStart, long arrayStart, long arrayEnd) throws IOException;
}
