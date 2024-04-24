package nu.marginalia.index.model;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongList;
import nu.marginalia.api.searchquery.model.compiled.CompiledQueryLong;
import nu.marginalia.api.searchquery.model.query.SearchQuery;

import java.util.ArrayList;
import java.util.List;

import static nu.marginalia.index.model.SearchTermsUtil.getWordId;

public final class SearchTerms {
    private final LongList advice;
    private final LongList excludes;
    private final LongList priority;
    private final List<LongList> coherences;

    private final CompiledQueryLong compiledQueryIds;

    public SearchTerms(SearchQuery query,
                       CompiledQueryLong compiledQueryIds)
    {
        this.excludes = new LongArrayList();
        this.priority = new LongArrayList();
        this.coherences = new ArrayList<>();
        this.advice = new LongArrayList();
        this.compiledQueryIds = compiledQueryIds;

        for (var word : query.searchTermsAdvice) {
            advice.add(getWordId(word));
        }

        for (var coherence : query.searchTermCoherences) {
            LongList parts = new LongArrayList(coherence.size());

            for (var word : coherence) {
                parts.add(getWordId(word));
            }

            coherences.add(parts);
        }

        for (var word : query.searchTermsExclude) {
            excludes.add(getWordId(word));
        }

        for (var word : query.searchTermsPriority) {
            priority.add(getWordId(word));
        }
    }

    public boolean isEmpty() {
        return compiledQueryIds.isEmpty();
    }

    public long[] sortedDistinctIncludes(LongComparator comparator) {
        LongList list = new LongArrayList(compiledQueryIds.copyData());
        list.sort(comparator);
        return list.toLongArray();
    }


    public LongList excludes() {
        return excludes;
    }
    public LongList advice() {
        return advice;
    }
    public LongList priority() {
        return priority;
    }

    public List<LongList> coherences() {
        return coherences;
    }

    public CompiledQueryLong compiledQuery() { return compiledQueryIds; }

}
