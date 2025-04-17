package nu.marginalia.crawl.fetcher;

import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;

/** Encapsulates request modifiers; the ETag and Last-Modified tags for a resource */
public record ContentTags(String etag, String lastMod) {
    public static ContentTags empty() {
        return new ContentTags(null, null);
    }

    public boolean isPresent() {
        return etag != null || lastMod != null;
    }

    public boolean isEmpty() {
        return etag == null && lastMod == null;
    }

    /** Paints the tags onto the request builder. */
    public void paint(ClassicRequestBuilder getBuilder) {

        if (etag != null) {
            getBuilder.addHeader("If-None-Match", etag);
        }

        if (lastMod != null) {
            getBuilder.addHeader("If-Modified-Since", lastMod);
        }
    }
}
