package nu.marginalia.actor.task;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.IndexLocations;
import nu.marginalia.actor.prototype.RecordActorPrototype;
import nu.marginalia.actor.state.ActorResumeBehavior;
import nu.marginalia.actor.state.ActorStep;
import nu.marginalia.actor.state.Resume;
import nu.marginalia.index.api.IndexMqClient;
import nu.marginalia.index.api.IndexMqEndpoints;
import nu.marginalia.mq.MqMessageState;
import nu.marginalia.mq.outbox.MqOutbox;
import nu.marginalia.mqapi.converting.ConvertRequest;
import nu.marginalia.mqapi.index.CreateIndexRequest;
import nu.marginalia.mqapi.index.IndexName;
import nu.marginalia.mqapi.loading.LoadRequest;
import nu.marginalia.nodecfg.NodeConfigurationService;
import nu.marginalia.process.ProcessOutboxes;
import nu.marginalia.process.ProcessService;
import nu.marginalia.service.module.ServiceConfiguration;
import nu.marginalia.storage.FileStorageService;
import nu.marginalia.storage.model.FileStorageId;
import nu.marginalia.storage.model.FileStorageState;
import nu.marginalia.storage.model.FileStorageType;
import nu.marginalia.svc.BackupService;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;

@Singleton
public class ConvertAndLoadActor extends RecordActorPrototype {

    // STATES
    public static final String RERANK = "RERANK";
    private final ActorProcessWatcher processWatcher;
    private final MqOutbox mqConverterOutbox;
    private final MqOutbox mqLoaderOutbox;
    private final MqOutbox mqIndexConstructorOutbox;
    private final MqOutbox indexOutbox;
    private final FileStorageService storageService;
    private final BackupService backupService;
    private final NodeConfigurationService nodeConfigurationService;

    private final int nodeId;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public record Initial(FileStorageId fid) implements ActorStep {}

    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record Convert(FileStorageId crawlId, FileStorageId  processedId, long msgId) implements ActorStep {
        public Convert(FileStorageId crawlId, FileStorageId  processedId) { this(crawlId, processedId, -1); }
    }
    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record Load(List<FileStorageId> processedId, long msgId) implements ActorStep {
        public Load(List<FileStorageId> processedId) { this(processedId, -1); }
    }

    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record Backup(List<FileStorageId> processedIds) implements ActorStep { }
    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record Rerank(long id) implements ActorStep { public Rerank() { this(-1); } }
    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record ReindexFwd(long id) implements ActorStep {  public ReindexFwd() { this(-1); } }
    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record ReindexFull(long id) implements ActorStep {  public ReindexFull() { this(-1); } }
    @Resume(behavior = ActorResumeBehavior.RETRY)
    public record ReindexPrio(long id) implements ActorStep {  public ReindexPrio() { this(-1); } }
    public record SwitchIndex() implements ActorStep {}

