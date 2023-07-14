package nu.marginalia.index;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.db.storage.model.FileStorageType;
import nu.marginalia.index.forward.ForwardIndexConverter;
import nu.marginalia.index.forward.ForwardIndexReader;
import nu.marginalia.index.journal.reader.IndexJournalReaderSingleCompressedFile;
import nu.marginalia.index.journal.writer.IndexJournalWriter;
import nu.marginalia.index.journal.writer.IndexJournalWriterImpl;
import nu.marginalia.index.priority.ReverseIndexPriorityConverter;
import nu.marginalia.index.full.ReverseIndexFullConverter;
import nu.marginalia.index.priority.ReverseIndexPriorityReader;
import nu.marginalia.index.priority.ReverseIndexPriorityParameters;
import nu.marginalia.index.full.ReverseIndexFullReader;
import nu.marginalia.lexicon.KeywordLexicon;
import nu.marginalia.ranking.DomainRankings;
import nu.marginalia.index.index.SearchIndexReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Singleton
public class IndexServicesFactory {
    private final Path tmpFileDir;
    private final Path liveStorage;
    private final Path stagingStorage;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final PartitionedDataFile writerIndexFile;

    private final PartitionedDataFile fwdIndexDocId;
    private final PartitionedDataFile fwdIndexDocData;
    private final PartitionedDataFile revIndexDoc;
    private final PartitionedDataFile revIndexWords;

    private final PartitionedDataFile revPrioIndexDoc;
    private final PartitionedDataFile revPrioIndexWords;

    private final Path searchSetsBase;

    final int LIVE_PART = 0;
    final int NEXT_PART = 1;

    @Inject
    public IndexServicesFactory(
            FileStorageService fileStorageService
            ) throws IOException, SQLException {

        liveStorage = fileStorageService.getStorageByType(FileStorageType.INDEX_LIVE).asPath();
        stagingStorage = fileStorageService.getStorageByType(FileStorageType.INDEX_STAGING).asPath();
        tmpFileDir = fileStorageService.getStorageByType(FileStorageType.INDEX_STAGING).asPath().resolve("tmp");
        searchSetsBase = fileStorageService.getStorageByType(FileStorageType.SEARCH_SETS).asPath();

        if (!Files.exists(tmpFileDir)) {
            Files.createDirectories(tmpFileDir);
        }

        writerIndexFile = new PartitionedDataFile(stagingStorage, "page-index.dat");

        fwdIndexDocId = new PartitionedDataFile(liveStorage, "fwd-doc-id.dat");
        fwdIndexDocData = new PartitionedDataFile(liveStorage, "fwd-doc-data.dat");

        revIndexDoc = new PartitionedDataFile(liveStorage, "rev-doc.dat");
        revIndexWords = new PartitionedDataFile(liveStorage, "rev-words.dat");

        revPrioIndexDoc = new PartitionedDataFile(liveStorage, "rev-prio-doc.dat");
        revPrioIndexWords = new PartitionedDataFile(liveStorage, "rev-prio-words.dat");
    }

    public Path getSearchSetsBase() {
        return searchSetsBase;
    }

    public boolean isPreconvertedIndexPresent() {
        return Stream.of(
                writerIndexFile.get(LIVE_PART).toPath()
        ).allMatch(Files::exists);
    }

    public boolean isConvertedIndexMissing() {
        return Stream.of(
                revIndexWords.get(LIVE_PART).toPath(),
                revIndexDoc.get(LIVE_PART).toPath(),
                revPrioIndexWords.get(LIVE_PART).toPath(),
                revPrioIndexDoc.get(LIVE_PART).toPath(),
                fwdIndexDocData.get(LIVE_PART).toPath(),
                fwdIndexDocId.get(LIVE_PART).toPath()
        ).noneMatch(Files::exists);
    }

    public IndexJournalWriter createIndexJournalWriter(KeywordLexicon lexicon) throws IOException {
        return new IndexJournalWriterImpl(lexicon, writerIndexFile.get(LIVE_PART).toPath());
    }

    public void convertIndex(DomainRankings domainRankings) throws IOException {
        convertForwardIndex(domainRankings);
        convertFullReverseIndex(domainRankings);
        convertPriorityReverseIndex(domainRankings);
    }

