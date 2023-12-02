package nu.marginalia.search.command.commands;

import com.google.inject.Inject;
import nu.marginalia.db.DbDomainQueries;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.search.SearchOperator;
import nu.marginalia.search.model.UrlDetails;
import nu.marginalia.search.command.SearchCommandInterface;
import nu.marginalia.search.command.SearchParameters;
import nu.marginalia.search.model.DomainInformation;
import nu.marginalia.search.model.SearchProfile;
import nu.marginalia.search.siteinfo.DomainInformationService;
import nu.marginalia.search.svc.SearchQueryIndexService;
import nu.marginalia.client.Context;
import nu.marginalia.renderer.MustacheRenderer;
import nu.marginalia.renderer.RendererFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class SiteListCommand implements SearchCommandInterface {
    private final DbDomainQueries domainQueries;
    private final DomainInformationService domainInformationService;
    private final SearchQueryIndexService searchQueryIndexService;
    private final SearchOperator searchOperator;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final MustacheRenderer<DomainInformation> siteInfoRenderer;

    private final Predicate<String> queryPatternPredicate = Pattern.compile("^site:[.A-Za-z\\-0-9]+$").asPredicate();

    @Inject
    public SiteListCommand(
            DomainInformationService domainInformationService,
            DbDomainQueries domainQueries,
            RendererFactory rendererFactory,
            SearchQueryIndexService searchQueryIndexService, SearchOperator searchOperator)
            throws IOException
    {
        this.domainQueries = domainQueries;
        this.domainInformationService = domainInformationService;

        siteInfoRenderer = rendererFactory.renderer("search/site-info");
        this.searchQueryIndexService = searchQueryIndexService;
        this.searchOperator = searchOperator;
    }

    @Override
    public Optional<Object> process(Context ctx, SearchParameters parameters) {
        if (!queryPatternPredicate.test(parameters.query())) {
            return Optional.empty();
        }

        var results = siteInfo(ctx, parameters.query());
        var domain = results.getDomain();

        List<UrlDetails> resultSet;
        Path screenshotPath = null;
        int domainId = -1;
        if (null != domain) {
            resultSet = searchOperator.doSiteSearch(ctx, domain.toString());

            var maybeId = domainQueries.tryGetDomainId(domain);
            if (maybeId.isPresent()) {
                domainId = maybeId.getAsInt();
                screenshotPath = Path.of("/screenshot/" + domainId);
            }
            else {
                domainId = -1;
                screenshotPath = Path.of("/screenshot/0");
            }
        }
        else {
            resultSet = Collections.emptyList();
        }

        Map<String, Object> renderObject = new HashMap<>(10);

        renderObject.put("query", parameters.query());
        renderObject.put("hideRanking", true);
        renderObject.put("profile", parameters.profileStr());
        renderObject.put("results", resultSet);
        renderObject.put("screenshot", screenshotPath == null ? "" : screenshotPath.toString());
        renderObject.put("domainId", domainId);
        renderObject.put("focusDomain", domain);

        return Optional.of(siteInfoRenderer.render(results, renderObject));
    }


    private DomainInformation siteInfo(Context ctx, String humanQuery) {
        String definePrefix = "site:";
        String word = humanQuery.substring(definePrefix.length()).toLowerCase();

        logger.info("Fetching Site Info: {}", word);

        var results = domainInformationService
                .domainInfo(word)
                .orElseGet(() -> unknownSite(word));

        logger.debug("Results = {}", results);

        return results;

    }

    private DomainInformation unknownSite(String url) {
        return DomainInformation.builder()
                .domain(new EdgeDomain(url))
                .suggestForCrawling(true)
                .unknownDomain(true)
                .build();
    }
}
