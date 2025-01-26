package nu.marginalia.crawling.retreival;

import nu.marginalia.UserAgent;
import nu.marginalia.WmsaHome;
import nu.marginalia.atags.model.DomainLinks;
import nu.marginalia.crawl.CrawlerMain;
import nu.marginalia.crawl.DomainStateDb;
import nu.marginalia.crawl.fetcher.Cookies;
import nu.marginalia.crawl.fetcher.HttpFetcher;
import nu.marginalia.crawl.fetcher.HttpFetcherImpl;
import nu.marginalia.crawl.fetcher.warc.WarcRecorder;
import nu.marginalia.crawl.retreival.*;
import nu.marginalia.io.SerializableCrawlDataStream;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.crawldata.CrawledDocument;
import nu.marginalia.model.crawldata.CrawledDomain;
import nu.marginalia.model.crawldata.SerializableCrawlData;
import nu.marginalia.parquet.crawldata.CrawledDocumentParquetRecordFileWriter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.netpreserve.jwarc.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("slow")
class CrawlerRetreiverTest {
    private HttpFetcher httpFetcher;

    Path tempFileWarc1;
    Path tempFileParquet1;
    Path tempFileWarc2;
    Path tempFileParquet2;
    Path tempFileWarc3;
    Path tempFileDb;
    @BeforeEach
    public void setUp() throws IOException {
        httpFetcher = new HttpFetcherImpl("search.marginalia.nu; testing a bit :D");
        tempFileParquet1 = Files.createTempFile("crawling-process", ".parquet");
        tempFileParquet2 = Files.createTempFile("crawling-process", ".parquet");
        tempFileDb = Files.createTempFile("crawling-process", ".db");

    }

    @BeforeAll
    public static void setUpAll() {
        // this must be done to avoid java inserting its own user agent for the sitemap requests
        System.setProperty("http.agent", WmsaHome.getUserAgent().uaString());
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (tempFileWarc1 != null) {
            Files.deleteIfExists(tempFileWarc1);
        }
        if (tempFileParquet1 != null) {
            Files.deleteIfExists(tempFileParquet1);
        }
        if (tempFileWarc2 != null) {
            Files.deleteIfExists(tempFileWarc2);
        }
        if (tempFileParquet2 != null) {
            Files.deleteIfExists(tempFileParquet2);
        }
        if (tempFileWarc3 != null) {
            Files.deleteIfExists(tempFileWarc3);
        }
    }

