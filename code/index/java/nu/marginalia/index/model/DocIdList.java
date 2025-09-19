package nu.marginalia.index.model;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Arrays;
import java.util.stream.LongStream;

/** A list of document ids, with their ranking bits removed.
 *
 * @see CombinedDocIdList
 * @see nu.marginalia.model.id.UrlIdCodec
 * */
public final class DocIdList {
    private final long[] array;

    public DocIdList(long[] array) {
        this.array = array;
    }

    public DocIdList(LongArrayList list) {
        this(list.toLongArray());
    }

    public int size() {
        return array.length;
    }

    public void sort() {
        Arrays.sort(array);
    }

    public LongStream stream() {
        return LongStream.of(array);
    }

    public long[] array() {
        return array;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (DocIdList) obj;
        return Arrays.equals(this.array, that.array);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(array);
    }

}
