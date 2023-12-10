package nu.marginalia.crawl;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import nu.marginalia.ProcessConfiguration;
import nu.marginalia.ProcessConfigurationModule;
import nu.marginalia.UserAgent;
import nu.marginalia.WmsaHome;
import nu.marginalia.atags.source.AnchorTagsSource;
import nu.marginalia.atags.source.AnchorTagsSourceFactory;
import nu.marginalia.crawl.retreival.CrawlDataReference;
import nu.marginalia.crawl.retreival.DomainProber;
import nu.marginalia.crawl.retreival.fetcher.HttpFetcherImpl;
import nu.marginalia.crawl.spec.CrawlSpecProvider;
import nu.marginalia.crawl.spec.DbCrawlSpecProvider;
import nu.marginalia.crawl.spec.ParquetCrawlSpecProvider;
import nu.marginalia.crawling.io.CrawledDomainReader;
import nu.marginalia.crawlspec.CrawlSpecFileNames;
import nu.marginalia.storage.FileStorageService;
import nu.marginalia.model.crawlspec.CrawlSpecRecord;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.mq.MqMessage;
import nu.marginalia.mq.inbox.MqInboxResponse;
import nu.marginalia.mq.inbox.MqSingleShotInbox;
import nu.marginalia.process.control.ProcessHeartbeatImpl;
import nu.marginalia.process.log.WorkLog;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.crawling.io.CrawledDomainWriter;
import nu.marginalia.crawl.retreival.CrawlerRetreiver;
import nu.marginalia.crawl.retreival.fetcher.HttpFetcher;
import nu.marginalia.util.SimpleBlockingThreadPool;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.internal.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static nu.marginalia.mqapi.ProcessInboxNames.CRAWLER_INBOX;

public class CrawlerMain {
    private final static Logger logger = LoggerFactory.getLogger(CrawlerMain.class);

    private final ProcessHeartbeatImpl heartbeat;
    private final ConnectionPool connectionPool = new ConnectionPool(5, 10, TimeUnit.SECONDS);

