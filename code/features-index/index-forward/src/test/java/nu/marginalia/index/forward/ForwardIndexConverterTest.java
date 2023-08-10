package nu.marginalia.index.forward;

import lombok.SneakyThrows;
import nu.marginalia.index.journal.model.IndexJournalEntry;
import nu.marginalia.index.journal.writer.IndexJournalWriterImpl;
import nu.marginalia.index.journal.writer.IndexJournalWriter;
import nu.marginalia.lexicon.journal.KeywordLexiconJournalMode;
import nu.marginalia.ranking.DomainRankings;
import nu.marginalia.lexicon.KeywordLexicon;
import nu.marginalia.lexicon.journal.KeywordLexiconJournal;
import nu.marginalia.service.control.ServiceHeartbeat;
import nu.marginalia.service.control.ServiceTaskHeartbeat;
import nu.marginalia.test.TestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class ForwardIndexConverterTest {

    KeywordLexicon keywordLexicon;
    IndexJournalWriter writer;

    Path indexFile;
    Path wordsFile1;
    Path urlsFile1;
    Path dictionaryFile;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    Path dataDir;
    private Path docsFileId;
    private Path docsFileData;

    int workSetSize = 512;
    @BeforeEach
    @SneakyThrows
    void setUp() {
        dictionaryFile = Files.createTempFile("tmp", ".dict");
        dictionaryFile.toFile().deleteOnExit();

        keywordLexicon = new KeywordLexicon(new KeywordLexiconJournal(dictionaryFile.toFile(), KeywordLexiconJournalMode.READ_WRITE));
        keywordLexicon.getOrInsert("0");

        indexFile = Files.createTempFile("tmp", ".idx");
        indexFile.toFile().deleteOnExit();
        writer = new IndexJournalWriterImpl(keywordLexicon, indexFile);

        wordsFile1 = Files.createTempFile("words1", ".idx");
        urlsFile1 = Files.createTempFile("urls1", ".idx");

        dataDir = Files.createTempDirectory(getClass().getSimpleName());

        for (int i = 1; i < workSetSize; i++) {
            createEntry(writer, keywordLexicon, i);
        }


        keywordLexicon.commitToDisk();
        writer.close();


        docsFileId = dataDir.resolve("docs-i.dat");
        docsFileData = dataDir.resolve("docs-d.dat");
    }

    @AfterEach
    public void tearDown() {
        TestUtil.clearTempDir(dataDir);
    }

    public int[] getFactorsI(int id) {
        return IntStream.rangeClosed(1, id).filter(v -> (id % v) == 0).toArray();
    }

    long createId(long url, long domain) {
        return (domain << 32) | url;
    }
    public void createEntry(IndexJournalWriter writer, KeywordLexicon keywordLexicon, int id) {
        int[] factors = getFactorsI(id);

        var entryBuilder = IndexJournalEntry.builder(createId(id, id/20), id%5);

        for (int i = 0; i+1 < factors.length; i+=2) {
            entryBuilder.add(keywordLexicon.getOrInsert(Integer.toString(factors[i])), -factors[i+1]);
        }

        writer.put(entryBuilder.build());
    }

    @Test
    void testForwardIndex() throws IOException {

        // RIP fairies
        var serviceHeartbeat = Mockito.mock(ServiceHeartbeat.class);
        when(serviceHeartbeat.createServiceTaskHeartbeat(Mockito.any(), Mockito.any()))
                .thenReturn(Mockito.mock(ServiceTaskHeartbeat.class));

        new ForwardIndexConverter(serviceHeartbeat, indexFile.toFile(), docsFileId, docsFileData, new DomainRankings()).convert();

        var forwardReader = new ForwardIndexReader(docsFileId, docsFileData);

        for (int i = 36; i < workSetSize; i++) {
            assertEquals(0x00FF000000000000L | (i % 5), forwardReader.getDocMeta(i));
            assertEquals(i/20, forwardReader.getDomainId(i));
        }

    }


}