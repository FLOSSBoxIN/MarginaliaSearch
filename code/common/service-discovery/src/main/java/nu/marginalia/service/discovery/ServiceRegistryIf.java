package nu.marginalia.service.discovery;

import nu.marginalia.service.discovery.monitor.*;
import nu.marginalia.service.discovery.property.ServiceEndpoint;
import static nu.marginalia.service.discovery.property.ServiceEndpoint.*;

import nu.marginalia.service.discovery.property.ServiceKey;

import java.util.Set;
import java.util.UUID;

/** A service registry that allows services to register themselves and
 * be discovered by other services on the network.
 */
public interface ServiceRegistryIf {
    /**
     * Register a service with the registry.
     * <p></p>
     * Once the instance has announced itself with {@link #announceInstance(UUID instanceUUID) announceInstance(...)},
     * the service will be available for discovery with {@link #getEndpoints(ServiceKey key) getEndpoints(...)}.
     *
     * @param key             the key identifying the service
     * @param instanceUUID    the unique UUID of the instance
     * @param externalAddress the public address of the service
     */
    ServiceEndpoint registerService(ServiceKey<?> key,
                                    UUID instanceUUID,
                                    String externalAddress) throws Exception;


    void declareFirstBoot();
    void waitForFirstBoot() throws InterruptedException;

    /** Let the world know that the service is running
     * and ready to accept requests. */
    void announceInstance(UUID instanceUUID);

    /** At the discretion of the implementation, provide a port that is unique
     * across (host, api-schema).  It may be randomly selected
     * or hard-coded or some combination of behaviors.
     */
    int requestPort(String externalHost, ServiceKey<?> key);

    /** Get all endpoints for the service on the specified node and schema. */
    Set<InstanceAddress> getEndpoints(ServiceKey<?> schema);

    /** Register a monitor to be notified when the service registry changes.
     * <p></p>
     * {@link ServiceMonitorIf#onChange()} will be called when the registry changes.
     * Spurious calls to {@link ServiceMonitorIf#onChange()} are allowed depending
     * on the implementation.
     * <p></p>
     * Behavior of the monitor depends on the implementation of the registry, and the
     * monitor type.
     * <ul>
     * <li>{@link ServiceChangeMonitor} is notified when any node for the service changes.</li>
     * </ul>
     * */
    void registerMonitor(ServiceMonitorIf monitor) throws Exception;
}
