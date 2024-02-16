package nu.marginalia.ranking.data;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import lombok.SneakyThrows;
import nu.marginalia.query.client.QueryClient;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** A source for the inverted link graph,
 * which is the same as the regular graph except
 * the direction of the links have been inverted */
public class InvertedLinkGraphSource extends AbstractGraphSource {
    private final QueryClient queryClient;

    @Inject
    public InvertedLinkGraphSource(HikariDataSource dataSource, QueryClient queryClient) {
        super(dataSource);
        this.queryClient = queryClient;
    }
    @SneakyThrows
    @Override
    public Graph<Integer, ?> getGraph() {
        Graph<Integer, ?> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        addVertices(graph);

        var allLinks = queryClient.getAllDomainLinks();
        var iter = allLinks.iterator();
        while (iter.advance()) {
            if (!graph.containsVertex(iter.dest())) {
                continue;
            }
            if (!graph.containsVertex(iter.source())) {
                continue;
            }

            // Invert the edge
            graph.addEdge(iter.dest(), iter.source());
        }

        return graph;
    }
}
