package nu.marginalia.index.results.model;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import nu.marginalia.api.searchquery.model.query.SearchCoherenceConstraint;
import nu.marginalia.index.model.SearchTermsUtil;
import nu.marginalia.sequence.GammaCodedSequence;
import nu.marginalia.sequence.SequenceOperations;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * wordIds that we require to be in the same sentence
 */
public class TermCoherenceGroupList {
    List<TermCoherenceGroup> mandatoryGroups = new ArrayList<>();
    List<TermCoherenceGroup> optionalGroups = new ArrayList<>();

    public TermCoherenceGroupList(List<TermCoherenceGroup> groups) {
        for (var group : groups) {
            if (group.mandatory) {
                mandatoryGroups.add(group);
            } else {
                optionalGroups.add(group);
            }
        }
    }

    public boolean testMandatory(GammaCodedSequence[] positions) {
        for (var coherenceSet : mandatoryGroups) {
            if (!coherenceSet.test(positions)) {
                return false;
            }
        }

        return true;
    }

    public int testOptional(GammaCodedSequence[] positions) {
        int best = 0;
        for (var coherenceSet : mandatoryGroups) {
            if (coherenceSet.test(positions)) {
                best = Math.max(coherenceSet.size, best);
            }
        }
        return best;
    }


    public static final class TermCoherenceGroup {
        private final int[] offsets;
        private final BitSet present;

        public final int size;
        public final boolean mandatory;
        public TermCoherenceGroup(SearchCoherenceConstraint cons, LongList termIdsAll) {
            offsets = new int[cons.size()];
            present = new BitSet(cons.size());
            mandatory = cons.mandatory();
            size = cons.size();

            int i = 0;
            for (String term : cons.terms()) {
                if (!term.isEmpty()) {
                    present.set(i);
                    long termId = SearchTermsUtil.getWordId(term);
                    offsets[i++] = termIdsAll.indexOf(termId);
                }
            }
        }

        public boolean test(GammaCodedSequence[] positions) {
            IntIterator[] sequences = new IntIterator[present.cardinality()];

            for (int oi = 0, si = 0; oi < offsets.length; oi++) {
                if (!present.get(oi)) {
                    continue;
                }
                int offset = offsets[oi];
                if (offset < 0)
                    return false;

                // Create iterators that are offset by their relative position in the
                // sequence.  This is done by subtracting the index from the offset,
                // so that when we intersect them, an overlap means that the terms are
                // in the correct order.  Note the offset is negative!

                sequences[si++] = positions[oi].offsetIterator(-oi);
            }

            return SequenceOperations.intersectSequences(sequences);
        }
    }
}
