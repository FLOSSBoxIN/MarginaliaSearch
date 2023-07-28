package nu.marginalia.control;

import com.google.gson.Gson;
import com.google.inject.Inject;
import nu.marginalia.client.ServiceMonitors;
import nu.marginalia.control.model.Actor;
import nu.marginalia.control.svc.*;
import nu.marginalia.db.storage.model.FileStorageId;
import nu.marginalia.db.storage.model.FileStorageType;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.mq.MqMessageState;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.renderer.RendererFactory;
import nu.marginalia.service.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

public class ControlService extends Service {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = GsonFactory.get();

    private final ServiceMonitors monitors;
    private final HeartbeatService heartbeatService;
    private final EventLogService eventLogService;
    private final ControlActorService controlActorService;
    private final StaticResources staticResources;
    private final MessageQueueViewService messageQueueViewService;
    private final ControlFileStorageService controlFileStorageService;


    @Inject
    public ControlService(BaseServiceParams params,
                          ServiceMonitors monitors,
                          HeartbeatService heartbeatService,
                          EventLogService eventLogService,
                          RendererFactory rendererFactory,
                          ControlActorService controlActorService,
                          StaticResources staticResources,
                          MessageQueueViewService messageQueueViewService,
                          ControlFileStorageService controlFileStorageService,
                          MqPersistence persistence
                      ) throws IOException {

        super(params);
        this.monitors = monitors;
        this.heartbeatService = heartbeatService;
        this.eventLogService = eventLogService;

        var indexRenderer = rendererFactory.renderer("control/index");
        var servicesRenderer = rendererFactory.renderer("control/services");
        var serviceByIdRenderer = rendererFactory.renderer("control/service-by-id");
        var actorsRenderer = rendererFactory.renderer("control/actors");
        var actorDetailsRenderer = rendererFactory.renderer("control/actor-details");
        var storageRenderer = rendererFactory.renderer("control/storage-overview");
        var storageSpecsRenderer = rendererFactory.renderer("control/storage-specs");
        var storageCrawlsRenderer = rendererFactory.renderer("control/storage-crawls");
        var storageProcessedRenderer = rendererFactory.renderer("control/storage-processed");

        var storageDetailsRenderer = rendererFactory.renderer("control/storage-details");
        var updateMessageStateRenderer = rendererFactory.renderer("control/dialog-update-message-state");

        this.controlActorService = controlActorService;

        this.staticResources = staticResources;
        this.messageQueueViewService = messageQueueViewService;
        this.controlFileStorageService = controlFileStorageService;

        Spark.get("/public/heartbeats", (req, res) -> {
            res.type("application/json");
            return heartbeatService.getServiceHeartbeats();
        }, gson::toJson);

        Spark.get("/public/", (req, rsp) -> indexRenderer.render(Map.of()));

        Spark.get("/public/services", this::servicesModel, servicesRenderer::render);
        Spark.get("/public/services/:id", this::serviceModel, serviceByIdRenderer::render);
        Spark.get("/public/messages/:id", this::messageModel, gson::toJson);
        Spark.get("/public/actors", this::processesModel, actorsRenderer::render);
        Spark.get("/public/actors/:fsm", this::actorDetailsModel, actorDetailsRenderer::render);
        Spark.get("/public/storage", this::storageModel, storageRenderer::render);
        Spark.get("/public/storage/specs", this::storageModelSpecs, storageSpecsRenderer::render);
        Spark.get("/public/storage/crawls", this::storageModelCrawls, storageCrawlsRenderer::render);
        Spark.get("/public/storage/processed", this::storageModelProcessed, storageProcessedRenderer::render);
        Spark.get("/public/storage/:id", this::storageDetailsModel, storageDetailsRenderer::render);
        Spark.get("/public/storage/:id/file", controlFileStorageService::downloadFileFromStorage);


        final HtmlRedirect redirectToServices = new HtmlRedirect("/services");
        final HtmlRedirect redirectToProcesses = new HtmlRedirect("/actors");
        final HtmlRedirect redirectToStorage = new HtmlRedirect("/storage");

        Spark.post("/public/fsms/:fsm/start", controlActorService::startFsm, redirectToProcesses);
        Spark.post("/public/fsms/:fsm/stop", controlActorService::stopFsm, redirectToProcesses);
        Spark.post("/public/storage/:fid/crawl", controlActorService::triggerCrawling, redirectToProcesses);
        Spark.post("/public/storage/:fid/recrawl", controlActorService::triggerRecrawling, redirectToProcesses);
        Spark.post("/public/storage/:fid/process", controlActorService::triggerProcessing, redirectToProcesses);
        Spark.post("/public/storage/:fid/load", controlActorService::loadProcessedData, redirectToProcesses);

        Spark.post("/public/storage/specs", controlActorService::createCrawlSpecification, redirectToStorage);
        Spark.post("/public/storage/:fid/delete", controlFileStorageService::flagFileForDeletionRequest, redirectToStorage);

        Spark.get("/public/message/:id/state", (rq, rsp) -> persistence.getMessage(Long.parseLong(rq.params("id"))), updateMessageStateRenderer::render);
        Spark.post("/public/message/:id/state", (rq, rsp) -> {
            MqMessageState state = MqMessageState.valueOf(rq.queryParams("state"));
            long id = Long.parseLong(rq.params("id"));
            persistence.updateMessageState(id, state);
            return "";
        }, redirectToProcesses);

        Spark.get("/public/:resource", this::serveStatic);

        monitors.subscribe(this::logMonitorStateChange);
    }

