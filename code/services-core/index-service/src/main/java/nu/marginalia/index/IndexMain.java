package nu.marginalia.index;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import nu.marginalia.service.MainClass;
import nu.marginalia.service.SearchServiceDescriptors;
import nu.marginalia.service.id.ServiceId;
import nu.marginalia.service.module.ServiceConfigurationModule;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.service.server.Initialization;
import nu.marginalia.service.server.NodeStatusWatcher;

public class IndexMain extends MainClass {
    private final IndexService service;

    @Inject
    public IndexMain(IndexService service) {
        this.service = service;
    }

    public static void main(String... args) {
        init(ServiceId.Index, args);

        Injector injector = Guice.createInjector(
                new IndexModule(),
                new DatabaseModule(false),
                new ServiceConfigurationModule(SearchServiceDescriptors.descriptors, ServiceId.Index)
        );

        injector.getInstance(NodeStatusWatcher.class);

        injector.getInstance(IndexMain.class);
        injector.getInstance(Initialization.class).setReady();

    }
}
