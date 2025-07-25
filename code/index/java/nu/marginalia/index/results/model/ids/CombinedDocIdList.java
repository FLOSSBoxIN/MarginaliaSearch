package nu.marginalia.index.results.model.ids;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import nu.marginalia.array.page.LongQueryBuffer;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.util.Arrays;
import java.util.stream.LongStream;

/** A list of document ids, with their ranking bits still remaining.
 *
 * @see nu.marginalia.index.results.model.ids.DocIdList
 * @see nu.marginalia.model.id.UrlIdCodec
 * */
public final class CombinedDocIdList {
    private final long[] data;

    public CombinedDocIdList(long... data) {
        this.data = Arrays.copyOf(data, data.length);
    }
    public CombinedDocIdList(LongQueryBuffer buffer) {
        this.data = buffer.copyData();
    }
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
    public boolean isEmpty() {
        return data.length == 0;
    }
    public long at(int i) { return data[i]; }

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

