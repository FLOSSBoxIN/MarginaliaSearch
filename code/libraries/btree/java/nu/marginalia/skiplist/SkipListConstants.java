package nu.marginalia.skiplist;

public class SkipListConstants {
    public static final int BLOCK_SIZE = 65536;
    static final int MIN_TRUNCATED_BLOCK_SIZE = Math.min(512, BLOCK_SIZE / 4);

    static final int HEADER_SIZE = 8;
    static final int SEGREGATED_HEADER_SIZE = 16;
    static final int RECORD_SIZE = 2;
    static final int MAX_RECORDS_PER_BLOCK = (BLOCK_SIZE/8 - 2);

    static final byte FLAG_END_BLOCK = 1<<0;


    static int skipOffsetForPointer(int pointerIdx) {
        return (1 << (pointerIdx));
    }

    static int numPointersForBlock(int blockIdx) {
        assert blockIdx >= 1;
        return Math.max(16, Integer.numberOfTrailingZeros(blockIdx));
    }

    static int numPointersForRootBlock(int n) {
        return Math.max(0, Integer.numberOfTrailingZeros(Integer.highestOneBit(estimateNumBlocks(n))));
    }

    static int rootBlockCapacity(int rootBlockSize, int n) {
        return Math.min(n, (rootBlockSize - SEGREGATED_HEADER_SIZE - 8 * numPointersForRootBlock(n)) / 8);
    }

    static int nonRootBlockCapacity(int blockIdx) {
        assert blockIdx >= 1;
        return (BLOCK_SIZE - SEGREGATED_HEADER_SIZE - 8 * numPointersForBlock(blockIdx)) / 8;
    }

    static int estimateNumBlocks(int n) {
        return n / MAX_RECORDS_PER_BLOCK + Integer.signum(n % MAX_RECORDS_PER_BLOCK);
    }

    public static int pageDataOffset(int baseBlockOffset, int fc) {
        return baseBlockOffset + 8 * (2 + fc);
    }
}
