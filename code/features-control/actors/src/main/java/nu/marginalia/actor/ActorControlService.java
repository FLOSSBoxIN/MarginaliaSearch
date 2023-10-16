package nu.marginalia.actor;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.SneakyThrows;
import nu.marginalia.actor.monitor.*;
import nu.marginalia.actor.proc.*;
import nu.marginalia.actor.prototype.AbstractActorPrototype;
import nu.marginalia.actor.state.ActorStateInstance;
import nu.marginalia.actor.task.*;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.service.control.ServiceEventLog;
import nu.marginalia.service.server.BaseServiceParams;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** This class is responsible for starting and stopping the various actors in the responsible service */
@Singleton
public class ActorControlService {
    private final ServiceEventLog eventLog;
    private final Gson gson;
    private final MessageQueueFactory messageQueueFactory;
    public Map<Actor, ActorStateMachine> stateMachines = new HashMap<>();
    public Map<Actor, AbstractActorPrototype> actorDefinitions = new HashMap<>();
    private final int node;
    @Inject
    public ActorControlService(MessageQueueFactory messageQueueFactory,
                               BaseServiceParams baseServiceParams,
                               ConvertActor convertActor,
                               ConvertAndLoadActor convertAndLoadActor,
                               CrawlActor crawlActor,
                               RecrawlActor recrawlActor,
                               RestoreBackupActor restoreBackupActor,
                               ConverterMonitorActor converterMonitorFSM,
                               CrawlerMonitorActor crawlerMonitorActor,
                               LoaderMonitorActor loaderMonitor,
                               MessageQueueMonitorActor messageQueueMonitor,
                               ProcessLivenessMonitorActor processMonitorFSM,
                               FileStorageMonitorActor fileStorageMonitorActor,
                               IndexConstructorMonitorActor indexConstructorMonitorActor,
                               TriggerAdjacencyCalculationActor triggerAdjacencyCalculationActor,
                               CrawlJobExtractorActor crawlJobExtractorActor,
                               ExportDataActor exportDataActor,
                               TruncateLinkDatabase truncateLinkDatabase
                            ) {
        this.messageQueueFactory = messageQueueFactory;
        this.eventLog = baseServiceParams.eventLog;
        this.gson = GsonFactory.get();
        this.node = baseServiceParams.configuration.node();

        register(Actor.CRAWL, crawlActor);
        register(Actor.RECRAWL, recrawlActor);
        register(Actor.CONVERT, convertActor);
        register(Actor.RESTORE_BACKUP, restoreBackupActor);
        register(Actor.CONVERT_AND_LOAD, convertAndLoadActor);

        register(Actor.PROC_INDEX_CONSTRUCTOR_SPAWNER, indexConstructorMonitorActor);
        register(Actor.PROC_CONVERTER_SPAWNER, converterMonitorFSM);
        register(Actor.PROC_LOADER_SPAWNER, loaderMonitor);
        register(Actor.PROC_CRAWLER_SPAWNER, crawlerMonitorActor);

        register(Actor.MONITOR_MESSAGE_QUEUE, messageQueueMonitor);
        register(Actor.MONITOR_PROCESS_LIVENESS, processMonitorFSM);
        register(Actor.MONITOR_FILE_STORAGE, fileStorageMonitorActor);

        register(Actor.ADJACENCY_CALCULATION, triggerAdjacencyCalculationActor);
        register(Actor.CRAWL_JOB_EXTRACTOR, crawlJobExtractorActor);
        register(Actor.EXPORT_DATA, exportDataActor);
        register(Actor.TRUNCATE_LINK_DATABASE, truncateLinkDatabase);
    }

    private void register(Actor process, AbstractActorPrototype graph) {
        var sm = new ActorStateMachine(messageQueueFactory, process.id(), node, UUID.randomUUID(), graph);
        sm.listen((function, param) -> logStateChange(process, function));

        stateMachines.put(process, sm);
        actorDefinitions.put(process, graph);
    }

    private void logStateChange(Actor process, String state) {
        eventLog.logEvent("FSM-STATE-CHANGE", process.id() + " -> " + state);
    }

    public void startFrom(Actor process, String state) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state);
    }

    public void start(Actor process) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init();
    }

    public <T> void startFrom(Actor process, String state, Object arg) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state, gson.toJson(arg));
    }

    public <T> void startFromJSON(Actor process, String state, String json) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state, json);
    }

    public <T> void start(Actor process, Object arg) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init(gson.toJson(arg));
    }
    public <T> void startJSON(Actor process, String json) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init(json);
    }
    @SneakyThrows
    public void stop(Actor process) {
        eventLog.logEvent("FSM-STOP", process.id());

        stateMachines.get(process).abortExecution();
    }

    public Map<Actor, ActorStateInstance> getActorStates() {
        return stateMachines.entrySet().stream().collect(
                Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().getState())
        );
    }

    public boolean isDirectlyInitializable(Actor actor) {
        return actorDefinitions.get(actor).isDirectlyInitializable();
    }

    public AbstractActorPrototype getActorDefinition(Actor actor) {
        return actorDefinitions.get(actor);
    }

}
