package nu.marginalia.loading;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import lombok.SneakyThrows;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.linkdb.LinkdbWriter;
import nu.marginalia.loading.documents.DocumentLoaderService;
import nu.marginalia.loading.documents.KeywordLoaderService;
import nu.marginalia.loading.domains.DomainIdRegistry;
import nu.marginalia.loading.domains.DomainLoaderService;
import nu.marginalia.loading.links.DomainLinksLoaderService;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.mq.MqMessage;
import nu.marginalia.mq.inbox.MqInboxResponse;
import nu.marginalia.mq.inbox.MqSingleShotInbox;
import nu.marginalia.process.control.ProcessHeartbeatImpl;
import nu.marginalia.worklog.BatchingWorkLogInspector;
import plan.CrawlPlan;
import nu.marginalia.service.module.DatabaseModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static nu.marginalia.mqapi.ProcessInboxNames.LOADER_INBOX;

public class LoaderMain {
    private static final Logger logger = LoggerFactory.getLogger(LoaderMain.class);

    private final ProcessHeartbeatImpl heartbeat;
    private final MessageQueueFactory messageQueueFactory;
    private final FileStorageService fileStorageService;
    private final LinkdbWriter linkdbWriter;
    private final LoaderIndexJournalWriter journalWriter;
    private final DomainLoaderService domainService;
    private final DomainLinksLoaderService linksService;
    private final KeywordLoaderService keywordLoaderService;
    private final DocumentLoaderService documentLoaderService;
    private final Gson gson;

    public static void main(String... args) throws Exception {
        new org.mariadb.jdbc.Driver();

        Injector injector = Guice.createInjector(
                new LoaderModule(),
                new DatabaseModule()
        );

        var instance = injector.getInstance(LoaderMain.class);
        try {
            var instructions = instance.fetchInstructions();
            logger.info("Instructions received");
            instance.run(instructions);
        }
        catch (Exception ex) {
            logger.error("Error running loader", ex);
        }
    }

    @Inject
    public LoaderMain(ProcessHeartbeatImpl heartbeat,
                      MessageQueueFactory messageQueueFactory,
                      FileStorageService fileStorageService,
                      LinkdbWriter linkdbWriter,
                      LoaderIndexJournalWriter journalWriter,
                      DomainLoaderService domainService,
                      DomainLinksLoaderService linksService,
                      KeywordLoaderService keywordLoaderService,
                      DocumentLoaderService documentLoaderService,
                      Gson gson
                      ) {

        this.heartbeat = heartbeat;
        this.messageQueueFactory = messageQueueFactory;
        this.fileStorageService = fileStorageService;
        this.linkdbWriter = linkdbWriter;
        this.journalWriter = journalWriter;
        this.domainService = domainService;
        this.linksService = linksService;
        this.keywordLoaderService = keywordLoaderService;
        this.documentLoaderService = documentLoaderService;
        this.gson = gson;

        heartbeat.start();
    }

    @SneakyThrows
    void run(LoadRequest instructions) {
        var plan = instructions.getPlan();
        var processLogFile = plan.process.getLogFile();

        Path inputDataDir = plan.process.getDir();
        int validBatchCount = BatchingWorkLogInspector.getValidBatches(processLogFile);

        DomainIdRegistry domainIdRegistry =
                domainService.getOrCreateDomainIds(
                        inputDataDir,
                        validBatchCount);

        try {
            var results = ForkJoinPool.commonPool()
                    .invokeAll(
                        List.of(
                            () -> linksService.loadLinks(domainIdRegistry, heartbeat, inputDataDir, validBatchCount),
                            () -> keywordLoaderService.loadKeywords(domainIdRegistry, heartbeat, inputDataDir, validBatchCount),
                            () -> documentLoaderService.loadDocuments(domainIdRegistry, heartbeat, inputDataDir, validBatchCount)
                        )
            );

            for (var result : results) {
                if (result.state() == Future.State.FAILED) {
                    throw result.exceptionNow();
                }
            }

            instructions.ok();
        }
        catch (Exception ex) {
            instructions.err();
            logger.error("Error", ex);
        }
        finally {
            journalWriter.close();
            linkdbWriter.close();
            heartbeat.shutDown();
        }

        System.exit(0);
    }

    private static class LoadRequest {
        private final CrawlPlan plan;
        private final MqMessage message;
        private final MqSingleShotInbox inbox;

        LoadRequest(CrawlPlan plan, MqMessage message, MqSingleShotInbox inbox) {
            this.plan = plan;
            this.message = message;
            this.inbox = inbox;
        }

        public CrawlPlan getPlan() {
            return plan;
        }

        public void ok() {
            inbox.sendResponse(message, MqInboxResponse.ok());
        }
        public void err() {
            inbox.sendResponse(message, MqInboxResponse.err());
        }

    }

    private LoadRequest fetchInstructions() throws Exception {

        var inbox = messageQueueFactory.createSingleShotInbox(LOADER_INBOX, UUID.randomUUID());

        var msgOpt = getMessage(inbox, nu.marginalia.mqapi.loading.LoadRequest.class.getSimpleName());
        if (msgOpt.isEmpty())
            throw new RuntimeException("No instruction received in inbox");
        var msg = msgOpt.get();

        if (!nu.marginalia.mqapi.loading.LoadRequest.class.getSimpleName().equals(msg.function())) {
            throw new RuntimeException("Unexpected message in inbox: " + msg);
        }

        var request = gson.fromJson(msg.payload(), nu.marginalia.mqapi.loading.LoadRequest.class);

        var processData = fileStorageService.getStorage(request.processedDataStorage);

        var plan = new CrawlPlan(null, null,  new CrawlPlan.WorkDir(processData.path(), "processor.log"));

        return new LoadRequest(plan, msg, inbox);
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
