package nu.marginalia.index.query;

import nu.marginalia.array.page.LongQueryBuffer;
import nu.marginalia.btree.PoolingBTreeReader;
import nu.marginalia.index.query.filter.QueryFilterStepIf;

public record ReverseIndexRetainFilter(PoolingBTreeReader range, String name, long wordId) implements QueryFilterStepIf {

    @Override
    public void apply(LongQueryBuffer buffer) {
        range.retainEntries(buffer);
        buffer.finalizeFiltering();
    }

    public boolean test(long id) {
        return range.findEntry(id) >= 0;
    }

    @Override
    public double cost() {
        return range.numEntries();
    }

    @Override
    public String describe() {
        return "Retain:" + name + "/" + wordId;
    }
}
