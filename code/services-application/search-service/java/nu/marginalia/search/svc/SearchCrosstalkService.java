package nu.marginalia.search.svc;

import com.google.inject.Inject;
import nu.marginalia.search.JteRenderer;
import nu.marginalia.search.SearchOperator;
import nu.marginalia.search.model.NavbarModel;
import nu.marginalia.search.model.SimpleSearchResults;
import nu.marginalia.search.model.UrlDetails;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class SearchCrosstalkService {
    private static final Logger logger = LoggerFactory.getLogger(SearchCrosstalkService.class);
    private final SearchOperator searchOperator;
    private final JteRenderer renderer;

    @Inject
    public SearchCrosstalkService(SearchOperator searchOperator, JteRenderer renderer) throws IOException
    {
        this.searchOperator = searchOperator;
        this.renderer = renderer;
    }

    public Object handle(Request request, Response response) throws SQLException {
        String domains = request.queryParams("domains");
        String[] parts = StringUtils.split(domains, ',');

        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected exactly two domains");
        }

        response.type("text/html");

        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }

        SimpleSearchResults resAtoB = searchOperator.doLinkSearch(parts[0], parts[1]);
        SimpleSearchResults resBtoA = searchOperator.doLinkSearch(parts[1], parts[0]);

        CrosstalkResult model = new CrosstalkResult(parts[0], parts[1], resAtoB.results, resBtoA.results);

        return renderer.render(
                "siteinfo/crosstalk.jte",
                Map.of("model", model,
                        "navbar", NavbarModel.SITEINFO));
    }



    public record CrosstalkResult(String domainA,
                                   String domainB,
                                   List<UrlDetails> aToB,
                                   List<UrlDetails> bToA)
    {

        public boolean hasBoth() {
            return !aToB.isEmpty() && !bToA.isEmpty();
        }
        public boolean hasA() {
            return !aToB.isEmpty();
        }
        public boolean hasB() {
            return !bToA.isEmpty();
        }
    }
}