    private void convertFullReverseIndex(DomainRankings domainRankings) throws IOException {
        var source = writerIndexFile.get(0).toPath();

        logger.info("Converting full reverse index {}", source);

        var journalReader = new IndexJournalReaderSingleCompressedFile(source);
        var converter = new ReverseIndexFullConverter(tmpFileDir,
                journalReader,
                domainRankings,
                revIndexWords.get(NEXT_PART).toPath(),
                revIndexDoc.get(NEXT_PART).toPath());

        converter.convert();

        tryGc();
    }

    private void convertPriorityReverseIndex(DomainRankings domainRankings) throws IOException {

        var source = writerIndexFile.get(0).toPath();

        logger.info("Converting priority reverse index {}", source);

        var journalReader = new IndexJournalReaderSingleCompressedFile(source, null,
                ReverseIndexPriorityParameters::filterPriorityRecord);

        var converter = new ReverseIndexPriorityConverter(tmpFileDir,
                journalReader,
                domainRankings,
                revPrioIndexWords.get(NEXT_PART).toPath(),
                revPrioIndexDoc.get(NEXT_PART).toPath());

        converter.convert();

        tryGc();
    }

    private void convertForwardIndex(DomainRankings domainRankings) throws IOException {

        var source = writerIndexFile.get(0);

        logger.info("Converting forward index data {}", source);

        new ForwardIndexConverter(source,
                fwdIndexDocId.get(NEXT_PART).toPath(),
                fwdIndexDocData.get(NEXT_PART).toPath(),
                domainRankings)
                .convert();

        tryGc();
    }


    public void tryGc() {

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        System.gc();
    }

    public ReverseIndexFullReader getReverseIndexReader() throws IOException {
        return new ReverseIndexFullReader(
                revIndexWords.get(LIVE_PART).toPath(),
                revIndexDoc.get(LIVE_PART).toPath());
    }
    public ReverseIndexPriorityReader getReverseIndexPrioReader() throws IOException {
        return new ReverseIndexPriorityReader(
                revPrioIndexWords.get(LIVE_PART).toPath(),
                revPrioIndexDoc.get(LIVE_PART).toPath());
    }
    public ForwardIndexReader getForwardIndexReader() throws IOException {
        return new ForwardIndexReader(
                fwdIndexDocId.get(LIVE_PART).toPath(),
                fwdIndexDocData.get(LIVE_PART).toPath()
        );
    }

    public Callable<Boolean> switchFilesJob() {
        return () -> {

            switchFile(revIndexDoc.get(NEXT_PART).toPath(), revIndexDoc.get(LIVE_PART).toPath());
            switchFile(revIndexWords.get(NEXT_PART).toPath(), revIndexWords.get(LIVE_PART).toPath());

            switchFile(revPrioIndexDoc.get(NEXT_PART).toPath(), revPrioIndexDoc.get(LIVE_PART).toPath());
            switchFile(revPrioIndexWords.get(NEXT_PART).toPath(), revPrioIndexWords.get(LIVE_PART).toPath());

            switchFile(fwdIndexDocId.get(NEXT_PART).toPath(), fwdIndexDocId.get(LIVE_PART).toPath());
            switchFile(fwdIndexDocData.get(NEXT_PART).toPath(), fwdIndexDocData.get(LIVE_PART).toPath());

            return true;
        };
    }

    public void switchFile(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public SearchIndexReader getSearchIndexReader() throws IOException {
        return new SearchIndexReader(
                getForwardIndexReader(),
                getReverseIndexReader(),
                getReverseIndexPrioReader()
        );
    }
}

class RootDataFile {
    private final Path partition;
    private final String pattern;

    RootDataFile(Path partition, String pattern) {
        this.partition = partition;
        this.pattern = pattern;
    }

    public File get() {
        return partition.resolve(pattern).toFile();
    }
}


class PartitionedDataFile {
    private final Path partition;
    private final String pattern;

    PartitionedDataFile(Path partition, String pattern) {
        this.partition = partition;
        this.pattern = pattern;
    }

    public File get(Object id) {
        Path partitionDir = partition.resolve(id.toString());
        if (!partitionDir.toFile().exists()) {
            partitionDir.toFile().mkdir();
        }
        return partitionDir.resolve(pattern).toFile();
    }

}