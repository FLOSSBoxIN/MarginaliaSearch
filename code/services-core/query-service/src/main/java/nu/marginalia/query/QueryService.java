package nu.marginalia.query;

import com.google.gson.Gson;
import com.google.inject.Inject;
import nu.marginalia.client.Context;
import nu.marginalia.db.DomainBlacklist;
import nu.marginalia.index.client.IndexClient;
import nu.marginalia.index.client.model.query.SearchSpecification;
import nu.marginalia.index.client.model.results.DecoratedSearchResultItem;
import nu.marginalia.index.client.model.results.SearchResultSet;
import nu.marginalia.nodecfg.NodeConfigurationService;
import nu.marginalia.query.model.QueryParams;
import nu.marginalia.query.model.QueryResponse;
import nu.marginalia.query.svc.NodeConfigurationWatcher;
import nu.marginalia.query.svc.QueryFactory;
import nu.marginalia.service.server.BaseServiceParams;
import nu.marginalia.service.server.Service;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class QueryService extends Service {

    private final IndexClient indexClient;
    private final NodeConfigurationWatcher nodeWatcher;
    private final Gson gson;
    private final DomainBlacklist blacklist;
    private final QueryFactory queryFactory;

    private volatile List<Integer> nodes = new ArrayList<>();

    @Inject
    public QueryService(BaseServiceParams params,
                        IndexClient indexClient,
                        NodeConfigurationWatcher nodeWatcher,
                        Gson gson,
                        DomainBlacklist blacklist,
                        QueryFactory queryFactory)
    {
        super(params);
        this.indexClient = indexClient;
        this.nodeWatcher = nodeWatcher;
        this.gson = gson;
        this.blacklist = blacklist;
        this.queryFactory = queryFactory;

        Spark.post("/delegate/", this::delegateToIndex, gson::toJson);
        Spark.post("/search/", this::search, gson::toJson);
    }

    private Object search(Request request, Response response) {
        String json = request.body();
        QueryParams params = gson.fromJson(json, QueryParams.class);

        var query = queryFactory.createQuery(params);
        var rsp = executeQuery(Context.fromRequest(request), query.specs);

        rsp.results.removeIf(this::isBlacklisted);

        response.type("application/json");

        return new QueryResponse(
                query.specs,
                rsp.results,
                query.searchTermsHuman,
                List.of(), // no problems
                query.domain
        );
    }

    private SearchResultSet delegateToIndex(Request request, Response response) {
        String json = request.body();
        SearchSpecification specsSet = gson.fromJson(json, SearchSpecification.class);

        response.type("application/json");

        return executeQuery(Context.fromRequest(request), specsSet);
    }

    private SearchResultSet executeQuery(Context ctx, SearchSpecification query) {
        var nodes = nodeWatcher.getQueryNodes();

        return indexClient.query(ctx, nodes, query);
    }

    private boolean isBlacklisted(DecoratedSearchResultItem item) {
        return blacklist.isBlacklisted(item.domainId());
    }
}
