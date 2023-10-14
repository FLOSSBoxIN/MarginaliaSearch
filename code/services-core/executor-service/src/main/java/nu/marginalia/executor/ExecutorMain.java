package nu.marginalia.executor;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import nu.marginalia.service.MainClass;
import nu.marginalia.service.SearchServiceDescriptors;
import nu.marginalia.service.id.ServiceId;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.service.module.ServiceConfigurationModule;
import nu.marginalia.service.server.Initialization;

public class ExecutorMain extends MainClass  {
    private final ExecutorSvc service;

    @Inject
    public ExecutorMain(ExecutorSvc service) {
        this.service = service;
    }

    public static void main(String... args) {
        init(ServiceId.Executor, args);

        Injector injector = Guice.createInjector(
                new ExecutorModule(),
                new DatabaseModule(),
                new ServiceConfigurationModule(SearchServiceDescriptors.descriptors, ServiceId.Executor)
        );

        injector.getInstance(ExecutorMain.class);
        injector.getInstance(Initialization.class).setReady();
    }
}
