package nu.marginalia.keyword;

import nu.marginalia.WmsaHome;
import nu.marginalia.converting.processor.logic.dom.DomPruningFilter;
import nu.marginalia.language.sentence.SentenceExtractor;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.idx.WordMetadata;
import nu.marginalia.segmentation.NgramLexicon;
import nu.marginalia.term_frequency_dict.TermFrequencyDict;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class DocumentKeywordExtractorTest {

    static DocumentKeywordExtractor extractor = new DocumentKeywordExtractor();
    static SentenceExtractor se = new SentenceExtractor(WmsaHome.getLanguageModels());

    @Test
    public void testWordPattern() {
        Assertions.assertTrue(extractor.matchesWordPattern("test"));
        Assertions.assertTrue(extractor.matchesWordPattern("1234567890abcde"));
        Assertions.assertFalse(extractor.matchesWordPattern("1234567890abcdef"));

        Assertions.assertTrue(extractor.matchesWordPattern("test-test-test-test-test"));
        Assertions.assertFalse(extractor.matchesWordPattern("test-test-test-test-test-test"));
        Assertions.assertTrue(extractor.matchesWordPattern("192.168.1.100/24"));
        Assertions.assertTrue(extractor.matchesWordPattern("std::vector"));
        Assertions.assertTrue(extractor.matchesWordPattern("c++"));
        Assertions.assertTrue(extractor.matchesWordPattern("m*a*s*h"));
        Assertions.assertFalse(extractor.matchesWordPattern("Stulpnagelstrasse"));
    }

    @Test
    public void testKeyboards2() throws IOException, URISyntaxException {
        var resource = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream("test-data/keyboards.html"),
                "Could not load word frequency table");
        String html = new String(resource.readAllBytes(), Charset.defaultCharset());
        var doc = Jsoup.parse(html);
        doc.filter(new DomPruningFilter(0.5));

        var keywords = extractor.extractKeywords(se.extractSentences(doc), new EdgeUrl("https://pmortensen.eu/world2/2021/12/24/rapoo-mechanical-keyboards-gotchas-and-setup/"));

        keywords.getWordToMeta().forEach((k, v) -> {
            if (k.contains("_")) {
                System.out.println(k + " " + new WordMetadata(v));
            }
        });
    }
    @Test
    public void testKeyboards() throws IOException, URISyntaxException {
        var resource = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream("test-data/keyboards.html"),
                "Could not load word frequency table");
        String html = new String(resource.readAllBytes(), Charset.defaultCharset());
        var doc = Jsoup.parse(html);
        doc.filter(new DomPruningFilter(0.5));

        var keywords = extractor.extractKeywords(se.extractSentences(doc), new EdgeUrl("https://pmortensen.eu/world2/2021/12/24/rapoo-mechanical-keyboards-gotchas-and-setup/"));
        System.out.println(keywords.getMetaForWord("mechanical"));
        System.out.println(keywords.getMetaForWord("keyboard"));
        System.out.println(keywords.getMetaForWord("keyboards"));

        System.out.println(new WordMetadata(8894889328781L));
        System.out.println(new WordMetadata(4294967297L));
        System.out.println(new WordMetadata(566820053975498886L));
        // -
        System.out.println(new WordMetadata(1198298103937L));
        System.out.println(new WordMetadata(1103808168065L));
    }

    @Test
    public void testMadonna() throws IOException, URISyntaxException {
        var resource = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream("test-data/madonna.html"),
                "Could not load word frequency table");
        String html = new String(resource.readAllBytes(), Charset.defaultCharset());
        var doc = Jsoup.parse(html);
        doc.filter(new DomPruningFilter(0.5));

        var keywords = extractor.extractKeywords(
                se.extractSentences(doc),
                new EdgeUrl("https://encyclopedia.marginalia.nu/article/Don't_Tell_Me_(Madonna_song)")
        );

        var keywordsBuilt = keywords.build();
        var ptr = keywordsBuilt.newPointer();

        Map<String, WordMetadata> flags = new HashMap<>();
        Map<String, RoaringBitmap> positions = new HashMap<>();

        while (ptr.advancePointer()) {
            System.out.println(ptr.getKeyword() + " " + ptr.getMetadata() + " " + ptr.getPositions());
            if (Set.of("dirty", "blues").contains(ptr.getKeyword())) {
                flags.put(ptr.getKeyword(), new WordMetadata(ptr.getMetadata()));
                positions.put(ptr.getKeyword(), ptr.getPositions());
            }
        }

        Assertions.assertTrue(flags.containsKey("dirty"));
        Assertions.assertTrue(flags.containsKey("blues"));
        Assertions.assertNotEquals(
                positions.get("dirty"),
                positions.get("blues")
                );
    }

    @Test
    public void testSpam() throws IOException, URISyntaxException {
        var resource = Objects.requireNonNull(ClassLoader.getSystemResourceAsStream("test-data/spam.html"),
                "Could not load word frequency table");
        String html = new String(resource.readAllBytes(), Charset.defaultCharset());
        var doc = Jsoup.parse(html);
        doc.filter(new DomPruningFilter(0.5));

        DocumentKeywordExtractor extractor = new DocumentKeywordExtractor(
                new TermFrequencyDict(WmsaHome.getLanguageModels()));
        SentenceExtractor se = new SentenceExtractor(WmsaHome.getLanguageModels());

        var keywords = extractor.extractKeywords(se.extractSentences(doc), new EdgeUrl("https://math.byu.edu/wiki/index.php/All_You_Need_To_Know_About_Earning_Money_Online"));
        System.out.println(keywords.getMetaForWord("knitting"));
    }
}