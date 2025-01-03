package nu.marginalia.rss.model;

import java.util.List;

public record FeedItems(String domain,
                        String feedUrl,
                        String updated,
                        List<FeedItem> items) {

    // List.of() is immutable, so we can use the same instance for all empty FeedItems
    private static final FeedItems EMPTY = new FeedItems("",  "","1970-01-01T00:00:00.000+00000", List.of());
    public static FeedItems none() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
