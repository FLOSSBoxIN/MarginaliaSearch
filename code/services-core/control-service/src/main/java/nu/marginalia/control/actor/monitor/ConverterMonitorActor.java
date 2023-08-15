package nu.marginalia.control.actor.monitor;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.actor.ActorStateFactory;
import nu.marginalia.control.process.ProcessService;
import nu.marginalia.mqapi.ProcessInboxNames;
import nu.marginalia.mq.persistence.MqPersistence;

@Singleton
public class ConverterMonitorActor extends AbstractProcessSpawnerActor {


    @Inject
    public ConverterMonitorActor(ActorStateFactory stateFactory,
                                 MqPersistence persistence,
                                 ProcessService processService) {
        super(stateFactory, persistence, processService, ProcessInboxNames.CONVERTER_INBOX, ProcessService.ProcessId.CONVERTER);
    }


}
