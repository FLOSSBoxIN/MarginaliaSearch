package nu.marginalia.array.page;

import nu.marginalia.array.algo.BulkTransferArray;
import nu.marginalia.array.functional.AddressRangeCall;
import nu.marginalia.array.functional.AddressRangeCallIO;
import nu.marginalia.array.scheme.ArrayPartitioningScheme;

import java.io.IOException;

import static nu.marginalia.array.algo.LongArraySearch.decodeSearchMiss;
import static nu.marginalia.array.algo.LongArraySearch.encodeSearchMiss;

public class AbstractPagingArray<T extends BulkTransferArray<B>, B> {
    final T[] pages;
    final long size;
    final ArrayPartitioningScheme partitioningScheme;

    public AbstractPagingArray(ArrayPartitioningScheme partitioningScheme, T[] pages, long size) {
        this.partitioningScheme = partitioningScheme;
        this.pages = pages;
        this.size = size;
    }

    void delegateToEachPage(long start, long end, AddressRangeCall<T> fn) {
        assert end >= start;

        int page = partitioningScheme.getPage(start);

        long endPos;

        for (long pos = start; pos < end; pos = endPos) {
            endPos = partitioningScheme.getPageEnd(pos, end);

            int sOff = partitioningScheme.getOffset(pos);
            int eOff = partitioningScheme.getEndOffset(start, endPos);

            fn.apply(pages[page++], sOff, eOff);
        }
    }

    void delegateToEachPageIO(long start, long end, AddressRangeCallIO<T> fn) throws IOException {
        assert end >= start;

        int page = partitioningScheme.getPage(start);

        long endPos;

        for (long pos = start; pos < end; pos = endPos) {
            endPos = partitioningScheme.getPageEnd(pos, end);

            int sOff = partitioningScheme.getOffset(pos);
            int eOff = partitioningScheme.getEndOffset(start, endPos);

            fn.apply(pages[page++], sOff, eOff);
        }
    }

    long translateSearchResultsFromPage(long fromIndex, long ret) {
        int page = partitioningScheme.getPage(fromIndex);

        if (ret >= 0) {
            return partitioningScheme.toRealIndex(page, (int) ret);
        } else {
            ret = decodeSearchMiss(1, ret);
            ret = partitioningScheme.toRealIndex(page, (int) ret);
            return encodeSearchMiss(1, ret);
        }
    }

    public void set(long start, long end, B buffer, int bufferStart) {
        assert end >= start;

        int page = partitioningScheme.getPage(start);

        long endPos;

        for (long pos = start; pos < end; pos = endPos) {
            endPos = partitioningScheme.getPageEnd(pos, end);

            int sOff = partitioningScheme.getOffset(pos);
            int eOff = partitioningScheme.getEndOffset(start, endPos);

            pages[page++].set(sOff, eOff, buffer, bufferStart);

            bufferStart += eOff - sOff;
        }
    }

    public void get(long start, long end, B buffer, int bufferStart) {
        assert end >= start;

        int page = partitioningScheme.getPage(start);

        long endPos;

        for (long pos = start; pos < end; pos = endPos) {
            endPos = partitioningScheme.getPageEnd(pos, end);

            int sOff = partitioningScheme.getOffset(pos);
            int eOff = partitioningScheme.getEndOffset(start, endPos);

            pages[page++].get(sOff, eOff, buffer, bufferStart);

            bufferStart += eOff - sOff;
        }
    }

    public void close() {
        for (var page : pages) {
            page.close();
        }
    }
}
