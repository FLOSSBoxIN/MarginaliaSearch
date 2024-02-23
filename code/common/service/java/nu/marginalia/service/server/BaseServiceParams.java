package nu.marginalia.service.server;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.mq.MessageQueueFactory;
import nu.marginalia.service.control.ServiceEventLog;
import nu.marginalia.service.control.ServiceHeartbeatImpl;
import nu.marginalia.service.discovery.ServiceRegistryIf;
import nu.marginalia.service.module.ServiceConfiguration;

/** This class exists to reduce Service boilerplate */
@Singleton
public class BaseServiceParams {
    public final ServiceConfiguration configuration;
    public final Initialization initialization;
    public final MetricsServer metricsServer;
    public final ServiceHeartbeatImpl heartbeat;
    public final ServiceEventLog eventLog;
    public final ServiceRegistryIf serviceRegistry;
    public final MessageQueueFactory messageQueueInboxFactory;

    @Inject
    public BaseServiceParams(ServiceConfiguration configuration,
                             Initialization initialization,
                             MetricsServer metricsServer,
                             ServiceHeartbeatImpl heartbeat,
                             ServiceEventLog eventLog,
                             ServiceRegistryIf serviceRegistry,
                             MessageQueueFactory messageQueueInboxFactory) {
        this.configuration = configuration;
        this.initialization = initialization;
        this.metricsServer = metricsServer;
        this.heartbeat = heartbeat;
        this.eventLog = eventLog;
        this.serviceRegistry = serviceRegistry;
        this.messageQueueInboxFactory = messageQueueInboxFactory;
    }
}
