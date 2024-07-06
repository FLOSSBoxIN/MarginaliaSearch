package nu.marginalia;

import com.google.inject.Guice;
import com.google.inject.Inject;
import nu.marginalia.api.searchquery.QueryProtobufCodec;
import nu.marginalia.api.searchquery.RpcQsQuery;
import nu.marginalia.api.searchquery.RpcQueryLimits;
import nu.marginalia.api.searchquery.model.results.ResultRankingParameters;
import nu.marginalia.converting.processor.DomainProcessor;
import nu.marginalia.converting.writer.ConverterBatchWriter;
import nu.marginalia.crawl.retreival.DomainProber;
import nu.marginalia.crawl.retreival.fetcher.ContentTags;
import nu.marginalia.crawl.retreival.fetcher.warc.WarcRecorder;
import nu.marginalia.crawling.io.CrawledDomainReader;
import nu.marginalia.crawling.parquet.CrawledDocumentParquetRecordFileWriter;
import nu.marginalia.functions.searchquery.QueryFactory;
import nu.marginalia.index.IndexGrpcService;
import nu.marginalia.index.ReverseIndexFullFileNames;
import nu.marginalia.index.ReverseIndexPrioFileNames;
import nu.marginalia.index.construction.full.FullIndexConstructor;
import nu.marginalia.index.domainrankings.DomainRankings;
import nu.marginalia.index.forward.ForwardIndexConverter;
import nu.marginalia.index.forward.ForwardIndexFileNames;
import nu.marginalia.index.index.StatefulIndex;
import nu.marginalia.index.journal.reader.IndexJournalReader;
import nu.marginalia.index.model.SearchParameters;
import nu.marginalia.index.searchset.SearchSetAny;
import nu.marginalia.linkdb.docs.DocumentDbReader;
import nu.marginalia.linkdb.docs.DocumentDbWriter;
import nu.marginalia.loading.LoaderIndexJournalWriter;
import nu.marginalia.loading.LoaderInputData;
import nu.marginalia.loading.documents.DocumentLoaderService;
import nu.marginalia.loading.documents.KeywordLoaderService;
import nu.marginalia.loading.domains.DomainIdRegistry;
import nu.marginalia.loading.links.DomainLinksLoaderService;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.id.UrlIdCodec;
import nu.marginalia.model.idx.WordFlags;
import nu.marginalia.model.idx.WordMetadata;
import nu.marginalia.process.control.FakeProcessHeartbeat;
import nu.marginalia.storage.FileStorageService;
import nu.marginalia.storage.model.FileStorageBaseType;
import nu.marginalia.test.IntegrationTestModule;
import nu.marginalia.test.TestUtil;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.LongPredicate;

