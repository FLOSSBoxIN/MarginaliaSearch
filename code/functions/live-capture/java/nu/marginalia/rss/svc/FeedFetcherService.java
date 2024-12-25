package nu.marginalia.rss.svc;

import com.apptasticsoftware.rssreader.Item;
import com.apptasticsoftware.rssreader.RssReader;
import com.google.inject.Inject;
import com.opencsv.CSVReader;
import nu.marginalia.WmsaHome;
import nu.marginalia.executor.client.ExecutorClient;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.nodecfg.NodeConfigurationService;
import nu.marginalia.rss.db.FeedDb;
import nu.marginalia.rss.db.FeedDbWriter;
import nu.marginalia.rss.model.FeedDefinition;
import nu.marginalia.rss.model.FeedItem;
import nu.marginalia.rss.model.FeedItems;
import nu.marginalia.service.control.ServiceHeartbeat;
import nu.marginalia.storage.FileStorageService;
import nu.marginalia.storage.model.FileStorage;
import nu.marginalia.storage.model.FileStorageType;
import nu.marginalia.util.SimpleBlockingThreadPool;
import org.apache.commons.io.input.BOMInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

public class FeedFetcherService {
    private static final int MAX_FEED_ITEMS = 10;
    private static final Logger logger = LoggerFactory.getLogger(FeedFetcherService.class);

    private final RssReader rssReader = new RssReader();

    private final FeedDb feedDb;
    private final FileStorageService fileStorageService;
    private final NodeConfigurationService nodeConfigurationService;
    private final ServiceHeartbeat serviceHeartbeat;
    private final ExecutorClient executorClient;

    private final DomainLocks domainLocks = new DomainLocks();

    private volatile boolean updating;
    private boolean deterministic = false;

    @Inject
    public FeedFetcherService(FeedDb feedDb,
                              FileStorageService fileStorageService,
                              NodeConfigurationService nodeConfigurationService,
                              ServiceHeartbeat serviceHeartbeat,
                              ExecutorClient executorClient)
    {
        this.feedDb = feedDb;
        this.fileStorageService = fileStorageService;
        this.nodeConfigurationService = nodeConfigurationService;
        this.serviceHeartbeat = serviceHeartbeat;
        this.executorClient = executorClient;


        // Add support for some alternate date tags for atom
        rssReader.addItemExtension("issued", this::setDateFallback);
        rssReader.addItemExtension("created", this::setDateFallback);
    }

    private void setDateFallback(Item item, String value) {
        if (item.getPubDate().isEmpty()) {
            item.setPubDate(value);
        }
    }

    public enum UpdateMode {
        CLEAN,
        REFRESH
    };

    /** Disable random-based heuristics.  This is meant for testing */
    public void setDeterministic() {
        this.deterministic = true;
    }