    private Object messageModel(Request request, Response response) {
        var message = messageQueueViewService.getMessage(Long.parseLong(request.params("id")));
        if (message != null) {
            response.type("application/json");
            return message;
        }
        else {
            response.status(404);
            return "";
        }
    }

    private Object serviceModel(Request request, Response response) {
        String serviceName = request.params("id");

        return Map.of(
                "id", serviceName,
                "events", eventLogService.getLastEntriesForService(serviceName, 20));
    }

    private Object storageModel(Request request, Response response) {
        return Map.of("storage", controlFileStorageService.getStorageList());
    }

    private Object storageDetailsModel(Request request, Response response) throws SQLException {
        return Map.of("storage", controlFileStorageService.getFileStorageWithRelatedEntries(FileStorageId.parse(request.params("id"))));
    }
    private Object storageModelSpecs(Request request, Response response) {
        return Map.of("storage", controlFileStorageService.getStorageList(FileStorageType.CRAWL_SPEC));
    }
    private Object storageModelCrawls(Request request, Response response) {
        return Map.of("storage", controlFileStorageService.getStorageList(FileStorageType.CRAWL_DATA));
    }
    private Object storageModelProcessed(Request request, Response response) {
        return Map.of("storage", controlFileStorageService.getStorageList(FileStorageType.PROCESSED_DATA));
    }
    private Object servicesModel(Request request, Response response) {
        return Map.of("services", heartbeatService.getServiceHeartbeats(),
                      "events", eventLogService.getLastEntries(20));
    }

    private Object processesModel(Request request, Response response) {
        return Map.of("processes", heartbeatService.getProcessHeartbeats(),
                      "actors", controlActorService.getActorStates(),
                      "messages", messageQueueViewService.getLastEntries(20));
    }
    private Object actorDetailsModel(Request request, Response response) {
        final Actor actor = Actor.valueOf(request.params("fsm").toUpperCase());
        final String inbox = actor.id();

        return Map.of(
                "actor", actor,
                "state-graph", controlActorService.getActorStateGraph(actor),
                "messages", messageQueueViewService.getLastEntriesForInbox(inbox, 20));
    }
    private Object serveStatic(Request request, Response response) {
        String resource = request.params("resource");

        staticResources.serveStatic("control", resource, request, response);

        return "";
    }


    private void logMonitorStateChange() {
        logger.info("Service state change: {}", monitors.getRunningServices());
    }

}
