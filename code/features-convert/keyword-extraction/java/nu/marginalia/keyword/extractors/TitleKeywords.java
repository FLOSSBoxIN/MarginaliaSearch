package nu.marginalia.keyword.extractors;

import nu.marginalia.keyword.WordReps;
import nu.marginalia.keyword.KeywordExtractor;
import nu.marginalia.language.model.DocumentLanguageData;
import nu.marginalia.language.model.WordRep;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Extract keywords from the title */
public class TitleKeywords implements WordReps {
    private final Set<WordRep> titleKeywords;
    private final Set<String> stemmed;

    public TitleKeywords(KeywordExtractor keywordExtractor, DocumentLanguageData documentLanguageData) {
        titleKeywords = Arrays.stream(documentLanguageData.titleSentences).flatMap(sent ->
                        keywordExtractor.getWordsFromSentence(sent).stream().sorted().distinct().map(w -> new WordRep(sent, w)))
                .limit(100)
                .collect(Collectors.toSet());

        stemmed = titleKeywords.stream().map(WordRep::getStemmed).collect(Collectors.toSet());
    }

    public boolean contains(String wordStemmed) {
        return stemmed.contains(wordStemmed);
    }

    @Override
    public Collection<WordRep> getReps() {
        return titleKeywords;
    }
}
