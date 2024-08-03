package nu.marginalia.index.results.model;

import gnu.trove.map.hash.TObjectLongHashMap;
import nu.marginalia.index.results.model.ids.TermIdList;

public class QuerySearchTerms {
    private final TObjectLongHashMap<String> termToId;
    public final TermIdList termIdsAll;

    public final TermCoherenceGroupList coherences;

    public QuerySearchTerms(TObjectLongHashMap<String> termToId,
                            TermIdList termIdsAll,
                            TermCoherenceGroupList coherences) {
        this.termToId = termToId;
        this.termIdsAll = termIdsAll;
        this.coherences = coherences;
    }

    public long getIdForTerm(String searchTerm) {
        return termToId.get(searchTerm);
    }
}
