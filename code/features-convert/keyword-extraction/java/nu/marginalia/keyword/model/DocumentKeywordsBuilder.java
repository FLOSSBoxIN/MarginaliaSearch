package nu.marginalia.keyword.model;

import gnu.trove.list.array.TByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import lombok.Getter;
import nu.marginalia.language.sentence.tag.HtmlTag;
import nu.marginalia.model.idx.CodedWordSpan;
import nu.marginalia.model.idx.WordFlags;
import nu.marginalia.sequence.GammaCodedSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

@Getter
public class DocumentKeywordsBuilder {
    public final Object2ByteOpenHashMap<String> wordToMeta;
    public final HashMap<String, IntList> wordToPos;
    public final Map<Character, List<DocumentWordSpan>> wordSpans = new HashMap<>();

    /** These ware keywords that had signals of high relevance */
    public final Set<String> importantWords = new HashSet<>();

    // |------64 letters is this long-------------------------------|
    // granted, some of these words are word n-grams, but 64 ought to
    // be plenty. The lexicon writer has another limit that's higher.
    private final int MAX_WORD_LENGTH = 64;
    private final int MAX_POSITIONS_PER_WORD = 512;

    private static final Logger logger = LoggerFactory.getLogger(DocumentKeywordsBuilder.class);

    public DocumentKeywordsBuilder() {
        this(1600);
    }

    public DocumentKeywords build(ByteBuffer workArea) {
        final List<String> wordArray = new ArrayList<>(wordToMeta.size());
        final TByteArrayList meta = new TByteArrayList(wordToMeta.size());
        final List<GammaCodedSequence> positions = new ArrayList<>(wordToMeta.size());

        var iter = wordToMeta.object2ByteEntrySet().fastIterator();

        while (iter.hasNext()) {
            var entry = iter.next();

            meta.add(entry.getByteValue());
            wordArray.add(entry.getKey());

            var posList = wordToPos.getOrDefault(entry.getKey(), IntList.of());

            if (posList.size() > MAX_POSITIONS_PER_WORD) {
                posList.subList(MAX_POSITIONS_PER_WORD, posList.size()).clear();
            }

            positions.add(GammaCodedSequence.generate(workArea, posList));
        }

        // Encode spans
        List<CodedWordSpan> spans = new ArrayList<>(wordSpans.size());

        wordSpans.forEach((tag, spansForTag) -> {
            spansForTag.sort(Comparator.comparingInt(DocumentWordSpan::start));

            var positionsForTag = new IntArrayList(spansForTag.size()*2);
            for (var span : spansForTag) {
                positionsForTag.add(span.start());
                positionsForTag.add(span.end());
            }

            spans.add(new CodedWordSpan((byte) tag.charValue(), GammaCodedSequence.generate(workArea, positionsForTag)));
        });

        return new DocumentKeywords(wordArray, meta.toArray(), positions, spans);
    }

    public DocumentKeywordsBuilder(int capacity) {
        wordToMeta  = new Object2ByteOpenHashMap<>(capacity);
        wordToPos = new HashMap<>(capacity);
    }

    public void addMeta(String word, byte meta) {
        if (word.length() > MAX_WORD_LENGTH)
            return;

        wordToMeta.put(word, meta);
    }

    public void addPos(String word, int pos) {
        if (word.length() > MAX_WORD_LENGTH)
            return;

        wordToPos.computeIfAbsent(word, k -> new IntArrayList()).add(pos);
    }

    public void addImportantWords(Collection<String> words) {
        importantWords.addAll(words);
    }

    public void setFlagOnMetadataForWords(WordFlags flag, Collection<String> flagWords) {
        flagWords.forEach(word ->
            wordToMeta.mergeByte(word, flag.asBit(), (a, b) -> (byte)(a|b))
        );
    }

    public void addAllSyntheticTerms(Collection<String> newWords) {
        byte meta = WordFlags.Synthetic.asBit();

        // Only add the synthetic flag if the words aren't already present

        newWords.forEach(word -> wordToMeta.putIfAbsent(word, meta));
    }

    public void addAnchorTerms(Map<String, Integer> keywords) {
        byte flagA = WordFlags.ExternalLink.asBit();
        byte flagB = (byte) (flagA | WordFlags.Site.asBit());
        byte flagC = (byte) (flagB | WordFlags.SiteAdjacent.asBit());

        keywords.forEach((word, count) -> {
            if (count > 5) {
                wordToMeta.mergeByte(word, flagC, (a, b) -> (byte) (a|b));
            } else if (count > 2) {
                wordToMeta.mergeByte(word, flagB, (a, b) -> (byte) (a|b));
            } else {
                wordToMeta.mergeByte(word, flagA, (a, b) -> (byte) (a|b));
            }
        });
    }

    public List<String> getWordsWithAnyFlag(long flags) {
        List<String> ret = new ArrayList<>();

        for (var iter = wordToMeta.object2ByteEntrySet().fastIterator(); iter.hasNext();) {
            var entry = iter.next();
            if ((flags & entry.getByteValue()) != 0) {
                ret.add(entry.getKey());
            }
        }

        return ret;
    }

    public void addSpans(List<DocumentWordSpan> newSpans) {
        for (var span : newSpans) {
            wordSpans.computeIfAbsent(span.tag().code, k -> new ArrayList<>()).add(span);
        }
    }

    public int size() {
        return Math.max(wordToMeta.size(), wordToPos.size());
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[ ");

        wordToMeta.forEach((word, meta) -> {
            sb.append(word).append("->").append(WordFlags.decode(meta)).append(',').append(wordToPos.getOrDefault(word, new IntArrayList())).append(' ');
        });
        return sb.append(']').toString();
    }

    public record DocumentWordSpan(HtmlTag tag, int start, int end) {
    }
}
