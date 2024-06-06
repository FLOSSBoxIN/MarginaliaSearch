package nu.marginalia.index.forward;

import lombok.SneakyThrows;
import nu.marginalia.index.domainrankings.DomainRankings;
import nu.marginalia.index.journal.model.IndexJournalEntryData;
import nu.marginalia.index.journal.model.IndexJournalEntryHeader;
import nu.marginalia.index.journal.reader.IndexJournalReaderSingleFile;
import nu.marginalia.index.journal.writer.IndexJournalWriter;
import nu.marginalia.index.journal.writer.IndexJournalWriterSingleFileImpl;
import nu.marginalia.model.id.UrlIdCodec;
import nu.marginalia.process.control.FakeProcessHeartbeat;
import nu.marginalia.sequence.GammaCodedSequence;
import nu.marginalia.test.TestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForwardIndexConverterTest {

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

        indexFile = Files.createTempFile("tmp", ".idx");
        indexFile.toFile().deleteOnExit();
        writer = new IndexJournalWriterSingleFileImpl(indexFile);

        wordsFile1 = Files.createTempFile("words1", ".idx");
        urlsFile1 = Files.createTempFile("urls1", ".idx");

        dataDir = Files.createTempDirectory(getClass().getSimpleName());

        for (int i = 1; i < workSetSize; i++) {
            createEntry(writer, i);
        }

        writer.close();


        docsFileId = dataDir.resolve("docs-i.dat");
        docsFileData = dataDir.resolve("docs-d.dat");
    }

    @AfterEach
    public void tearDown() {
        TestUtil.clearTempDir(dataDir);
    }

    long createId(long url, long domain) {
        return UrlIdCodec.encodeId((int) domain, (int) url);
    }

    public void createEntry(IndexJournalWriter writer, int id) {
        writer.put(
                new IndexJournalEntryHeader(createId(id, id/20),
                        id%3,
                        (id % 5)),
                new IndexJournalEntryData(
                    new String[]{},
                    new long[]{},
                    new GammaCodedSequence[]{}
                )
        );
    }

    @Test
    void testForwardIndex() throws IOException {

        new ForwardIndexConverter(new FakeProcessHeartbeat(),
                new IndexJournalReaderSingleFile(indexFile),
                docsFileId,
                docsFileData,
                new DomainRankings()).convert();

        var forwardReader = new ForwardIndexReader(docsFileId, docsFileData);

        for (int i = 36; i < workSetSize; i++) {
            long docId = createId(i, i/20);
            assertEquals(0x00FF000000000000L | (i % 5), forwardReader.getDocMeta(docId));
            assertEquals((i % 3), forwardReader.getHtmlFeatures(docId));
            assertEquals(i/20, UrlIdCodec.getDomainId(docId));
        }
    }

}