    private final Dispatcher dispatcher = new Dispatcher(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 5, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Util.threadFactory("OkHttp Dispatcher", true)));

    private final UserAgent userAgent;
    private final MessageQueueFactory messageQueueFactory;
    private final DomainProber domainProber;
    private final FileStorageService fileStorageService;
    private final DbCrawlSpecProvider dbCrawlSpecProvider;
    private final AnchorTagsSourceFactory anchorTagsSourceFactory;
    private final Gson gson;
    private final int node;
    private final SimpleBlockingThreadPool pool;

    private final Map<String, String> processingIds = new ConcurrentHashMap<>();
    private final CrawledDomainReader reader = new CrawledDomainReader();

    final AbortMonitor abortMonitor = AbortMonitor.getInstance();

    volatile int totalTasks;
    final AtomicInteger tasksDone = new AtomicInteger(0);
    private final CrawlLimiter limiter = new CrawlLimiter();

    @Inject
    public CrawlerMain(UserAgent userAgent,
                       ProcessHeartbeatImpl heartbeat,
                       MessageQueueFactory messageQueueFactory, DomainProber domainProber,
                       FileStorageService fileStorageService,
                       ProcessConfiguration processConfiguration,
                       DbCrawlSpecProvider dbCrawlSpecProvider,
                       AnchorTagsSourceFactory anchorTagsSourceFactory,
                       Gson gson) {
        this.heartbeat = heartbeat;
        this.userAgent = userAgent;
        this.messageQueueFactory = messageQueueFactory;
        this.domainProber = domainProber;
        this.fileStorageService = fileStorageService;
        this.dbCrawlSpecProvider = dbCrawlSpecProvider;
        this.anchorTagsSourceFactory = anchorTagsSourceFactory;
        this.gson = gson;
        this.node = processConfiguration.node();

        // maybe need to set -Xss for JVM to deal with this?
        pool = new SimpleBlockingThreadPool("CrawlerPool", CrawlLimiter.maxPoolSize, 1);
    }

    public static void main(String... args) throws Exception {
        if (!AbortMonitor.getInstance().isAlive()) {
            System.err.println("Remove abort file first");
            return;
        }

        // This must run *early*
        System.setProperty("http.agent", WmsaHome.getUserAgent().uaString());

        // If these aren't set properly, the JVM will hang forever on some requests
        System.setProperty("sun.net.client.defaultConnectTimeout", "30000");
        System.setProperty("sun.net.client.defaultReadTimeout", "30000");

        // We don't want to use too much memory caching sessions for https
        System.setProperty("javax.net.ssl.sessionCacheSize", "2048");

        try {
            Injector injector = Guice.createInjector(
                    new CrawlerModule(),
                    new ProcessConfigurationModule("crawler"),
                    new DatabaseModule()
            );
            var crawler = injector.getInstance(CrawlerMain.class);

            var instructions = crawler.fetchInstructions();
            try {
                crawler.run(instructions.specProvider, instructions.outputDir);
                instructions.ok();
            } catch (Exception ex) {
                logger.error("Crawler failed", ex);
                instructions.err();
            }

            TimeUnit.SECONDS.sleep(5);
        }
        catch (Exception ex) {
            logger.error("Uncaught exception", ex);
        }
        System.exit(0);
    }

    public void run(CrawlSpecProvider specProvider, Path outputDir) throws InterruptedException, IOException {

        heartbeat.start();
        try (WorkLog workLog = new WorkLog(outputDir.resolve("crawler.log"));
             AnchorTagsSource anchorTagsSource = anchorTagsSourceFactory.create(specProvider.getDomains())
        ) {

            // First a validation run to ensure the file is all good to parse
            logger.info("Validating JSON");

            totalTasks = specProvider.totalCount();

            logger.info("Queued {} crawl tasks, let's go", totalTasks);

            try (var specStream = specProvider.stream()) {
                specStream
                        .takeWhile((e) -> abortMonitor.isAlive())
                        .filter(e -> !workLog.isJobFinished(e.domain))
                        .filter(e -> processingIds.put(e.domain, "") == null)
                        .map(e -> new CrawlTask(e, anchorTagsSource, outputDir, workLog))
                        .forEach(pool::submitQuietly);
            }

            logger.info("Shutting down the pool, waiting for tasks to complete...");

            pool.shutDown();
            int activePoolCount = pool.getActiveCount();

            while (!pool.awaitTermination(5, TimeUnit.HOURS)) {
                int newActivePoolCount = pool.getActiveCount();
                if (activePoolCount == newActivePoolCount) {
                    logger.warn("Aborting the last {} jobs of the crawl, taking too long", newActivePoolCount);
                    pool.shutDownNow();
                } else {
                    activePoolCount = newActivePoolCount;
                }
            }
        }
        catch (Exception ex) {
            logger.warn("Exception in crawler", ex);
        }
        finally {
            heartbeat.shutDown();
        }
    }

    class CrawlTask implements SimpleBlockingThreadPool.Task {

        private final CrawlSpecRecord specification;

        private final String domain;
        private final String id;

        private final AnchorTagsSource anchorTagsSource;
        private final Path outputDir;
        private final WorkLog workLog;

        CrawlTask(CrawlSpecRecord specification,
                  AnchorTagsSource anchorTagsSource,
                  Path outputDir,
                  WorkLog workLog) {
            this.specification = specification;
            this.anchorTagsSource = anchorTagsSource;
            this.outputDir = outputDir;
            this.workLog = workLog;

            this.domain = specification.domain;
            this.id = Integer.toHexString(domain.hashCode());
        }

        @Override
        public void run() throws Exception {

            limiter.waitForEnoughRAM();

            HttpFetcher fetcher = new HttpFetcherImpl(userAgent.uaString(), dispatcher, connectionPool);

            try (CrawledDomainWriter writer = new CrawledDomainWriter(outputDir, domain, id);
                 CrawlDataReference reference = getReference())
            {
                Thread.currentThread().setName("crawling:" + domain);

                var domainLinks = anchorTagsSource.getAnchorTags(domain);

                var retreiver = new CrawlerRetreiver(fetcher, domainProber, specification, writer::accept);
                int size = retreiver.fetch(domainLinks, reference);

                workLog.setJobToFinished(domain, writer.getOutputFile().toString(), size);
                heartbeat.setProgress(tasksDone.incrementAndGet() / (double) totalTasks);

                logger.info("Fetched {}", domain);

            } catch (Exception e) {
                logger.error("Error fetching domain " + domain, e);
            }
            finally {
                // We don't need to double-count these; it's also kept int he workLog
                processingIds.remove(domain);
                Thread.currentThread().setName("[idle]");
            }
        }

        private CrawlDataReference getReference() {
            try {
                var dataStream = reader.createDataStream(outputDir, domain, id);
                return new CrawlDataReference(dataStream);
            } catch (IOException e) {
                logger.debug("Failed to read previous crawl data for {}", specification.domain);
                return new CrawlDataReference();
            }
        }

    }



    private static class CrawlRequest {
        private final CrawlSpecProvider specProvider;
        private final Path outputDir;
        private final MqMessage message;
        private final MqSingleShotInbox inbox;

        CrawlRequest(CrawlSpecProvider specProvider, Path outputDir, MqMessage message, MqSingleShotInbox inbox) {
            this.message = message;
            this.inbox = inbox;
            this.specProvider = specProvider;
            this.outputDir = outputDir;
        }


        public void ok() {
            inbox.sendResponse(message, MqInboxResponse.ok());
        }
        public void err() {
            inbox.sendResponse(message, MqInboxResponse.err());
        }

    }

    private CrawlRequest fetchInstructions() throws Exception {

        var inbox = messageQueueFactory.createSingleShotInbox(CRAWLER_INBOX, node, UUID.randomUUID());

        logger.info("Waiting for instructions");
        var msgOpt = getMessage(inbox, nu.marginalia.mqapi.crawling.CrawlRequest.class.getSimpleName());
        var msg = msgOpt.orElseThrow(() -> new RuntimeException("No message received"));

        var request = gson.fromJson(msg.payload(), nu.marginalia.mqapi.crawling.CrawlRequest.class);

        CrawlSpecProvider specProvider;

        if (request.specStorage != null) {
            var specData = fileStorageService.getStorage(request.specStorage);
            specProvider = new ParquetCrawlSpecProvider(CrawlSpecFileNames.resolve(specData));
        }
        else {
            specProvider = dbCrawlSpecProvider;
        }

        var crawlData = fileStorageService.getStorage(request.crawlStorage);

        return new CrawlRequest(
                specProvider,
                crawlData.asPath(),
                msg,
                inbox);
    }

    private Optional<MqMessage> getMessage(MqSingleShotInbox inbox, String expectedFunction) throws SQLException, InterruptedException {
        var opt = inbox.waitForMessage(30, TimeUnit.SECONDS);
        if (opt.isPresent()) {
            if (!opt.get().function().equals(expectedFunction)) {
                throw new RuntimeException("Unexpected function: " + opt.get().function());
            }
            return opt;
        }
        else {
            var stolenMessage = inbox.stealMessage(msg -> msg.function().equals(expectedFunction));
            stolenMessage.ifPresent(mqMessage -> logger.info("Stole message {}", mqMessage));
            return stolenMessage;
        }
    }

}
