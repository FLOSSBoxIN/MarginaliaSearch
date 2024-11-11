package nu.marginalia.crawl;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import nu.marginalia.UserAgent;
import nu.marginalia.WmsaHome;
import nu.marginalia.model.gson.GsonFactory;

public class CrawlerModule extends AbstractModule {

    public void configure() {
        bind(Gson.class).toInstance(createGson());
        bind(UserAgent.class).toInstance(WmsaHome.getUserAgent());
    }

    private Gson createGson() {
        return GsonFactory.get();
    }
}
