package nu.marginalia.index.query.filter;

import nu.marginalia.array.page.LongQueryBuffer;

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
        return 1.;
    }

    public String describe() {
        return "[NoPass]";
    }

}