    public void updateFeeds(UpdateMode updateMode) throws IOException {
        if (updating) // Prevent concurrent updates
        {
            throw new IllegalStateException("Already updating feeds, refusing to start another update");
        }

        try (FeedDbWriter writer = feedDb.createWriter();
             HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newCachedThreadPool())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build();
             var heartbeat = serviceHeartbeat.createServiceAdHocTaskHeartbeat("Update Rss Feeds")
        ) {
            updating = true;

            // Read the feed definitions from the database, if they are not available, read them from the system's
            // RSS exports instead

            Collection<FeedDefinition> definitions = feedDb.getAllFeeds();
            Map<String, Integer> errorCounts = feedDb.getAllErrorCounts();

            // If we didn't get any definitions, or a clean update is requested, read the definitions from the system
            // instead
            if (definitions == null || updateMode == UpdateMode.CLEAN) {
                definitions = readDefinitionsFromSystem();
            }

            logger.info("Found {} feed definitions", definitions.size());

            final AtomicInteger definitionsUpdated = new AtomicInteger(0);
            final int totalDefinitions = definitions.size();

            SimpleBlockingThreadPool executor = new SimpleBlockingThreadPool("FeedFetcher", 64, 4);

            for (var feed : definitions) {
                executor.submitQuietly(() -> {
                    try {
                        var oldData = feedDb.getFeed(new EdgeDomain(feed.domain()));

                        // If we have existing data, we might skip updating it with a probability that increases with time,
                        // this is to avoid hammering the feeds that are updated very rarely and save some time and resources
                        // on our end

                        /* Disable for now:

                        if (!oldData.isEmpty()) {
                            Duration duration = feed.durationSinceUpdated();
                            long daysSinceUpdate = duration.toDays();


                            if (deterministic || (daysSinceUpdate > 2 && ThreadLocalRandom.current()
                                    .nextInt(1, 1 + (int) Math.min(10, daysSinceUpdate) / 2) > 1)) {
                                // Skip updating this feed, just write the old data back instead
                                writer.saveFeed(oldData);
                                return;
                            }
                        }
                        */

                        FetchResult feedData;
                        try (DomainLocks.DomainLock domainLock = domainLocks.lockDomain(new EdgeDomain(feed.domain()))) {
                            feedData = fetchFeedData(feed, client);
                        } catch (Exception ex) {
                            feedData = new FetchResult.TransientError();
                        }

                        switch (feedData) {
                            case FetchResult.Success(String value) -> writer.saveFeed(parseFeed(value, feed));
                            case FetchResult.TransientError() -> {
                                int errorCount = errorCounts.getOrDefault(feed.domain().toLowerCase(), 0);
                                writer.setErrorCount(feed.domain().toLowerCase(), ++errorCount);

                                if (errorCount < 5) {
                                    // Permit the server a few days worth of retries before we drop the feed entirely
                                    writer.saveFeed(oldData);
                                }
                            }
                            case FetchResult.PermanentError() -> {
                            } // let the definition be forgotten about
                        }

                    }
                    finally {
                        if ((definitionsUpdated.incrementAndGet() % 1_000) == 0) {
                            // Update the progress every 1k feeds, to avoid hammering the database and flooding the logs
                            heartbeat.progress("Updated " + definitionsUpdated + "/" + totalDefinitions + " feeds", definitionsUpdated.get(), totalDefinitions);
                        }
                    }
                });
            }

            executor.shutDown();
            // Wait for the executor to finish, but give up after 60 minutes to avoid hanging indefinitely
            for (int waitedMinutes = 0; waitedMinutes < 60; waitedMinutes++) {
                if (executor.awaitTermination(1, TimeUnit.MINUTES)) break;
            }
            executor.shutDownNow();

            // Wait for any in-progress writes to finish before switching the database
            // in case we ended up murdering the writer with shutDownNow.  It's a very
            // slim chance but this increases the odds of a clean switch over.

            TimeUnit.SECONDS.sleep(5);

            feedDb.switchDb(writer);

        } catch (SQLException|InterruptedException e) {
            logger.error("Error updating feeds", e);
        }
        finally {
            updating = false;
        }
    }

    private FetchResult fetchFeedData(FeedDefinition feed, HttpClient client) {
        try {
            URI uri = new URI(feed.feedUrl());

            HttpRequest getRequest = HttpRequest.newBuilder()
                    .GET()
                    .uri(uri)
                    .header("User-Agent", WmsaHome.getUserAgent().uaIdentifier())
                    .header("Accept", "text/*, */*;q=0.9")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            for (int i = 0; i < 3; i++) {
                var rs = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
                if (429 == rs.statusCode()) {
                    int retryAfter = Integer.parseInt(rs.headers().firstValue("Retry-After").orElse("2"));
                    Thread.sleep(Duration.ofSeconds(Math.clamp(retryAfter, 1, 5)));
                } else if (200 == rs.statusCode()) {
                    return new FetchResult.Success(rs.body());
                } else if (404 == rs.statusCode()) {
                    return new FetchResult.PermanentError(); // never try again
                } else {
                    return new FetchResult.TransientError(); // we try again in a few days
                }
            }
        }
        catch (Exception ex) {
            logger.debug("Error fetching feed", ex);
        }

        return new FetchResult.TransientError();
    }

    public sealed interface FetchResult {
        record Success(String value) implements FetchResult {}
        record TransientError() implements FetchResult {}
        record PermanentError()  implements FetchResult {}
    }

    public Collection<FeedDefinition> readDefinitionsFromSystem() throws IOException {
        Collection<FileStorage> storages = getLatestFeedStorages();
        List<FeedDefinition> feedDefinitionList = new ArrayList<>();

        for (var storage : storages) {
            var url = executorClient.remoteFileURL(storage, "feeds.csv.gz");

            try (var feedStream = new GZIPInputStream(url.openStream())) {
                CSVReader reader = new CSVReader(new java.io.InputStreamReader(feedStream));

                for (String[] row : reader) {
                    if (row.length < 3) {
                        continue;
                    }
                    var domain = row[0].trim();
                    var feedUrl = row[2].trim();

                    feedDefinitionList.add(new FeedDefinition(domain, feedUrl, null));
                }

            }
        }

        return feedDefinitionList;
    }

    private Collection<FileStorage> getLatestFeedStorages() {
        // Find the newest feed storage for each node

        Map<Integer, FileStorage> newestStorageByNode = new HashMap<>();

        for (var node : nodeConfigurationService.getAll()) {
            int nodeId = node.node();

            for (var storage: fileStorageService.getEachFileStorage(nodeId, FileStorageType.EXPORT)) {
                if (!storage.description().startsWith("Feeds "))
                    continue;

                newestStorageByNode.compute(storage.node(), new KeepNewerFeedStorage(storage));
            }

        }

        return newestStorageByNode.values();
    }


    private static class KeepNewerFeedStorage implements BiFunction<Integer, FileStorage, FileStorage> {
        private final FileStorage newValue;

        private KeepNewerFeedStorage(FileStorage newValue) {
            this.newValue = newValue;
        }

        public FileStorage apply(Integer node, @Nullable FileStorage oldValue) {
            if (oldValue == null) return newValue;

            return newValue.createDateTime().isAfter(oldValue.createDateTime())
                    ? newValue
                    : oldValue;
        }
    }

    public FeedItems parseFeed(String feedData, FeedDefinition definition) {
        try {
            feedData = sanitizeEntities(feedData);

            List<Item> rawItems = rssReader.read(
                    // Massage the data to maximize the possibility of the flaky XML parser consuming it
                    new BOMInputStream(new ByteArrayInputStream(feedData.trim().getBytes(StandardCharsets.UTF_8)), false)
            ).toList();

            boolean keepUriFragment = rawItems.size() < 2 || areFragmentsDisparate(rawItems);

            var items = rawItems.stream()
                    .map(item -> FeedItem.fromItem(item, keepUriFragment))
                    .filter(new IsFeedItemDateValid())
                    .sorted()
                    .limit(MAX_FEED_ITEMS)
                    .toList();

            return new FeedItems(
                    definition.domain(),
                    definition.feedUrl(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    items);

        } catch (Exception e) {
            logger.debug("Exception", e);
            return FeedItems.none();
        }
    }

    private static final Map<String, String> HTML_ENTITIES = Map.of(
            "&raquo;", "»",
            "&laquo;", "«",
            "&mdash;", "--",
            "&ndash;", "-",
            "&rsquo;", "'",
            "&lsquo;", "'",
            "&nbsp;", ""
    );

    /** The XML parser will blow up if you insert HTML entities in the feed XML,
     * which is unfortunately relatively common.  Replace them as far as is possible
     * with their corresponding characters
     */
    static String sanitizeEntities(String feedData) {
        String result = feedData;
        for (Map.Entry<String, String> entry : HTML_ENTITIES.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        // Handle lone ampersands not part of a recognized XML entity
        result = result.replaceAll("&(?!(amp|lt|gt|apos|quot);)", "&amp;");

        return result;
    }

    /** Decide whether to keep URI fragments in the feed items.
     * <p></p>
     * We keep fragments if there are multiple different fragments in the items.
     *
     * @param items The items to check
     * @return True if we should keep the fragments, false otherwise
     */
    private boolean areFragmentsDisparate(List<Item> items) {
        Set<String> seenFragments = new HashSet<>();

        try {
            for (var item : items) {
                if (item.getLink().isEmpty()) {
                    continue;
                }

                var link = item.getLink().get();
                if (!link.contains("#")) {
                    continue;
                }

                var fragment = new URI(link).getFragment();
                if (fragment != null) {
                    seenFragments.add(fragment);
                }
            }
        }
        catch (URISyntaxException e) {
            logger.debug("Exception", e);
            return true; // safe default
        }

        return seenFragments.size() > 1;
    }

    static class IsFeedItemDateValid implements Predicate<FeedItem> {
        private final String today = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);

        public boolean test(FeedItem item) {
            var date = item.date();

            if (date.isBlank()) {
                return false;
            }

            if (date.compareTo(today) > 0) {
                return false;
            }

            return true;
        }
    }
}