import static nu.marginalia.index.journal.reader.IndexJournalReader.FILE_HEADER_SIZE_BYTES;
import static nu.marginalia.linkdb.LinkdbFileNames.DOCDB_FILE_NAME;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class IntegrationTest {
    IntegrationTestModule testModule;
    @Inject
    DomainProcessor domainProcessor;

    @Inject
    DomainLinksLoaderService linksService;
    @Inject
    KeywordLoaderService keywordLoaderService;
    @Inject
    DocumentLoaderService documentLoaderService;

    @Inject
    FileStorageService fileStorageService;

    @Inject
    DomainRankings domainRankings;

    @Inject
    DocumentDbWriter documentDbWriter;
    @Inject
    LoaderIndexJournalWriter journalWriter;

    Path warcData = null;
    Path crawlDataParquet = null;
    Path processedDataDir = null;

    @Inject
    StatefulIndex statefulIndex;
    @Inject
    IndexGrpcService indexGrpcService;
    @Inject
    DocumentDbReader documentDbReader;

    @Inject
    QueryFactory queryFactory;

    @BeforeEach
    public void setupTest() throws IOException {
        testModule = new IntegrationTestModule();

        Guice.createInjector(testModule).injectMembers(this);

        warcData = Files.createTempFile("warc", ".warc.gz");
        crawlDataParquet = Files.createTempFile("crawl", ".parquet");
        processedDataDir = Files.createTempDirectory("processed");
    }

    @AfterEach
    public void tearDownTest() throws IOException {
        Files.deleteIfExists(warcData);
        Files.deleteIfExists(crawlDataParquet);
        TestUtil.clearTempDir(processedDataDir);

        testModule.cleanUp();
    }


    @Test
    public void run() throws Exception {

        /** CREATE WARC */
        try (WarcRecorder warcRecorder = new WarcRecorder(warcData)) {
            warcRecorder.writeWarcinfoHeader("127.0.0.1", new EdgeDomain("www.example.com"),
                    new DomainProber.ProbeResultOk(new EdgeUrl("https://www.example.com/")));

            warcRecorder.writeReferenceCopy(new EdgeUrl("https://www.example.com/"),
                    "text/html", 200,
                    """
                            <html>
                            <h1>Hello World</h1>
                            <body>
                            <p>The best description of my problem solving process is the Feynman algorithm, which is sometimes presented as a joke where the hidden subtext is “be smart”, but I disagree. The “algorithm” is a surprisingly lucid description of how thinking works in the context of hard problems where the answer can’t simply be looked up or trivially broken down, iterated upon in a bottom-up fashion, or approached with similar methods.
                               The trick is that there is no trick. This is how thinking works. It appears that when you feed your brain related information, without further active involvement, it starts to digest the information you’ve fed it.
                               </p>
                            </body>
                            </html>
                            """,
                    ContentTags.empty()
            );
        }

        /** CONVERT WARC */
        CrawledDocumentParquetRecordFileWriter.convertWarc(
                "www.example.com",
                new UserAgent("search.marginalia.nu",
                        "search.marginalia.nu"),
                warcData,
                crawlDataParquet);

        /** PROCESS CRAWL DATA */

        var processedDomain = domainProcessor.fullProcessing(CrawledDomainReader.createDataStream(crawlDataParquet));

        System.out.println(processedDomain);

        /** WRITE PROCESSED DATA */

        try (ConverterBatchWriter cbw = new ConverterBatchWriter(processedDataDir, 0)) {
            cbw.writeProcessedDomain(processedDomain);

        }
        // Write a single batch-switch marker in the process log so that the loader will read the data
        Files.writeString(processedDataDir.resolve("processor.log"), "F\n", StandardOpenOption.CREATE_NEW);

        /** LOAD PROCESSED DATA */

        LoaderInputData inputData = new LoaderInputData(List.of(processedDataDir));

        DomainIdRegistry domainIdRegistry = Mockito.mock(DomainIdRegistry.class);
        when(domainIdRegistry.getDomainId(any())).thenReturn(1);

        linksService.loadLinks(domainIdRegistry, new FakeProcessHeartbeat(), inputData);
        keywordLoaderService.loadKeywords(domainIdRegistry, new FakeProcessHeartbeat(), inputData);
        documentLoaderService.loadDocuments(domainIdRegistry, new FakeProcessHeartbeat(), inputData);

        // These must be closed to finalize the associated files
        documentDbWriter.close();
        keywordLoaderService.close();

        Path journalFile = fileStorageService
                .getStorageBase(FileStorageBaseType.CURRENT)
                .asPath()
                .resolve("iw/page-index-0000.dat");

        assertTrue(Files.exists(journalFile), "Journal file not found: " + journalFile);
        assertTrue(Files.size(journalFile) > FILE_HEADER_SIZE_BYTES, "Journal file does not contain data");

        /** CONSTRUCT INDEX */

        createForwardIndex();
        createFullReverseIndex();
        createPrioReverseIndex();

        /** SWITCH INDEX */

        statefulIndex.switchIndex();

        // Move the docdb file to the live location
        Files.move(
                IndexLocations.getLinkdbWritePath(fileStorageService).resolve(DOCDB_FILE_NAME),
                IndexLocations.getLinkdbLivePath(fileStorageService).resolve(DOCDB_FILE_NAME)
        );
        // Reconnect the document reader to the new docdb file
        documentDbReader.reconnect();

        /** QUERY */

        var request = RpcQsQuery.newBuilder()
                .setQueryLimits(RpcQueryLimits.newBuilder()
                        .setTimeoutMs(1000)
                        .setResultsTotal(100)
                        .setResultsByDomain(10)
                        .setFetchSize(1000)
                        .build())
                .setQueryStrategy("AUTO")
                .setHumanQuery("\"This is how thinking works\"")
                .build();

        var params = QueryProtobufCodec.convertRequest(request);

        var query = queryFactory.createQuery(params, ResultRankingParameters.sensibleDefaults());


        var indexRequest = QueryProtobufCodec.convertQuery(request, query);

        System.out.println(indexRequest);

        var rs = indexGrpcService.executeSearch(new SearchParameters(indexRequest, new SearchSetAny()));

        System.out.println(rs);
    }


    private void createFullReverseIndex() throws IOException {

        Path outputFileDocs = ReverseIndexFullFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexFullFileNames.FileIdentifier.DOCS, ReverseIndexFullFileNames.FileVersion.NEXT);
        Path outputFileWords = ReverseIndexFullFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexFullFileNames.FileIdentifier.WORDS, ReverseIndexFullFileNames.FileVersion.NEXT);
        Path outputFilePositions = ReverseIndexFullFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexFullFileNames.FileIdentifier.POSITIONS, ReverseIndexFullFileNames.FileVersion.NEXT);

        Path workDir = IndexLocations.getIndexConstructionArea(fileStorageService);
        Path tmpDir = workDir.resolve("tmp");

        if (!Files.isDirectory(tmpDir)) Files.createDirectories(tmpDir);

        var constructor = new FullIndexConstructor(
                outputFileDocs,
                outputFileWords,
                outputFilePositions,
                IndexJournalReader::singleFile,
                this::addRankToIdEncoding,
                tmpDir);

        constructor.createReverseIndex(new FakeProcessHeartbeat(), "createReverseIndexFull", workDir);

    }

    private void createPrioReverseIndex() throws IOException {

        Path outputFileDocs = ReverseIndexPrioFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexPrioFileNames.FileIdentifier.DOCS, ReverseIndexPrioFileNames.FileVersion.NEXT);
        Path outputFileWords = ReverseIndexPrioFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexPrioFileNames.FileIdentifier.WORDS, ReverseIndexPrioFileNames.FileVersion.NEXT);
        Path outputFilePositions = ReverseIndexPrioFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ReverseIndexPrioFileNames.FileIdentifier.POSITIONS, ReverseIndexPrioFileNames.FileVersion.NEXT);

        Path workDir = IndexLocations.getIndexConstructionArea(fileStorageService);
        Path tmpDir = workDir.resolve("tmp");

        // The priority index only includes words that have bits indicating they are
        // important to the document.  This filter will act on the encoded {@see WordMetadata}
        LongPredicate wordMetaFilter = getPriorityIndexWordMetaFilter();

        var constructor = new FullIndexConstructor(
                outputFileDocs,
                outputFileWords,
                outputFilePositions,
                (path) -> IndexJournalReader.singleFile(path).filtering(wordMetaFilter),
                this::addRankToIdEncoding,
                tmpDir);

        constructor.createReverseIndex(new FakeProcessHeartbeat(), "createReverseIndexPrio", workDir);
    }

    private static LongPredicate getPriorityIndexWordMetaFilter() {

        long highPriorityFlags =
                WordFlags.Title.asBit()
                        | WordFlags.Subjects.asBit()
                        | WordFlags.TfIdfHigh.asBit()
                        | WordFlags.NamesWords.asBit()
                        | WordFlags.UrlDomain.asBit()
                        | WordFlags.UrlPath.asBit()
                        | WordFlags.Site.asBit()
                        | WordFlags.ExternalLink.asBit()
                        | WordFlags.SiteAdjacent.asBit();

        return r -> WordMetadata.hasAnyFlags(r, highPriorityFlags);
    }

    private void createForwardIndex() throws IOException {

        Path workDir = IndexLocations.getIndexConstructionArea(fileStorageService);
        Path outputFileDocsId = ForwardIndexFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ForwardIndexFileNames.FileIdentifier.DOC_ID, ForwardIndexFileNames.FileVersion.NEXT);
        Path outputFileDocsData = ForwardIndexFileNames.resolve(IndexLocations.getCurrentIndex(fileStorageService), ForwardIndexFileNames.FileIdentifier.DOC_DATA, ForwardIndexFileNames.FileVersion.NEXT);

        ForwardIndexConverter converter = new ForwardIndexConverter(new FakeProcessHeartbeat(),
                IndexJournalReader.paging(workDir),
                outputFileDocsId,
                outputFileDocsData,
                domainRankings
        );

        converter.convert();
    }

    private long addRankToIdEncoding(long docId) {
        return UrlIdCodec.addRank(
                255,
                docId);
    }

}
