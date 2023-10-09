package nu.marginalia.api;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import nu.marginalia.service.MainClass;
import nu.marginalia.service.SearchServiceDescriptors;
import nu.marginalia.service.id.ServiceId;
import nu.marginalia.service.module.ConfigurationModule;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.service.server.Initialization;

public class ApiMain extends MainClass {

    @Inject
    public ApiMain(ApiService service) {
    }

    public static void main(String... args) {
        init(ServiceId.Api, args);

        Injector injector = Guice.createInjector(
                new DatabaseModule(),
                new ConfigurationModule(SearchServiceDescriptors.descriptors, ServiceId.Api));
        injector.getInstance(ApiMain.class);
        injector.getInstance(Initialization.class).setReady();
    }
}
