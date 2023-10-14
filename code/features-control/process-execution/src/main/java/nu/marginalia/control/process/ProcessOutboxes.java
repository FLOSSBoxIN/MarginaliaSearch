package nu.marginalia.control.process;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.mq.outbox.MqOutbox;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.mqapi.ProcessInboxNames;
import nu.marginalia.service.server.BaseServiceParams;

@Singleton
public class ProcessOutboxes {
    private final MqOutbox converterOutbox;
    private final MqOutbox loaderOutbox;
    private final MqOutbox crawlerOutbox;
    private final MqOutbox indexConstructorOutbox;

    @Inject
    public ProcessOutboxes(BaseServiceParams params, MqPersistence persistence) {
        converterOutbox = new MqOutbox(persistence,
                ProcessInboxNames.CONVERTER_INBOX,
                params.configuration.node(),
                params.configuration.serviceName(),
                params.configuration.node(),
                params.configuration.instanceUuid()
        );
        loaderOutbox = new MqOutbox(persistence,
                ProcessInboxNames.LOADER_INBOX,
                params.configuration.node(),
                params.configuration.serviceName(),
                params.configuration.node(),
                params.configuration.instanceUuid()
        );
        crawlerOutbox = new MqOutbox(persistence,
                ProcessInboxNames.CRAWLER_INBOX,
                params.configuration.node(),
                params.configuration.serviceName(),
                params.configuration.node(),
                params.configuration.instanceUuid()
        );
        indexConstructorOutbox = new MqOutbox(persistence,
                ProcessInboxNames.INDEX_CONSTRUCTOR_INBOX,
                params.configuration.node(),
                params.configuration.serviceName(),
                params.configuration.node(),
                params.configuration.instanceUuid()
        );
    }


    public MqOutbox getConverterOutbox() {
        return converterOutbox;
    }

    public MqOutbox getLoaderOutbox() {
        return loaderOutbox;
    }

    public MqOutbox getCrawlerOutbox() {
        return crawlerOutbox;
    }

    public MqOutbox getIndexConstructorOutbox() { return indexConstructorOutbox; }
}
