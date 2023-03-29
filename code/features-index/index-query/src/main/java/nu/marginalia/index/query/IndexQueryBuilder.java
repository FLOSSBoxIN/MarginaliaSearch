package nu.marginalia.index.query;

import nu.marginalia.index.query.filter.QueryFilterStepIf;

public interface IndexQueryBuilder {
    /** Filters documents that also contain termId, within the full index.
     */
    IndexQueryBuilder alsoFull(int termId);

    /**
     * Filters documents that also contain <i>any of the provided termIds</i>, within the priority index.
     */
    IndexQueryBuilder alsoPrioAnyOf(int... termIds);

    /** Excludes documents that contain termId, within the full index
     */
    IndexQueryBuilder notFull(int termId);

    IndexQueryBuilder addInclusionFilter(QueryFilterStepIf filterStep);

    IndexQuery build();
}
