package nu.marginalia.crawl.retreival.fetcher;

import com.google.inject.Inject;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import lombok.SneakyThrows;
import nu.marginalia.UserAgent;
import nu.marginalia.crawl.retreival.Cookies;
import nu.marginalia.crawl.retreival.RateLimitException;
import nu.marginalia.crawl.retreival.fetcher.ContentTypeProber.ContentTypeProbeResult;
import nu.marginalia.crawl.retreival.fetcher.socket.FastTerminatingSocketFactory;
import nu.marginalia.crawl.retreival.fetcher.socket.IpInterceptingNetworkInterceptor;
import nu.marginalia.crawl.retreival.fetcher.socket.NoSecuritySSL;
import nu.marginalia.crawl.retreival.fetcher.warc.WarcRecorder;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.body.ContentTypeLogic;
import nu.marginalia.model.body.DocumentBodyExtractor;
import nu.marginalia.model.body.HttpFetchResult;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.X509TrustManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


public class HttpFetcherImpl implements HttpFetcher {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final String userAgentString;
    private final String userAgentIdentifier;
    private final Cookies cookies = new Cookies();

    private static final SimpleRobotRulesParser robotsParser = new SimpleRobotRulesParser();

    private static final ContentTypeLogic contentTypeLogic = new ContentTypeLogic();
    private final ContentTypeProber contentTypeProber;
    private final SoftIfModifiedSinceProber softIfModifiedSinceProber;

    @Override
    public void setAllowAllContentTypes(boolean allowAllContentTypes) {
        contentTypeLogic.setAllowAllContentTypes(allowAllContentTypes);
    }

    private final OkHttpClient client;

    private static final FastTerminatingSocketFactory ftSocketFactory = new FastTerminatingSocketFactory();

    @SneakyThrows
    private OkHttpClient createClient(Dispatcher dispatcher, ConnectionPool pool) {
        var builder = new OkHttpClient.Builder();
        if (dispatcher != null) {
            builder.dispatcher(dispatcher);
        }

        return builder.sslSocketFactory(NoSecuritySSL.buildSocketFactory(), (X509TrustManager) NoSecuritySSL.trustAllCerts[0])
            .socketFactory(ftSocketFactory)
            .hostnameVerifier(NoSecuritySSL.buildHostnameVerifyer())
            .addNetworkInterceptor(new IpInterceptingNetworkInterceptor())
            .connectionPool(pool)
            .cookieJar(cookies.getJar())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    }

    @Override
    public List<String> getCookies() {
        return cookies.getCookies();
    }

    @Override
    public void clearCookies() {
        cookies.clear();
    }

    @Inject
    public HttpFetcherImpl(UserAgent userAgent,
                           Dispatcher dispatcher,
                           ConnectionPool connectionPool)
    {
        this.client = createClient(dispatcher, connectionPool);
        this.userAgentString = userAgent.uaString();
        this.userAgentIdentifier = userAgent.uaIdentifier();
        this.contentTypeProber = new ContentTypeProber(userAgentString, client);
        this.softIfModifiedSinceProber = new SoftIfModifiedSinceProber(userAgentString, client);
    }

    public HttpFetcherImpl(String userAgent) {
        this.client = createClient(null, new ConnectionPool());
        this.userAgentString = userAgent;
        this.userAgentIdentifier = userAgent;
        this.contentTypeProber = new ContentTypeProber(userAgent, client);
        this.softIfModifiedSinceProber = new SoftIfModifiedSinceProber(userAgent, client);
    }

    /**
     * Probe the domain to see if it is reachable, attempting to identify which schema to use,
     * and if there are any redirects.  This is done by one or more HEAD requests.
     *
     * @param url The URL to probe.
     * @return The result of the probe, indicating the state and the URL.
     */
    @Override
    @SneakyThrows
    public FetchResult probeDomain(EdgeUrl url) {
        var head = new Request.Builder().head().addHeader("User-agent", userAgentString)
                .url(url.toString())
                .build();

        var call = client.newCall(head);

        try (var rsp = call.execute()) {
            EdgeUrl requestUrl = new EdgeUrl(rsp.request().url().toString());

            if (!Objects.equals(requestUrl.domain, url.domain)) {
                return new FetchResult(FetchResultState.REDIRECT, requestUrl);
            }
            return new FetchResult(FetchResultState.OK, requestUrl);
        }

        catch (Exception ex) {
            if (url.proto.equalsIgnoreCase("http") && "/".equals(url.path)) {
                return probeDomain(new EdgeUrl("https", url.domain, url.port, url.path, url.param));
            }

            logger.info("Error during fetching {}", ex.getMessage());
            return new FetchResult(FetchResultState.ERROR, url);
        }
    }


