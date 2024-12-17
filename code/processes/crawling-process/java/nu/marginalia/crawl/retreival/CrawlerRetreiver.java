package nu.marginalia.crawl.retreival;

import crawlercommons.robots.SimpleRobotRules;
import nu.marginalia.atags.model.DomainLinks;
import nu.marginalia.contenttype.ContentType;
import nu.marginalia.crawl.CrawlerMain;
import nu.marginalia.crawl.fetcher.ContentTags;
import nu.marginalia.crawl.fetcher.HttpFetcher;
import nu.marginalia.crawl.fetcher.HttpFetcherImpl;
import nu.marginalia.crawl.fetcher.warc.WarcRecorder;
import nu.marginalia.crawl.logic.LinkFilterSelector;
import nu.marginalia.crawl.retreival.revisit.CrawlerRevisitor;
import nu.marginalia.crawl.retreival.revisit.DocumentWithReference;
import nu.marginalia.crawl.retreival.sitemap.SitemapFetcher;
import nu.marginalia.ip_blocklist.UrlBlocklist;
import nu.marginalia.link_parser.LinkParser;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.body.HttpFetchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class CrawlerRetreiver implements AutoCloseable {

    private static final int MAX_ERRORS = 20;
    private static final int HTTP_429_RETRY_LIMIT = 1; // Retry 429s once

    private final HttpFetcher fetcher;

    private final String domain;

    private static final LinkParser linkParser = new LinkParser();
    private static final Logger logger = LoggerFactory.getLogger(CrawlerRetreiver.class);

    private static final UrlBlocklist urlBlocklist = new UrlBlocklist();
    private static final LinkFilterSelector linkFilterSelector = new LinkFilterSelector();

    private final DomainProber domainProber;
    private final DomainCrawlFrontier crawlFrontier;
    private final WarcRecorder warcRecorder;
    private final CrawlerRevisitor crawlerRevisitor;

    private final SitemapFetcher sitemapFetcher;
    int errorCount = 0;

    public CrawlerRetreiver(HttpFetcher fetcher,
                            DomainProber domainProber,
                            CrawlerMain.CrawlSpecRecord specs,
                            WarcRecorder warcRecorder)
    {
        this.warcRecorder = warcRecorder;
        this.fetcher = fetcher;
        this.domainProber = domainProber;

        domain = specs.domain();

        crawlFrontier = new DomainCrawlFrontier(new EdgeDomain(domain), specs.urls(), specs.crawlDepth());
        crawlerRevisitor = new CrawlerRevisitor(crawlFrontier, this, warcRecorder);
        sitemapFetcher = new SitemapFetcher(crawlFrontier, fetcher.createSitemapRetriever());

        // We must always crawl the index page first, this is assumed when fingerprinting the server
        var fst = crawlFrontier.peek();
        if (fst != null) {
            // Ensure the index page is always crawled
            var root = fst.withPathAndParam("/", null);

            crawlFrontier.addFirst(root);
        }
    }

    // For testing
    public DomainCrawlFrontier getCrawlFrontier() {
        return crawlFrontier;
    }

    public int crawlDomain() {
        return crawlDomain(new DomainLinks(), new CrawlDataReference());
    }

    public int crawlDomain(DomainLinks domainLinks, CrawlDataReference oldCrawlData) {
        try {
            // Do an initial domain probe to determine the root URL
            EdgeUrl rootUrl;
            if (probeRootUrl() instanceof HttpFetcher.DomainProbeResult.Ok ok) rootUrl = ok.probedUrl();
            else return 1;

            // Sleep after the initial probe, we don't have access to the robots.txt yet
            // so we don't know the crawl delay
            TimeUnit.SECONDS.sleep(1);

            return crawlDomain(oldCrawlData, rootUrl, domainLinks);
        }
        catch (Exception ex) {
            logger.error("Error crawling domain {}", domain, ex);
            return 0;
        }
    }

    private int crawlDomain(CrawlDataReference oldCrawlData,
                            EdgeUrl rootUrl,
                            DomainLinks domainLinks) throws InterruptedException {

        final SimpleRobotRules robotsRules = fetcher.fetchRobotRules(rootUrl.domain, warcRecorder);
        final CrawlDelayTimer delayTimer = new CrawlDelayTimer(robotsRules.getCrawlDelay());

        delayTimer.waitFetchDelay(0); // initial delay after robots.txt

        sniffRootDocument(rootUrl, delayTimer);

        // Play back the old crawl data (if present) and fetch the documents comparing etags and last-modified
        if (crawlerRevisitor.recrawl(oldCrawlData, robotsRules, delayTimer) > 0) {
            // If we have reference data, we will always grow the crawl depth a bit
            crawlFrontier.increaseDepth(1.5, 2500);
        }

        // Add external links to the crawl frontier
        crawlFrontier.addAllToQueue(domainLinks.getUrls(rootUrl.proto));

        // Add links from the sitemap to the crawl frontier
        sitemapFetcher.downloadSitemaps(robotsRules, rootUrl);


        while (!crawlFrontier.isEmpty()
            && !crawlFrontier.isCrawlDepthReached()
            && errorCount < MAX_ERRORS
            && !Thread.interrupted())
        {
            var top = crawlFrontier.takeNextUrl();

            if (!robotsRules.isAllowed(top.toString())) {
                warcRecorder.flagAsRobotsTxtError(top);
                continue;
            }

            // Check the link filter if the endpoint should be fetched based on site-type
            if (!crawlFrontier.filterLink(top))
                continue;

            // Check vs blocklist
            if (urlBlocklist.isUrlBlocked(top))
                continue;

            if (!isAllowedProtocol(top.proto))
                continue;

            // Check if the URL is too long to insert into the DB
            if (top.toString().length() > 255)
                continue;

            if (!crawlFrontier.addVisited(top))
                continue;

            try {
                fetchContentWithReference(top, delayTimer, DocumentWithReference.empty());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return crawlFrontier.visitedSize();
    }

    public void syncAbortedRun(Path warcFile) {
        var resync = new CrawlerWarcResynchronizer(crawlFrontier, warcRecorder);

        resync.run(warcFile);
    }

    private HttpFetcher.DomainProbeResult probeRootUrl() throws IOException {
        // Construct an URL to the root of the domain, we don't know the schema yet so we'll
        // start with http and then try https if that fails
        var httpUrl = new EdgeUrl("https", new EdgeDomain(domain), null, "/", null);
        final HttpFetcher.DomainProbeResult domainProbeResult = domainProber.probeDomain(fetcher, domain, httpUrl);

        String ip;
        try {
            ip = InetAddress.getByName(domain).getHostAddress();
        } catch (UnknownHostException e) {
            ip = "";
        }

        // Write the domain probe result to the WARC file
        warcRecorder.writeWarcinfoHeader(ip, new EdgeDomain(domain), domainProbeResult);

        return domainProbeResult;
    }

    private void sniffRootDocument(EdgeUrl rootUrl, CrawlDelayTimer timer) {
        try {
            var url = rootUrl.withPathAndParam("/", null);

            HttpFetchResult result = fetchWithRetry(url, timer, HttpFetcher.ProbeType.DISABLED, ContentTags.empty());
            timer.waitFetchDelay(0);

            if (!(result instanceof HttpFetchResult.ResultOk ok))
                return;

            var optDoc = ok.parseDocument();
            if (optDoc.isEmpty())
                return;

            // Sniff the software based on the sample document
            var doc = optDoc.get();
            crawlFrontier.setLinkFilter(linkFilterSelector.selectFilter(doc));
            crawlFrontier.enqueueLinksFromDocument(url, doc);

            EdgeUrl faviconUrl = url.withPathAndParam("/favicon.ico", null);
            Optional<EdgeUrl> sitemapUrl = Optional.empty();

            for (var link : doc.getElementsByTag("link")) {
                String rel = link.attr("rel");
                String type = link.attr("type");

                if (rel.equals("icon") || rel.equals("shortcut icon")) {
                    String href = link.attr("href");

                    faviconUrl = linkParser.parseLink(url, href)
                            .filter(crawlFrontier::isSameDomain)
                            .orElse(faviconUrl);
                }

                // Grab the RSS/Atom as a sitemap if it exists
                if (rel.equalsIgnoreCase("alternate")
                && (type.equalsIgnoreCase("application/atom+xml") || type.equalsIgnoreCase("application/atomsvc+xml"))) {
                    String href = link.attr("href");

                    sitemapUrl = linkParser.parseLink(url, href)
                            .filter(crawlFrontier::isSameDomain);
                }
            }

            // Download the sitemap if available exists
            if (sitemapUrl.isPresent()) {
                sitemapFetcher.downloadSitemaps(List.of(sitemapUrl.get()));
                timer.waitFetchDelay(0);
            }

            // Grab the favicon if it exists
            fetchWithRetry(faviconUrl, timer, HttpFetcher.ProbeType.DISABLED, ContentTags.empty());
            timer.waitFetchDelay(0);
        }
        catch (Exception ex) {
            logger.error("Error configuring link filter", ex);
        }
        finally {
            crawlFrontier.addVisited(rootUrl);
        }
    }

    public HttpFetchResult fetchContentWithReference(EdgeUrl top,
                                                     CrawlDelayTimer timer,
                                                     DocumentWithReference reference) throws InterruptedException
    {
        logger.debug("Fetching {}", top);

        long startTime = System.currentTimeMillis();
        var contentTags = reference.getContentTags();

        HttpFetchResult fetchedDoc = fetchWithRetry(top, timer, HttpFetcher.ProbeType.FULL, contentTags);

        // Parse the document and enqueue links
        try {
            if (fetchedDoc instanceof HttpFetchResult.ResultOk ok) {
                var docOpt = ok.parseDocument();
                if (docOpt.isPresent()) {
                    var doc = docOpt.get();

                    crawlFrontier.enqueueLinksFromDocument(top, doc);
                    crawlFrontier.addVisited(new EdgeUrl(ok.uri()));
                }
            }
            else if (fetchedDoc instanceof HttpFetchResult.Result304Raw && reference.doc() != null) {
                var doc = reference.doc();

                warcRecorder.writeReferenceCopy(top, doc.contentType, doc.httpStatus, doc.documentBodyBytes, doc.headers, contentTags);

                fetchedDoc = new HttpFetchResult.Result304ReplacedWithReference(doc.url,
                        new ContentType(doc.contentType, "UTF-8"),
                        doc.documentBodyBytes);

                if (doc.documentBodyBytes != null) {
                    var parsed = doc.parseBody();

                    crawlFrontier.enqueueLinksFromDocument(top, parsed);
                    crawlFrontier.addVisited(top);
                }
            }
            else if (fetchedDoc instanceof HttpFetchResult.ResultException) {
                errorCount ++;
            }
        }
        catch (Exception ex) {
            logger.error("Error parsing document {}", top, ex);
        }

        timer.waitFetchDelay(System.currentTimeMillis() - startTime);

        return fetchedDoc;
    }

    /** Fetch a document and retry on 429s */
    private HttpFetchResult fetchWithRetry(EdgeUrl url,
                                           CrawlDelayTimer timer,
                                           HttpFetcher.ProbeType probeType,
                                           ContentTags contentTags) throws InterruptedException {

        long probeStart = System.currentTimeMillis();

        if (probeType == HttpFetcher.ProbeType.FULL) {
            retryLoop:
            for (int i = 0; i <= HTTP_429_RETRY_LIMIT; i++) {
                try {
                    var probeResult = fetcher.probeContentType(url, warcRecorder, contentTags);

                    switch (probeResult) {
                        case HttpFetcher.ContentTypeProbeResult.Ok(EdgeUrl resolvedUrl):
                            url = resolvedUrl; // If we were redirected while probing, use the final URL for fetching
                            break retryLoop;
                        case HttpFetcher.ContentTypeProbeResult.BadContentType badContentType:
                            return new HttpFetchResult.ResultNone();
                        case HttpFetcher.ContentTypeProbeResult.BadContentType.Timeout timeout:
                            return new HttpFetchResult.ResultException(timeout.ex());
                        case HttpFetcher.ContentTypeProbeResult.Exception exception:
                            return new HttpFetchResult.ResultException(exception.ex());
                        default:  // should be unreachable
                            throw new IllegalStateException("Unknown probe result");
                    }
                }
                catch (HttpFetcherImpl.RateLimitException ex) {
                    timer.waitRetryDelay(ex);
                }
                catch (Exception ex) {
                    logger.warn("Failed to fetch {}", url, ex);
                    return new HttpFetchResult.ResultException(ex);
                }
            }

            timer.waitFetchDelay(System.currentTimeMillis() - probeStart);
        }


        for (int i = 0; i <= HTTP_429_RETRY_LIMIT; i++) {
            try {
                return fetcher.fetchContent(url, warcRecorder, contentTags, probeType);
            }
            catch (HttpFetcherImpl.RateLimitException ex) {
                timer.waitRetryDelay(ex);
            }
            catch (Exception ex) {
                logger.warn("Failed to fetch {}", url, ex);
                return new HttpFetchResult.ResultException(ex);
            }
        }

        return new HttpFetchResult.ResultNone();
    }

    private boolean isAllowedProtocol(String proto) {
        return proto.equalsIgnoreCase("http")
                || proto.equalsIgnoreCase("https");
    }

    @Override
    public void close() throws Exception {
        warcRecorder.close();
    }

}
