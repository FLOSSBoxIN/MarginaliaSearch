package nu.marginalia.loading;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.mq.MqMessage;
import nu.marginalia.mq.inbox.MqInboxResponse;
import nu.marginalia.mq.inbox.MqSingleShotInbox;
import nu.marginalia.process.control.ProcessHeartbeat;
import nu.marginalia.process.log.WorkLog;
import plan.CrawlPlan;
import nu.marginalia.loading.loader.Loader;
import nu.marginalia.loading.loader.LoaderFactory;
import nu.marginalia.converting.instruction.Instruction;
import nu.marginalia.service.module.DatabaseModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static nu.marginalia.mqapi.ProcessInboxNames.LOADER_INBOX;

public class LoaderMain {
    private static final Logger logger = LoggerFactory.getLogger(LoaderMain.class);

    private final ConvertedDomainReader instructionsReader;
    private final LoaderFactory loaderFactory;

    private final ProcessHeartbeat heartbeat;
    private final MessageQueueFactory messageQueueFactory;
    private final FileStorageService fileStorageService;
    private final Gson gson;
    private volatile boolean running = true;

    final Thread processorThread;

    public static void main(String... args) throws Exception {
        new org.mariadb.jdbc.Driver();

        Injector injector = Guice.createInjector(
                new LoaderModule(),
                new DatabaseModule()
        );

        var instance = injector.getInstance(LoaderMain.class);
        var instructions = instance.fetchInstructions();
        instance.run(instructions);
    }

    @Inject
    public LoaderMain(ConvertedDomainReader instructionsReader,
                      HikariDataSource dataSource,
                      LoaderFactory loaderFactory,
                      ProcessHeartbeat heartbeat,
                      MessageQueueFactory messageQueueFactory,
                      FileStorageService fileStorageService,
                      Gson gson
                      ) {

        this.instructionsReader = instructionsReader;
        this.loaderFactory = loaderFactory;
        this.heartbeat = heartbeat;
        this.messageQueueFactory = messageQueueFactory;
        this.fileStorageService = fileStorageService;
        this.gson = gson;

        heartbeat.start();

        nukeTables(dataSource);

        processorThread = new Thread(this::processor, "Processor Thread");
        processorThread.start();
    }

    private void nukeTables(HikariDataSource dataSource) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
            stmt.execute("TRUNCATE TABLE EC_PAGE_DATA");
            stmt.execute("TRUNCATE TABLE EC_URL");
            stmt.execute("TRUNCATE TABLE EC_DOMAIN_LINK");
            stmt.execute("TRUNCATE TABLE DOMAIN_METADATA");
            stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SneakyThrows
    public void run(LoadRequest instructions) {
        var plan = instructions.getPlan();
        var logFile = plan.process.getLogFile();

        try {
            int loadTotal = 0;
            int loaded = 0;

            for (var unused : WorkLog.iterable(logFile)) {
                loadTotal++;
            }

            LoaderMain.loadTotal = loadTotal;

            for (var entry : WorkLog.iterable(logFile)) {
                heartbeat.setProgress(loaded++ / (double) loadTotal);

                load(plan, entry.path(), entry.cnt());
            }

            running = false;
            processorThread.join();
            instructions.ok();
        }
        catch (Exception ex) {
            logger.error("Failed to load", ex);
            instructions.err();
            throw ex;
        }
        finally {
            heartbeat.shutDown();
        }
        System.exit(0);
    }

    private volatile static int loadTotal;

    private void load(CrawlPlan plan, String path, int cnt) {
        Path destDir = plan.getProcessedFilePath(path);
        try {
            var loader = loaderFactory.create(cnt);
            var instructions = instructionsReader.read(destDir, cnt);
            processQueue.put(new LoadJob(path, loader, instructions));
        } catch (Exception e) {
            logger.error("Failed to load " + destDir, e);
        }
    }

    static final TaskStats taskStats = new TaskStats(100);

    private record LoadJob(String path, Loader loader, List<Instruction> instructionList) {
        public void run() {
            long startTime = System.currentTimeMillis();
            for (var i : instructionList) {
                try {
                    i.apply(loader);
                }
                catch (Exception ex) {
                    logger.error("Failed to load instruction {}", i);
                }
            }

            loader.finish();
            long loadTime = System.currentTimeMillis() - startTime;
            taskStats.observe(loadTime);
            logger.info("Loaded {}/{} : {} ({}) {}ms {} l/s", taskStats.getCount(),
                    loadTotal, path, loader.data.sizeHint, loadTime, taskStats.avgTime());
        }

    }

    private static final LinkedBlockingQueue<LoadJob> processQueue = new LinkedBlockingQueue<>(2);

    private void processor() {
        try {
            while (running || !processQueue.isEmpty()) {
                LoadJob job = processQueue.poll(1, TimeUnit.SECONDS);

                if (job != null) {
                    job.run();
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

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
