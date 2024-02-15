package nu.marginalia.index.query.filter;

import nu.marginalia.array.buffer.LongQueryBuffer;

public class QueryFilterLetThrough implements QueryFilterStepIf {

    @Override
    public boolean test(long value) {
        return true;
    }

    @Override
    public void apply(LongQueryBuffer buffer) {
        buffer.retainAll();
        buffer.finalizeFiltering();
    }

    public double cost() {
        return 0.;
    }

    public String describe() {
        return "[PassThrough]";
    }

}