    @Test
    public void testWarcOutput() throws IOException {
        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(5)
                .domain("www.marginalia.nu")
                .urls(List.of("https://www.marginalia.nu/misc/debian-laptop-install-log/"))
                .build();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("crawling-process", "warc");

            doCrawl(tempFile, specs);

            Set<String> requests = new HashSet<>();
            Set<String> responses = new HashSet<>();

            try (var reader = new WarcReader(tempFile)) {
                reader.forEach(record -> {
                    if (record instanceof WarcRequest req) {
                        requests.add(req.target());
                        System.out.println(req.type() + ":" + req.target());
                    }
                    else if (record instanceof WarcResponse rsp) {
                        responses.add(rsp.target());
                        System.out.println(rsp.type() + ":" + rsp.target());
                    }
                    else {
                        System.out.println(record.type());
                    }
                });
            }

            assertTrue(requests.contains("https://www.marginalia.nu/misc/debian-laptop-install-log/"));
            assertEquals(requests, responses);
        }
        finally {
            if (tempFile != null)
                Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testWarcOutputNoKnownUrls() throws IOException {
        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(5)
                .domain("www.marginalia.nu")
                .urls(List.of())
                .build();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("crawling-process", "warc");

            doCrawl(tempFile, specs);

            Set<String> requests = new HashSet<>();
            Set<String> responses = new HashSet<>();

            try (var reader = new WarcReader(tempFile)) {
                reader.forEach(record -> {
                    if (record instanceof WarcRequest req) {
                        requests.add(req.target());
                        System.out.println(req.type() + ":" + req.target());
                    }
                    else if (record instanceof WarcResponse rsp) {
                        responses.add(rsp.target());
                        System.out.println(rsp.type() + ":" + rsp.target());
                    }
                    else {
                        System.out.println(record.type());
                    }
                });
            }

            assertTrue(responses.size() > 5, "Should have fetched more than 5 URLs");
            assertEquals(requests, responses);
        }
        finally {
            if (tempFile != null)
                Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void testResync() throws Exception {
        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(5)
                .domain("www.marginalia.nu")
                .urls(List.of("https://www.marginalia.nu/misc/debian-laptop-install-log/"))
                .build();
        tempFileWarc1 = Files.createTempFile("crawling-process", "warc");
        tempFileWarc2 = Files.createTempFile("crawling-process", "warc");

        doCrawl(tempFileWarc1, specs);

        Set<String> requests = new HashSet<>();
        Set<String> responses = new HashSet<>();

        var revisitCrawlFrontier = new DomainCrawlFrontier(
                new EdgeDomain("www.marginalia.nu"),
                List.of(), 100);
        var resync = new CrawlerWarcResynchronizer(revisitCrawlFrontier,
                new WarcRecorder(tempFileWarc2, new Cookies())
        );

        // truncate the size of the file to simulate a crash
        simulatePartialWrite(tempFileWarc1);

        resync.run(tempFileWarc1);
        assertTrue(revisitCrawlFrontier.addKnown(new EdgeUrl("https://www.marginalia.nu/misc/debian-laptop-install-log/")));

        try (var reader = new WarcReader(tempFileWarc2)) {
            reader.forEach(record -> {
                if (record instanceof WarcRequest req) {
                    requests.add(req.target());
                    System.out.println(req.type() + ":" + req.target());
                }
                else if (record instanceof WarcResponse rsp) {
                    responses.add(rsp.target());
                    System.out.println(rsp.type() + ":" + rsp.target());
                }
                else {
                    System.out.println(record.type());
                }
            });
        }

        assertTrue(requests.contains("https://www.marginalia.nu/misc/debian-laptop-install-log/"));
        assertEquals(requests, responses);
    }

    @Test
    public void testWithKnownDomains() throws IOException {
        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(5)
                .domain("www.marginalia.nu")
                .urls(List.of("https://www.marginalia.nu/misc/debian-laptop-install-log/"))
                .build();

        List<SerializableCrawlData> data = new ArrayList<>();

        tempFileWarc1 = Files.createTempFile("crawling-process", ".warc");

        doCrawl(tempFileWarc1, specs);

        convertToParquet(tempFileWarc1, tempFileParquet1);

        try (var stream = SerializableCrawlDataStream.openDataStream(tempFileParquet1)) {
            while (stream.hasNext()) {
                if (stream.next() instanceof CrawledDocument doc) {
                    data.add(doc);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var fetchedUrls =
                data.stream()
                        .peek(System.out::println)
                        .filter(CrawledDocument.class::isInstance)
                        .map(CrawledDocument.class::cast)
                        .map(doc -> doc.url)
                        .collect(Collectors.toSet());

        assertTrue(fetchedUrls.contains("https://www.marginalia.nu/"));
        assertTrue(fetchedUrls.contains("https://www.marginalia.nu/misc/debian-laptop-install-log/"));

        data.stream().filter(CrawledDocument.class::isInstance)
                .map(CrawledDocument.class::cast)
                .forEach(doc -> System.out.println(doc.url + "\t" + doc.crawlerStatus + "\t" + doc.httpStatus));

    }

    @Test
    public void testRedirect() throws IOException, URISyntaxException {
        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(3)
                .domain("www.marginalia.nu")
                .urls(List.of(
                        "https://www.marginalia.nu/log/06-optimization.gmi"
                        ))
                .build();

        List<SerializableCrawlData> data = new ArrayList<>();

        tempFileWarc1 = Files.createTempFile("crawling-process", ".warc");

        DomainCrawlFrontier frontier = doCrawl(tempFileWarc1, specs);

        assertTrue(frontier.isVisited(new EdgeUrl("https://www.marginalia.nu/log/06-optimization.gmi/")));
        assertTrue(frontier.isVisited(new EdgeUrl("https://www.marginalia.nu/log/06-optimization.gmi")));
        assertTrue(frontier.isVisited(new EdgeUrl("https://www.marginalia.nu/")));

        assertFalse(frontier.isVisited(new EdgeUrl("https://www.marginalia.nu/log/06-optimization/")));
        assertTrue(frontier.isKnown(new EdgeUrl("https://www.marginalia.nu/log/06-optimization/")));

        convertToParquet(tempFileWarc1, tempFileParquet1);

        try (var stream = SerializableCrawlDataStream.openDataStream(tempFileParquet1)) {
            while (stream.hasNext()) {
                if (stream.next() instanceof CrawledDocument doc) {
                    data.add(doc);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // The URL https://www.marginalia.nu/log/06-optimization.gmi
        // redirects to https://www.marginalia.nu/log/06-optimization.gmi/  (note the trailing slash)
        //
        // Ensure that the redirect is followed, and that the trailing slash is added
        // to the url as reported in the parquet file.

        var fetchedUrls =
                data.stream()
                        .filter(CrawledDocument.class::isInstance)
                        .map(CrawledDocument.class::cast)
                        .peek(doc -> System.out.println(doc.url))
                        .map(doc -> doc.url)
                        .collect(Collectors.toSet());

        assertEquals(Set.of("https://www.marginalia.nu/",
                            "https://www.marginalia.nu/favicon.ico",
                            "https://www.marginalia.nu/log/06-optimization.gmi/"),
                    fetchedUrls);

    }

    @Test
    public void testEmptySet() throws IOException {

        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(5)
                .domain("www.marginalia.nu")
                .urls(List.of())
                .build();


        List<SerializableCrawlData> data = new ArrayList<>();

        tempFileWarc1 = Files.createTempFile("crawling-process", ".warc");

        doCrawl(tempFileWarc1, specs);
        convertToParquet(tempFileWarc1, tempFileParquet1);

        try (var stream = SerializableCrawlDataStream.openDataStream(tempFileParquet1)) {
            while (stream.hasNext()) {
                if (stream.next() instanceof CrawledDocument doc) {
                    data.add(doc);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        data.stream().filter(CrawledDocument.class::isInstance)
                .map(CrawledDocument.class::cast)
                .forEach(doc -> System.out.println(doc.url + "\t" + doc.crawlerStatus + "\t" + doc.httpStatus));

        var fetchedUrls =
                data.stream().filter(CrawledDocument.class::isInstance)
                        .map(CrawledDocument.class::cast)
                        .map(doc -> doc.url)
                        .collect(Collectors.toSet());

        assertTrue(fetchedUrls.contains("https://www.marginalia.nu/"));

        Assertions.assertTrue(
                data.stream().filter(CrawledDocument.class::isInstance)
                    .map(CrawledDocument.class::cast)
                    .anyMatch(doc -> "OK".equals(doc.crawlerStatus))
        );
    }

    @Test
    public void testRecrawl() throws IOException {

        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(12)
                .domain("www.marginalia.nu")
                .urls(List.of("https://www.marginalia.nu/some-dead-link"))
                .build();


        tempFileWarc1 = Files.createTempFile("crawling-process", ".warc.gz");
        tempFileWarc2 = Files.createTempFile("crawling-process", ".warc.gz");

        doCrawl(tempFileWarc1, specs);
        convertToParquet(tempFileWarc1, tempFileParquet1);
        doCrawlWithReferenceStream(specs,
                SerializableCrawlDataStream.openDataStream(tempFileParquet1)
        );
        convertToParquet(tempFileWarc2, tempFileParquet2);

        try (var reader = new WarcReader(tempFileWarc2)) {
            WarcXResponseReference.register(reader);

            reader.forEach(record -> {
                if (record instanceof WarcResponse rsp) {
                    try {
                        System.out.println(rsp.type() + ":" + rsp.target() + "/" + rsp.http().status());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (record instanceof WarcMetadata rsp) {
                    System.out.println("meta:" + rsp.target());
                }
            });
        }

        try (var ds = SerializableCrawlDataStream.openDataStream(tempFileParquet2)) {
            while (ds.hasNext()) {
                var doc = ds.next();
                if (doc instanceof CrawledDomain dr) {
                    System.out.println(dr.domain + "/" + dr.crawlerStatus);
                }
                else if (doc instanceof CrawledDocument dc) {
                    System.out.println(dc.url + "/" + dc.crawlerStatus + "/" + dc.httpStatus + "/" + dc.timestamp);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void convertToParquet(Path tempFileWarc2, Path tempFileParquet2) {
        CrawledDocumentParquetRecordFileWriter.convertWarc("www.marginalia.nu",
                new UserAgent("test", "test"), tempFileWarc2, tempFileParquet2);
    }


    @Test
    public void testRecrawlWithResync() throws Exception {

        var specs = CrawlerMain.CrawlSpecRecord
                .builder()
                .crawlDepth(12)
                .domain("www.marginalia.nu")
                .urls(List.of("https://www.marginalia.nu/some-dead-link"))
                .build();


        tempFileWarc1 = Files.createTempFile("crawling-process", ".warc.gz");
        tempFileWarc2 = Files.createTempFile("crawling-process", ".warc.gz");
        tempFileWarc3 = Files.createTempFile("crawling-process", ".warc.gz");

        Map<Class<? extends SerializableCrawlData>, List<SerializableCrawlData>> data = new HashMap<>();

        doCrawl(tempFileWarc1, specs);

        convertToParquet(tempFileWarc1, tempFileParquet1);

        try (var stream = SerializableCrawlDataStream.openDataStream(tempFileParquet1)) {
            while (stream.hasNext()) {
                var doc = stream.next();
                data.computeIfAbsent(doc.getClass(), c -> new ArrayList<>()).add(doc);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var stream = SerializableCrawlDataStream.openDataStream(tempFileParquet1);

        System.out.println("---");

        doCrawlWithReferenceStream(specs, stream);

        var revisitCrawlFrontier = new DomainCrawlFrontier(
                new EdgeDomain("www.marginalia.nu"),
                List.of(), 100);

        var resync = new CrawlerWarcResynchronizer(revisitCrawlFrontier,
                new WarcRecorder(tempFileWarc3, new Cookies())
        );

        // truncate the size of the file to simulate a crash
        simulatePartialWrite(tempFileWarc2);

        resync.run(tempFileWarc2);

        assertTrue(revisitCrawlFrontier.addKnown(new EdgeUrl("https://www.marginalia.nu/")));
        convertToParquet(tempFileWarc3, tempFileParquet2);


        try (var reader = new WarcReader(tempFileWarc3)) {
            WarcXResponseReference.register(reader);

            reader.forEach(record -> {
                if (record instanceof WarcResponse rsp) {
                    try {
                        System.out.println(rsp.type() + ":" + rsp.target() + "/" + rsp.http().status());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (record instanceof WarcMetadata rsp) {
                    System.out.println("meta:" + rsp.target());
                }
            });
        }

        try (var ds = SerializableCrawlDataStream.openDataStream(tempFileParquet2)) {
            while (ds.hasNext()) {
                var doc = ds.next();
                if (doc instanceof CrawledDomain dr) {
                    System.out.println(dr.domain + "/" + dr.crawlerStatus);
                }
                else if (doc instanceof CrawledDocument dc) {
                    System.out.println(dc.url + "/" + dc.crawlerStatus + "/" + dc.httpStatus + "/" + dc.timestamp);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void simulatePartialWrite(Path tempFileWarc2) throws IOException {
        try (var raf = new RandomAccessFile(tempFileWarc2.toFile(), "rw")) {
            raf.setLength(raf.length() - 10);
        }
    }

    private void doCrawlWithReferenceStream(CrawlerMain.CrawlSpecRecord specs, SerializableCrawlDataStream stream) {
        try (var recorder = new WarcRecorder(tempFileWarc2, new Cookies());
             var db = new DomainStateDb(tempFileDb)
        ) {
            new CrawlerRetreiver(httpFetcher, new DomainProber(d -> true), specs, db, recorder).crawlDomain(new DomainLinks(),
                    new CrawlDataReference(stream));
        }
        catch (IOException | SQLException ex) {
            Assertions.fail(ex);
        }
    }

    @NotNull
    private DomainCrawlFrontier doCrawl(Path tempFileWarc1, CrawlerMain.CrawlSpecRecord specs) {
        try (var recorder = new WarcRecorder(tempFileWarc1, new Cookies());
             var db = new DomainStateDb(tempFileDb)
        ) {
            var crawler = new CrawlerRetreiver(httpFetcher, new DomainProber(d -> true), specs, db, recorder);
            crawler.crawlDomain();
            return crawler.getCrawlFrontier();
        } catch (IOException| SQLException  ex) {
            Assertions.fail(ex);
            return null; // unreachable
        }
    }
}