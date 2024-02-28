package nu.marginalia.service.discovery.property;

import io.grpc.ServiceDescriptor;
import nu.marginalia.service.id.ServiceId;

public sealed interface ServiceKey<P extends ServicePartition> {
    String toPath();

    static ServiceKey<ServicePartition.None> forRest(ServiceId id) {
        return new Rest(id.serviceName);
    }
    static ServiceKey<ServicePartition.None> forRest(ServiceId id, int node) {
        if (node == 0) {
            return forRest(id);
        }

        return new Rest(id.serviceName + "-" + node);
    }

    static Grpc<ServicePartition> forServiceDescriptor(ServiceDescriptor descriptor, ServicePartition partition) {
        return new Grpc<>(descriptor.getName(), partition);
    }

    static <P2 extends ServicePartition & PartitionTraits.Grpc> Grpc<P2> forGrpcApi(Class<?> apiClass, P2 partition) {
        try {
            var name = apiClass.getField("SERVICE_NAME").get(null);
            return new Grpc<P2>(name.toString(), partition);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("Could not get SERVICE_NAME from " + apiClass.getSimpleName(), e);
        }
    }


    <P2 extends ServicePartition & PartitionTraits.Grpc & PartitionTraits.Unicast>
    Grpc<P2> forPartition(P2 partition);


    record Rest(String name) implements ServiceKey<ServicePartition.None> {
        public String toPath() {
            return STR."/services/rest/\{name}";
        }

        @Override
        public
        <P2 extends ServicePartition & PartitionTraits.Grpc & PartitionTraits.Unicast>
            Grpc<P2> forPartition(P2 partition)
        {
            throw new UnsupportedOperationException();
        }
    }
    record Grpc<P extends ServicePartition>(String name, P partition) implements ServiceKey<P> {
        public String baseName() {
            return STR."/services/grpc/\{name}";
        }
        public String toPath() {
            return STR."/services/grpc/\{name}/\{partition.identifier()}";
        }

        @Override
        public
        <P2 extends ServicePartition & PartitionTraits.Grpc & PartitionTraits.Unicast>
            Grpc<P2> forPartition(P2 partition)
        {
            return new Grpc<>(name, partition);
        }
    }

}
