package nu.marginalia.index.query.filter;

import nu.marginalia.array.buffer.LongQueryBuffer;

public class QueryFilterNoPass implements QueryFilterStepIf {
    static final QueryFilterStepIf instance = new QueryFilterNoPass();

    @Override
    public boolean test(long value) {
        return false;
    }

    public void apply(LongQueryBuffer buffer) {
        buffer.finalizeFiltering();
    }

    public double cost() {
        return 0.;
    }

    public String describe() {
        return "[NoPass]";
    }

}
