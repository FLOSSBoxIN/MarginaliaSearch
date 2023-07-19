package nu.marginalia.control.actor.monitor;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.control.svc.ProcessService;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.mqsm.StateFactory;
import nu.marginalia.mqsm.graph.AbstractStateGraph;
import nu.marginalia.mqsm.graph.GraphState;
import nu.marginalia.mqsm.graph.ResumeBehavior;
import nu.marginalia.mqsm.graph.TerminalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class AbstractProcessSpawnerActor extends AbstractStateGraph {

    private final MqPersistence persistence;
    private final ProcessService processService;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public static final String INITIAL = "INITIAL";
    public static final String MONITOR = "MONITOR";
    public static final String RUN = "RUN";
    public static final String ERROR = "ERROR";
    public static final String ABORTED = "ABORTED";
    public static final String END = "END";

    public static final int MAX_ATTEMPTS = 3;
    private final String inboxName;
    private final ProcessService.ProcessId processId;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Inject
    public AbstractProcessSpawnerActor(StateFactory stateFactory,
                                       MqPersistence persistence,
                                       ProcessService processService,
                                       String inboxName,
                                       ProcessService.ProcessId processId) {
        super(stateFactory);
        this.persistence = persistence;
        this.processService = processService;
        this.inboxName = inboxName;
        this.processId = processId;
    }

    @GraphState(name = INITIAL, next = MONITOR)
    public void init() {

    }

    @GraphState(name = MONITOR,
                next = MONITOR,
                resume = ResumeBehavior.RETRY,
                transitions = {MONITOR, RUN},
                description = """
                        Monitors the inbox of the process for messages.
                        If a message is found, transition to RUN.
                        """
    )
    public void monitor() throws SQLException, InterruptedException {

        for (;;) {
            var messages = persistence.eavesdrop(inboxName, 1);

            if (messages.isEmpty() && !processService.isRunning(processId)) {
                TimeUnit.SECONDS.sleep(5);
            } else {
                transition(RUN);
            }
        }
    }

    @GraphState(name = RUN,
                resume = ResumeBehavior.RESTART,
                transitions = {MONITOR, ERROR, RUN, ABORTED},
                description = """
                        Runs the process.
                        If the process fails, retransition to RUN up to MAX_ATTEMPTS times.
                        After MAX_ATTEMPTS at restarting the process, transition to ERROR.
                        If the process is cancelled, transition to ABORTED.
                        If the process is successful, transition to MONITOR.
                        """
    )
    public void run(Integer attempts) throws Exception {
        if (attempts == null)
            attempts = 0;

        try {
            var exec = new TaskExecution();
            if (exec.isError()) {
                if (attempts < MAX_ATTEMPTS)
                    transition(RUN, attempts + 1);
                else
                    transition(ERROR);
            }
        }
        catch (InterruptedException ex) {
            processService.kill(processId);
            transition(ABORTED);
        }

        transition(MONITOR);
    }

    @TerminalState(name = ABORTED, description = "The process was manually aborted")
    public void aborted() throws Exception {}


    private class TaskExecution {
        private final AtomicBoolean error = new AtomicBoolean(false);
        public TaskExecution() throws ExecutionException, InterruptedException {
            // Run this call in a separate thread so that this thread can be interrupted waiting for it
            executorService.submit(() -> {
                try {
                    processService.trigger(processId);
                } catch (Exception e) {
                    logger.warn("Error in triggering process", e);
                    error.set(true);
                }
            }).get(); // Wait for the process to start
        }

        public boolean isError() {
            return error.get();
        }
    }
}
