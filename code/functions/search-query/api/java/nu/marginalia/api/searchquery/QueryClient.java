package nu.marginalia.api.searchquery;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.prometheus.client.Summary;
import nu.marginalia.api.searchquery.model.query.QueryParams;
import nu.marginalia.api.searchquery.model.query.QueryResponse;
import nu.marginalia.service.client.GrpcChannelPoolFactory;
import nu.marginalia.service.client.GrpcSingleNodeChannelPool;
import nu.marginalia.service.discovery.property.ServiceKey;
import nu.marginalia.service.discovery.property.ServicePartition;

import javax.annotation.CheckReturnValue;
import java.time.Duration;

@Singleton
public class QueryClient  {

    private static final Summary wmsa_qs_api_search_time = Summary.build()
            .name("wmsa_qs_api_search_time")
            .help("query service search time")
            .register();

    private final GrpcSingleNodeChannelPool<QueryApiGrpc.QueryApiBlockingStub> queryApiPool;

    @Inject
    public QueryClient(GrpcChannelPoolFactory channelPoolFactory) throws InterruptedException {
        this.queryApiPool = channelPoolFactory.createSingle(
                ServiceKey.forGrpcApi(QueryApiGrpc.class, ServicePartition.any()),
                QueryApiGrpc::newBlockingStub);

        // Hold up initialization until we have a downstream connection
        this.queryApiPool.awaitChannel(Duration.ofSeconds(5));
    }

    @CheckReturnValue
    public QueryResponse search(QueryParams params) {
        var query = QueryProtobufCodec.convertQueryParams(params);

        return wmsa_qs_api_search_time.time(() ->
                QueryProtobufCodec.convertQueryResponse(
                        queryApiPool.call(QueryApiGrpc.QueryApiBlockingStub::query).run(query)
                )
        );
    }

}
