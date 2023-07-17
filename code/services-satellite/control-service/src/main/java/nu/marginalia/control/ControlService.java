package nu.marginalia.control;

import com.google.gson.Gson;
import com.google.inject.Inject;
import nu.marginalia.client.ServiceMonitors;
import nu.marginalia.control.svc.*;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.renderer.MustacheRenderer;
import nu.marginalia.renderer.RendererFactory;
import nu.marginalia.service.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.io.IOException;
import java.util.Map;

public class ControlService extends Service {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = GsonFactory.get();

    private final ServiceMonitors monitors;
    private final HeartbeatService heartbeatService;
    private final EventLogService eventLogService;
    private final ControlFsmService controlFsmService;
    private final StaticResources staticResources;
    private final MessageQueueViewService messageQueueViewService;
    private final ControlFileStorageService controlFileStorageService;


    @Inject
    public ControlService(BaseServiceParams params,
                          ServiceMonitors monitors,
                          HeartbeatService heartbeatService,
                          EventLogService eventLogService,
                          RendererFactory rendererFactory,
                          ControlFsmService controlFsmService,
                          StaticResources staticResources,
                          MessageQueueViewService messageQueueViewService,
                          ControlFileStorageService controlFileStorageService
                      ) throws IOException {

        super(params);
        this.monitors = monitors;
        this.heartbeatService = heartbeatService;
        this.eventLogService = eventLogService;

        var indexRenderer = rendererFactory.renderer("control/index");
        var servicesRenderer = rendererFactory.renderer("control/services");
        var serviceByIdRenderer = rendererFactory.renderer("control/service-by-id");
        var processesRenderer = rendererFactory.renderer("control/processes");
        var storageRenderer = rendererFactory.renderer("control/storage");

        this.controlFsmService = controlFsmService;

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
        Spark.get("/public/processes", this::processesModel, processesRenderer::render);
        Spark.get("/public/storage", this::storageModel, storageRenderer::render);

        final HtmlRedirect redirectToServices = new HtmlRedirect("/services");
        final HtmlRedirect redirectToProcesses = new HtmlRedirect("/processes");
        final HtmlRedirect redirectToStorage = new HtmlRedirect("/storage");

        Spark.post("/public/fsms/:fsm/start", controlFsmService::startFsm, redirectToProcesses);
        Spark.post("/public/fsms/:fsm/stop", controlFsmService::stopFsm, redirectToProcesses);

        Spark.post("/public/storage/:fid/process", controlFsmService::triggerProcessing, redirectToProcesses);
        Spark.post("/public/storage/:fid/load", controlFsmService::loadProcessedData, redirectToProcesses);

        Spark.post("/public/storage/:fid/delete", controlFileStorageService::flagFileForDeletionRequest, redirectToStorage);

        Spark.get("/public/:resource", this::serveStatic);

        monitors.subscribe(this::logMonitorStateChange);
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

    private Object servicesModel(Request request, Response response) {
        return Map.of("services", heartbeatService.getServiceHeartbeats(),
                      "events", eventLogService.getLastEntries(20));
    }

    private Object processesModel(Request request, Response response) {
        return Map.of("processes", heartbeatService.getProcessHeartbeats(),
                      "fsms", controlFsmService.getFsmStates(),
                      "messages", messageQueueViewService.getLastEntries(20));
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
