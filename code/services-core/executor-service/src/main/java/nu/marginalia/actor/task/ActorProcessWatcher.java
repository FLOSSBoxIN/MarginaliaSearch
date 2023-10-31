package nu.marginalia.actor.task;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.actor.state.ActorControlFlowException;
import nu.marginalia.process.ProcessService;
import nu.marginalia.mq.MqMessage;
import nu.marginalia.mq.outbox.MqOutbox;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Singleton
public class ActorProcessWatcher {

    private final ProcessService processService;

    @Inject
    public ActorProcessWatcher(ProcessService processService) {
        this.processService = processService;
    }

    /** Wait for a process to start, and then wait for a response from the process,
     * periodically checking that the process is still running.  If the process dies,
     * and does not respawn, or does not start at all, a control flow exception is thrown
     * that will cause the actor to transition to ERROR.
     * <p>
     * When interrupted, the process is killed and the message is marked as dead.
     */
    public MqMessage waitResponse(MqOutbox outbox, ProcessService.ProcessId processId, long msgId)
            throws ActorControlFlowException, InterruptedException, SQLException
    {
        synchronized (processId) {
            // Wake up the process spawning actor
            processId.notifyAll();
        }

        if (!waitForProcess(processId, TimeUnit.SECONDS, 30)) {
            throw new ActorControlFlowException("Process " + processId + " did not launch");
        }

        for (;;) {
            try {
                return outbox.waitResponse(msgId, 5, TimeUnit.SECONDS);
            }
            catch (InterruptedException ex) {
                // Here we mark the message as dead, as it's the user that has aborted the process
                // This will prevent the monitor process from attempting to respawn the process as we kill it

                outbox.flagAsDead(msgId);
                processService.kill(processId);

                throw ex;
            }
            catch (TimeoutException ex) {
                // Maybe the process died, wait a moment for it to restart
                if (!waitForProcess(processId, TimeUnit.SECONDS, 30)) {
                    throw new ActorControlFlowException("Process " + processId + " died and did not re-launch");
                }
            }
        }
    }

    /** Wait the specified time for the specified process to start running (does not start the process) */
    private boolean waitForProcess(ProcessService.ProcessId processId, TimeUnit unit, int duration) throws InterruptedException {

        // Wait for process to start
        long deadline = System.currentTimeMillis() + unit.toMillis(duration);
        while (System.currentTimeMillis() < deadline) {
            if (processService.isRunning(processId))
                return true;

            TimeUnit.MILLISECONDS.sleep(100);
        }

        return false;
    }

}