    @Override
    public ActorStep transition(ActorStep self) throws Exception {
        logger.info("{}", self);
        return switch (self) {
            case Initial(FileStorageId fid) -> {
                var storage = storageService.getStorage(fid);

                if (storage == null) yield new Error("Bad storage id");
                if (storage.type() != FileStorageType.CRAWL_DATA) yield new Error("Bad storage type " + storage.type());


                var processedArea = storageService.allocateStorage(FileStorageType.PROCESSED_DATA, "processed-data",
                        "Processed Data; " + storage.description());

                storageService.setFileStorageState(processedArea.id(), FileStorageState.NEW);
                storageService.relateFileStorages(storage.id(), processedArea.id());

                yield new Convert(fid, processedArea.id());
            }
            case Convert(FileStorageId crawlId, FileStorageId processedId, long msgId) when msgId < 0 ->
                    new Convert(crawlId, processedId, mqConverterOutbox.sendAsync(ConvertRequest.forCrawlData(crawlId, processedId)));
            case Convert(FileStorageId crawlId, FileStorageId processedId, long msgId) -> {
                var rsp = processWatcher.waitResponse(mqConverterOutbox, ProcessService.ProcessId.CONVERTER, msgId);

                if (rsp.state() != MqMessageState.OK)
                    yield new Error("Converter failed");

                if (!shouldAutoClean()) {
                    // If we're not auto-cleaning, we need to clean the NEW flag for the processed storage
                    storageService.setFileStorageState(processedId, FileStorageState.UNSET);
                    // (if we do auto-clean, we skip this step and purge the items after loading)
                }

                yield new Load(List.of(processedId));
            }
            case Load(List<FileStorageId> processedIds, long msgId) when msgId < 0 -> {
                // clear the output directory of the loader from any debris from partial jobs that have been aborted
                Files.list(IndexLocations.getIndexConstructionArea(storageService)).forEach(path -> {
                    try {
                        if (Files.isDirectory(path)) {
                            FileUtils.deleteDirectory(path.toFile());
                        }
                        else if (Files.isRegularFile(path)) {
                            Files.delete(path);
                        }
                    } catch (Exception e) {
                        logger.error("Error clearing staging area", e);
                    }
                });


                long id = mqLoaderOutbox.sendAsync(new LoadRequest(processedIds));

                yield new Load(processedIds, id);
            }
            case Load(List<FileStorageId> processedIds, long msgId) -> {
                var rsp = processWatcher.waitResponse(mqLoaderOutbox, ProcessService.ProcessId.LOADER, msgId);

                if (rsp.state() != MqMessageState.OK) {
                    yield new Error("Loader failed");
                }

                // If we're auto-cleaning, flag the processed files for deletion if they have the NEW flag,
                // indicating they've recently been created.  We need to check this, so we don't delete archived
                // stuff that's being loaded manually

                if (shouldAutoClean()) {
                    for (var id : processedIds) {
                        if (FileStorageState.NEW.equals(storageService.getStorage(id).state())) {
                            storageService.flagFileForDeletion(id);
                        }
                    }
                }

                yield new Backup(processedIds);
            }
            case Backup(List<FileStorageId> processedIds) -> {
                backupService.createBackupFromStaging(processedIds);
                yield new Rerank();
            }
            case Rerank(long id) when id < 0 ->
                    new Rerank(indexOutbox.sendAsync(IndexMqEndpoints.INDEX_RERANK, ""));
            case Rerank(long id) -> {
                var rsp = indexOutbox.waitResponse(id);
                if (rsp.state() != MqMessageState.OK) {
                    yield new Error("Repartition failed");
                }

                yield new ReindexFwd();
            }
            case ReindexFwd(long id) when id < 0 -> new ReindexFwd(createIndex(IndexName.FORWARD));
            case ReindexFwd(long id) -> {
                var rsp = processWatcher.waitResponse(mqIndexConstructorOutbox, ProcessService.ProcessId.INDEX_CONSTRUCTOR, id);

                if (rsp.state() != MqMessageState.OK)
                    yield new Error("Forward index construction failed");
                else
                    yield new ReindexFull();
            }
            case ReindexFull(long id) when id < 0 -> new ReindexFull(createIndex(IndexName.REVERSE_FULL));
            case ReindexFull(long id) -> {
                var rsp = processWatcher.waitResponse(mqIndexConstructorOutbox, ProcessService.ProcessId.INDEX_CONSTRUCTOR, id);

                if (rsp.state() != MqMessageState.OK)
                    yield new Error("Full index construction failed");
                else
                    yield new ReindexPrio();
            }
            case ReindexPrio(long id) when id < 0 -> new ReindexPrio(createIndex(IndexName.REVERSE_PRIO));
            case ReindexPrio(long id) -> {
                var rsp = processWatcher.waitResponse(mqIndexConstructorOutbox, ProcessService.ProcessId.INDEX_CONSTRUCTOR, id);

                if (rsp.state() != MqMessageState.OK)
                    yield new Error("Prio index construction failed");
                else
                    yield new SwitchIndex();
            }

            case SwitchIndex() -> {
                indexOutbox.sendNotice(IndexMqEndpoints.SWITCH_INDEX, "here");
                indexOutbox.sendNotice(IndexMqEndpoints.SWITCH_LINKDB, "we");

                // Defer repartitioning the domains until after the index has been switched
                indexOutbox.sendNotice(IndexMqEndpoints.INDEX_REPARTITION, "go");
                yield new End();
            }

            default -> new Error();
        };
    }

    private long createIndex(IndexName index) throws Exception {
        return mqIndexConstructorOutbox.sendAsync(new CreateIndexRequest(index));
    }

    private boolean shouldAutoClean() {
        try {
            return nodeConfigurationService.get(nodeId).autoClean();
        }
        catch (SQLException ex) {
            logger.error("Error getting node configuration", ex);
            return false; // safe dafault
        }
    }


    @Override
    public String describe() {
        return "Process a set of crawl data and then load it into the database.";
    }

    @Inject
    public ConvertAndLoadActor(ActorProcessWatcher processWatcher,
                               ProcessOutboxes processOutboxes,
                               FileStorageService storageService,
                               IndexMqClient indexMqClient,
                               BackupService backupService,
                               Gson gson,
                               NodeConfigurationService nodeConfigurationService,
                               ServiceConfiguration serviceConfiguration)
    {
        super(gson);
        this.processWatcher = processWatcher;
        this.indexOutbox = indexMqClient.outbox();
        this.mqConverterOutbox = processOutboxes.getConverterOutbox();
        this.mqLoaderOutbox = processOutboxes.getLoaderOutbox();
        this.mqIndexConstructorOutbox = processOutboxes.getIndexConstructorOutbox();
        this.storageService = storageService;
        this.backupService = backupService;
        this.nodeConfigurationService = nodeConfigurationService;

        this.nodeId = serviceConfiguration.node();
    }


}
