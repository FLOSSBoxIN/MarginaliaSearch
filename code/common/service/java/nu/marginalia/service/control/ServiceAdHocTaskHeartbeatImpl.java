package nu.marginalia.service.control;


import com.zaxxer.hikari.HikariDataSource;
import nu.marginalia.service.module.ServiceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** This object sends a heartbeat to the database every few seconds,
 * updating with the progress of a task within a service. Progress is tracked by providing
 * enumerations corresponding to the steps in the task.  It's important they're arranged in the same
 * order as the steps in the task in order to get an accurate progress tracking.
 */
public class ServiceAdHocTaskHeartbeatImpl implements AutoCloseable, ServiceAdHocTaskHeartbeat {
    private final Logger logger = LoggerFactory.getLogger(ServiceAdHocTaskHeartbeatImpl.class);
    private final String taskName;
    private final String taskBase;
    private final int node;
    private final String instanceUUID;
    private final HikariDataSource dataSource;


    private final Thread runnerThread;
    private final int heartbeatInterval = Integer.getInteger("mcp.heartbeat.interval", 1);
    private final String serviceInstanceUUID;
    private int progress;

    private volatile boolean running = false;
    private volatile String step = "-";

    ServiceAdHocTaskHeartbeatImpl(ServiceConfiguration configuration,
                                  String taskName,
                                  HikariDataSource dataSource)
    {
        this.taskName = configuration.serviceName() + "." + taskName + ":" + configuration.node();
        this.taskBase = configuration.serviceName() + "." + taskName;
        this.node = configuration.node();
        this.dataSource = dataSource;

        this.instanceUUID = UUID.randomUUID().toString();
        this.serviceInstanceUUID = configuration.instanceUuid().toString();

        heartbeatInit();

        runnerThread = new Thread(this::run);
        runnerThread.start();
    }

    /** Update the progress of the task.  This is a fast function that doesn't block;
     * the actual update is done in a separate thread.
     *
     * @param step The current step in the task.
     */
    @Override
    public void progress(String step, int stepProgress, int stepCount) {
        int lastProgress = this.progress;
        this.step = step;
        this.progress = (int) Math.round(100. * stepProgress / (double) stepCount);

        if (this.progress / 10 != lastProgress / 10) {
            logger.info("ProcessTask {} progress: {}%", taskBase, progress);
        }
    }

    public void shutDown() {
        if (!running)
            return;

        running = false;

        try {
            runnerThread.join();
            heartbeatStop();
        }
        catch (InterruptedException|SQLException ex) {
            logger.warn("ServiceHeartbeat shutdown failed", ex);
        }
    }

    private void run() {
        if (!running)
            running = true;
        else
            return;

        try {
            while (running) {
                try {
                    heartbeatUpdate();
                }
                catch (SQLException ex) {
                    logger.warn("ServiceHeartbeat failed to update", ex);
                }

                TimeUnit.SECONDS.sleep(heartbeatInterval);
            }
        }
        catch (InterruptedException ex) {
            logger.error("ServiceHeartbeat caught irrecoverable exception, killing service", ex);
            System.exit(255);
        }
    }

    private void heartbeatInit() {
        try (var connection = dataSource.getConnection()) {
            try (var stmt = connection.prepareStatement(
                    """
                        INSERT INTO TASK_HEARTBEAT (TASK_NAME, TASK_BASE, NODE, INSTANCE, SERVICE_INSTANCE, HEARTBEAT_TIME, STATUS)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), 'STARTING')
                        ON DUPLICATE KEY UPDATE
                            INSTANCE = ?,
                            SERVICE_INSTANCE = ?,
                            HEARTBEAT_TIME = CURRENT_TIMESTAMP(6),
                            STATUS = 'STARTING'
                        """
            ))
            {
                stmt.setString(1, taskName);
                stmt.setString(2, taskBase);
                stmt.setInt(3, node);
                stmt.setString(4, instanceUUID);
                stmt.setString(5, serviceInstanceUUID);
                stmt.setString(6, instanceUUID);
                stmt.setString(7, serviceInstanceUUID);
                stmt.executeUpdate();
            }
        }
        catch (SQLException ex) {
            logger.error("ServiceHeartbeat failed to initialize", ex);
            throw new RuntimeException(ex);
        }

    }

    private void heartbeatUpdate() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            try (var stmt = connection.prepareStatement(
                    """
                        UPDATE TASK_HEARTBEAT
                        SET HEARTBEAT_TIME = CURRENT_TIMESTAMP(6),
                            STATUS = 'RUNNING',
                            PROGRESS = ?,
                            STAGE_NAME = ?
                        WHERE INSTANCE = ?
                        """)
            )
            {
                stmt.setInt(1, progress);
                stmt.setString(2, step);
                stmt.setString(3, instanceUUID);
                stmt.executeUpdate();
            }
        }
    }

    private void heartbeatStop() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            try (var stmt = connection.prepareStatement(
                    """
                        UPDATE TASK_HEARTBEAT
                        SET HEARTBEAT_TIME = CURRENT_TIMESTAMP(6),
                            STATUS='STOPPED',
                            PROGRESS = ?,
                            STAGE_NAME = ?
                        WHERE INSTANCE = ?
                        """)
            )
            {
                stmt.setInt(1, progress);
                stmt.setString( 2, step);
                stmt.setString( 3, instanceUUID);
                stmt.executeUpdate();
            }
        }
    }

    @Override
    public void close() {
        shutDown();
    }

}