    @Override
    @SneakyThrows
    public HttpFetchResult fetchContent(EdgeUrl url,
                                           WarcRecorder warcRecorder,
                                           ContentTags contentTags)
    {

        // We don't want to waste time and resources on URLs that are not HTML, so if the file ending
        // looks like it might be something else, we perform a HEAD first to check the content type
        if (contentTags.isEmpty() && contentTypeLogic.isUrlLikeBinary(url))
        {
            ContentTypeProbeResult probeResult = contentTypeProber.probeContentType(url);
            if (probeResult instanceof ContentTypeProbeResult.Ok ok) {
                url = ok.resolvedUrl();
            }
            else if (probeResult instanceof ContentTypeProbeResult.BadContentType badContentType) {
                warcRecorder.flagAsFailedContentTypeProbe(url, badContentType.contentType(), badContentType.statusCode());
                return new HttpFetchResult.ResultNone();
            }
            else if (probeResult instanceof ContentTypeProbeResult.BadContentType.Timeout timeout) {
                warcRecorder.flagAsTimeout(url);

                return new HttpFetchResult.ResultException(timeout.ex());
            }
            else if (probeResult instanceof ContentTypeProbeResult.Exception exception) {
                warcRecorder.flagAsError(url, exception.ex());

                return new HttpFetchResult.ResultException(exception.ex());
            }
        }
        else {
            // Possibly do a soft probe to see if the URL has been modified since the last time we crawled it
            // if we have reason to suspect ETags are not supported by the server.
            if (softIfModifiedSinceProber.probeModificationTime(url, contentTags)) {
                return new HttpFetchResult.Result304Raw();
            }
        }

        var getBuilder = new Request.Builder().get();

        getBuilder.url(url.toString())
                .addHeader("Accept-Encoding", "gzip")
                .addHeader("Accept-Language", "en,*;q=0.5")
                .addHeader("Accept", "text/html, application/xhtml+xml, */*;q=0.8")
                .addHeader("User-agent", userAgentString);

        contentTags.paint(getBuilder);

        HttpFetchResult result = warcRecorder.fetch(client, getBuilder.build());

        if (result instanceof HttpFetchResult.ResultOk ok) {
            if (ok.statusCode() == 429) {
                String retryAfter = Objects.requireNonNullElse(ok.header("Retry-After"), "1000");
                throw new RateLimitException(retryAfter);
            }
            if (ok.statusCode() == 304) {
                return new HttpFetchResult.Result304Raw();
            }
            if (ok.statusCode() == 200) {
                return ok;
            }
        }

        return result;
    }

    @Override
    public SimpleRobotRules fetchRobotRules(EdgeDomain domain, WarcRecorder recorder) {
        return fetchRobotsForProto("https", recorder, domain)
                .or(() -> fetchRobotsForProto("http", recorder, domain))
                .orElseGet(() -> new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL));
    }

    @Override
    public SitemapRetriever createSitemapRetriever() {
        return new SitemapRetriever();
    }

    private Optional<SimpleRobotRules> fetchRobotsForProto(String proto, WarcRecorder recorder, EdgeDomain domain) {
        try {
            var url = new EdgeUrl(proto, domain, null, "/robots.txt", null);

            var getBuilder = new Request.Builder().get();

            getBuilder.url(url.toString())
                    .addHeader("Accept-Encoding", "gzip")
                    .addHeader("Accept", "text/*, */*;q=0.9")
                    .addHeader("User-agent", userAgentString);

            HttpFetchResult result = recorder.fetch(client, getBuilder.build());

            return DocumentBodyExtractor.asBytes(result).mapOpt((contentType, body) ->
                robotsParser.parseContent(url.toString(),
                        body,
                        contentType.toString(),
                        userAgentIdentifier)
            );

        }
        catch (Exception ex) {
            return Optional.empty();
        }
    }


}

