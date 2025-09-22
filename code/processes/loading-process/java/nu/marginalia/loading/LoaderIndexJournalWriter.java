package nu.marginalia.loading;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.IndexLocations;
import nu.marginalia.index.journal.IndexJournal;
import nu.marginalia.index.journal.IndexJournalSlopWriter;
import nu.marginalia.language.config.LanguageConfiguration;
import nu.marginalia.language.keywords.KeywordHasher;
import nu.marginalia.language.model.LanguageDefinition;
import nu.marginalia.model.processed.SlopDocumentRecord;
import nu.marginalia.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;


@Singleton
public class LoaderIndexJournalWriter {

    private static final Logger logger = LoggerFactory.getLogger(LoaderIndexJournalWriter.class);
    private final Map<String, WriteHead> writeHeads = new HashMap<>();

    @Inject
    public LoaderIndexJournalWriter(FileStorageService fileStorageService, LanguageConfiguration languageConfiguration) throws IOException {
        final Path indexArea = IndexLocations.getIndexConstructionArea(fileStorageService);

        for (LanguageDefinition languageDefinition: languageConfiguration.languages()) {
            writeHeads.put(languageDefinition.isoCode(), new WriteHead(indexArea, languageDefinition));
        }

        logger.info("Creating Journal Writer {}", indexArea);
    }

    public void putWords(long header, SlopDocumentRecord.KeywordsProjection data) throws IOException
    {
        WriteHead head = writeHeads.get(data.languageIsoCode());
        if (head == null) return;

        head.putWords(header, data);
    }

    public void close() throws IOException {
        for (WriteHead head : writeHeads.values()) {
            head.close();
        }
    }

    static class WriteHead {
        private IndexJournalSlopWriter currentWriter = null;
        private long recordsWritten = 0;
        private int page;

        private final KeywordHasher keywordHasher;

        private final Path journalPath;

        WriteHead(Path indexArea, LanguageDefinition  languageDefinition) throws IOException {
            keywordHasher = languageDefinition.keywordHasher();
            journalPath = IndexJournal.allocateName(indexArea, languageDefinition.isoCode());
            page = IndexJournal.numPages(journalPath);

            switchToNextVersion();
        }

        private void switchToNextVersion() throws IOException {
            if (currentWriter != null) {
                currentWriter.close();
            }

            currentWriter = new IndexJournalSlopWriter(journalPath, page++);
        }

        public void putWords(long header, SlopDocumentRecord.KeywordsProjection data) throws IOException
        {
            if (++recordsWritten > 200_000) {
                recordsWritten = 0;
                switchToNextVersion();
            }

            currentWriter.put(header, data, keywordHasher);
        }

        public void close() throws IOException {
            currentWriter.close();
        }

    }
}
