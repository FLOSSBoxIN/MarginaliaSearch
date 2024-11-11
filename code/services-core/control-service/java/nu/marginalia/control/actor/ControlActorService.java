package nu.marginalia.control.actor;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.actor.ActorStateMachine;
import nu.marginalia.actor.prototype.ActorPrototype;
import nu.marginalia.actor.state.ActorStateInstance;
import nu.marginalia.control.actor.monitor.MessageQueueMonitorActor;
import nu.marginalia.control.actor.monitor.ServiceHeartbeatMonitorActor;
import nu.marginalia.control.actor.precession.RecrawlAllActor;
import nu.marginalia.control.actor.precession.ReindexAllActor;
import nu.marginalia.control.actor.precession.ReprocessAllActor;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.service.control.ServiceEventLog;
import nu.marginalia.service.server.BaseServiceParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;


@Singleton
public class ControlActorService {
    private static final Logger logger = LoggerFactory.getLogger(ControlActorService.class);

    private final ServiceEventLog eventLog;
    private final Gson gson;
    private final MessageQueueFactory messageQueueFactory;
    public Map<ControlActor, ActorStateMachine> stateMachines = new HashMap<>();
    public Map<ControlActor, ActorPrototype> actorDefinitions = new HashMap<>();
    private final int node;
    @Inject
    public ControlActorService(MessageQueueFactory messageQueueFactory,
                               BaseServiceParams baseServiceParams,
                               MessageQueueMonitorActor messageQueueMonitor,
                               ServiceHeartbeatMonitorActor heartbeatMonitorActor,
                               ReindexAllActor reindexAllActor,
                               ReprocessAllActor reprocessAllActor,
                               RecrawlAllActor recrawlAllActor
    ) {
        this.messageQueueFactory = messageQueueFactory;
        this.eventLog = baseServiceParams.eventLog;
        this.gson = GsonFactory.get();
        this.node = baseServiceParams.configuration.node();


        register(ControlActor.MONITOR_MESSAGE_QUEUE, messageQueueMonitor);
        register(ControlActor.MONITOR_HEARTBEATS, heartbeatMonitorActor);
        register(ControlActor.REINDEX_ALL, reindexAllActor);
        register(ControlActor.REPROCESS_ALL, reprocessAllActor);
        register(ControlActor.RECRAWL_ALL, recrawlAllActor);
    }

    private void register(ControlActor process, ActorPrototype graph) {
        var sm = new ActorStateMachine(messageQueueFactory, process.id(), node, UUID.randomUUID(), graph);
        sm.listen((function, param) -> logStateChange(process, function));

        stateMachines.put(process, sm);
        actorDefinitions.put(process, graph);
    }

    private void logStateChange(ControlActor process, String state) {
        if ("ERROR".equals(state)) {
            eventLog.logEvent("FSM-ERROR", process.id());
        }
    }

    public void startFrom(ControlActor process, String state) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state);
    }

    public void start(ControlActor process) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init();
    }

    public <T> void startFrom(ControlActor process, String state, Object arg) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state, gson.toJson(arg));
    }

    public <T> void startFromJSON(ControlActor process, String state, String json) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).initFrom(state, json);
    }

    public <T> void start(ControlActor process, Object arg) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init(gson.toJson(arg));
    }
    public <T> void startJSON(ControlActor process, String json) throws Exception {
        eventLog.logEvent("FSM-START", process.id());

        stateMachines.get(process).init(json);
    }

    public void stop(ControlActor process) {
        eventLog.logEvent("FSM-STOP", process.id());

        stateMachines.get(process).abortExecution();
    }

    public Map<ControlActor, ActorStateInstance> getActorStates() {
        return stateMachines.entrySet().stream().collect(
                Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().getState())
        );
    }

    public boolean isDirectlyInitializable(ControlActor actor) {
        return actorDefinitions.get(actor).isDirectlyInitializable();
    }

    public ActorPrototype getActorDefinition(ControlActor actor) {
        return actorDefinitions.get(actor);
    }

    public void startDefaultActors() {
        try {
            if (!stateMachines.get(ControlActor.MONITOR_HEARTBEATS).isRunning()) {
                start(ControlActor.MONITOR_HEARTBEATS);
            }
            if (!stateMachines.get(ControlActor.MONITOR_MESSAGE_QUEUE).isRunning()) {
                start(ControlActor.MONITOR_MESSAGE_QUEUE);
            }
        }
        catch (Exception ex) {
            logger.error("Failed to start default actors", ex);
        }
    }
}