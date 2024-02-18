package nu.marginalia.client.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import nu.marginalia.service.id.ServiceId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.grpc.ConnectivityState.SHUTDOWN;

/** A pool of gRPC stubs for a service, with a separate stub for each node.
 * Manages broadcast-style request. */
public abstract class GrpcChannelPool<STUB> {
    public GrpcChannelPool(String serviceName) {
        this.serviceName = serviceName;
    }

    protected record ServiceAndNode(String service, int node) {
        public String getHostName() {
            return service+"-"+node;
        }
    }

    private final Map<ServiceAndNode, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ExecutorService virtualExecutorService = Executors.newVirtualThreadPerTaskExecutor();

    private final String serviceName;

    public GrpcChannelPool(ServiceId serviceId) {
        this.serviceName = serviceId.serviceName;
    }

    /** Get an API stub for the given node */
    public STUB apiForNode(int node) {
        return createStub(
            channels.compute(
                new ServiceAndNode(serviceName, node),
                this::refreshChannel)
        );
    }

    private ManagedChannel refreshChannel(ServiceAndNode serviceAndNode, ManagedChannel old) {
        if (old == null)
            return createChannel(serviceAndNode);

        // If the channel is in SHUTDOWN state, we need to create a new one
        // (shouldn't really happen in practice, but it's a good idea to be safe)
        if (old.getState(true) == SHUTDOWN) {
            old.shutdown();
            return createChannel(serviceAndNode);
        }

        return old;
    }

    protected ManagedChannel createChannel(ServiceAndNode serviceAndNode) {
        return ManagedChannelBuilder.forAddress(serviceAndNode.getHostName(), 81)
                .usePlaintext()
                .build();
    }

    /** Invoke a function on each node, returning a list of futures in a terminal state, as per
     * ExecutorService$invokeAll */
    public <T> List<Future<T>> invokeAll(Function<STUB, Callable<T>> callF) throws InterruptedException {
        List<Callable<T>> calls = getEligibleNodes().stream()
                .map(id -> callF.apply(apiForNode(id)))
                .toList();

        return virtualExecutorService.invokeAll(calls);
    }

    /** Invoke a function on each node, returning a stream of results */
    public <T> Stream<T> callEachSequential(Function<STUB, T> call) {
        return getEligibleNodes().stream()
                .map(id -> call.apply(apiForNode(id)));
    }


    /** Create a stub for the given channel, this is an operation
     * that needs to be implemented for the particular API this
     * pool is intended for
     */
    public abstract STUB createStub(ManagedChannel channel);

    /** Get the list of nodes that are eligible for broadcast-style requests */
    public abstract List<Integer> getEligibleNodes();

}
