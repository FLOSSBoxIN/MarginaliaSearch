package nu.marginalia.index.results.model.ids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.util.Arrays;
import java.util.stream.LongStream;

public final class CombinedDocIdList {
    private final long[] data;

    public CombinedDocIdList(LongArrayList data) {
        this.data = data.toLongArray();
    }
    public CombinedDocIdList(Roaring64Bitmap data) {
        this.data = data.toArray();
    }
    public CombinedDocIdList() {
        this.data = new long[0];
    }

    public int size() {
        return data.length;
    }

    public LongStream stream() {
        return Arrays.stream(data);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (CombinedDocIdList) obj;
        return Arrays.equals(this.data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }


    public long[] array() {
        return data;
    }

    public void sort() {
        Arrays.sort(data);
    }
}

