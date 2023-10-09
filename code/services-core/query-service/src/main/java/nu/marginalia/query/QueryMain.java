package nu.marginalia.query;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import nu.marginalia.service.MainClass;
import nu.marginalia.service.SearchServiceDescriptors;
import nu.marginalia.service.id.ServiceId;
import nu.marginalia.service.module.ConfigurationModule;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.service.server.Initialization;

public class QueryMain extends MainClass {
    private final QueryService service;

    @Inject
    public QueryMain(QueryService service) {
        this.service = service;
    }

    public static void main(String... args) {
        init(ServiceId.Query, args);

        Injector injector = Guice.createInjector(
                new QueryModule(),
                new DatabaseModule(),
                new ConfigurationModule(SearchServiceDescriptors.descriptors, ServiceId.Query)
        );

        injector.getInstance(QueryMain.class);
        injector.getInstance(Initialization.class).setReady();
    }

}
