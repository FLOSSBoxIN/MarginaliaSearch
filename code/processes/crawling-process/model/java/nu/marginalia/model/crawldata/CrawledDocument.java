package nu.marginalia.model.crawldata;

import nu.marginalia.contenttype.ContentType;
import nu.marginalia.contenttype.DocumentBodyToString;
import nu.marginalia.model.EdgeUrl;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class CrawledDocument implements SerializableCrawlData {
    public String crawlId;

    public String url;
    public String contentType;

    public String timestamp;
    public int httpStatus;

    public String crawlerStatus;
    public String crawlerStatusDesc;

    @Nullable
    public String headers;

    public String documentBody() {
        return DocumentBodyToString.getStringData(
                ContentType.parse(contentType),
                documentBodyBytes);
    }

    public Document parseBody() throws IOException {
        return DocumentBodyToString.getParsedData(
                ContentType.parse(contentType),
                documentBodyBytes,
                url);
    }

    public boolean hasBody() {
        return documentBodyBytes.length > 0;
    }

    public String documentBody(int sampleSize) {
        if (sampleSize >= documentBodyBytes.length) {
            return documentBody();
        }

        byte[] bytes = new byte[sampleSize];
        System.arraycopy(documentBodyBytes, 0, bytes, 0, sampleSize);

        return DocumentBodyToString.getStringData(
                ContentType.parse(contentType),
                bytes);
    }

    public byte[] documentBodyBytes;
    /**
     * This is not guaranteed to be set in all versions of the format,
     * information may come in CrawledDomain instead
     */
    public Boolean hasCookies = false;

    public String lastModifiedMaybe;
    public String etagMaybe;

    public CrawledDocument(String crawlId, String url, String contentType, String timestamp, int httpStatus, String crawlerStatus, String crawlerStatusDesc, @Nullable String headers, byte[] documentBodyBytes, Boolean hasCookies, String lastModifiedMaybe, String etagMaybe) {
        this.crawlId = crawlId;
        this.url = url;
        this.contentType = contentType;
        this.timestamp = timestamp;
        this.httpStatus = httpStatus;
        this.crawlerStatus = crawlerStatus;
        this.crawlerStatusDesc = crawlerStatusDesc;
        this.headers = headers;
        this.documentBodyBytes = Objects.requireNonNullElse(documentBodyBytes, new byte[] {});
        this.hasCookies = hasCookies;
        this.lastModifiedMaybe = lastModifiedMaybe;
        this.etagMaybe = etagMaybe;
    }

    public static CrawledDocumentBuilder builder() {
        return new CrawledDocumentBuilder();
    }

    @Nullable
    private String getHeader(String header) {
        if (headers == null) {
            return null;
        }

        String headerString = header + ":";

        String[] headersLines = StringUtils.split(headers, '\n');
        for (String headerLine : headersLines) {
            if (StringUtils.startsWithIgnoreCase(headerLine, headerString)) {
                return headerLine.substring(headerString.length()).trim();
            }
        }

        return null;
    }

    /**
     * Returns the ETag header, or null if not present;
     * <p>
     * this is a compatibility shim between the old json format, which saves headers in a long string
     * and the new parquet format which saves only the ETag and Last-Modified headers in separate columns
     */
    public String getEtag() {
        if (etagMaybe != null) {
            return etagMaybe;
        }
        return getHeader("ETag");
    }

    /**
     * Returns the Last-Modified header, or null if not present
     * <p>
     * this is a compatibility shim between the old json format, which saves headers in a long string
     * * and the new parquet format which saves only the ETag and Last-Modified headers in separate columns
     */
    public String getLastModified() {
        if (lastModifiedMaybe != null) {
            return lastModifiedMaybe;
        }
        return getHeader("Last-Modified");
    }

    @Override
    public String getDomain() {
        if (url == null)
            return null;

        return EdgeUrl
                .parse(url)
                .map(EdgeUrl::getDomain)
                .map(Object::toString)
                .orElse(null);
    }

    public String toString() {
        return "CrawledDocument(crawlId=" + this.crawlId + ", url=" + this.url + ", contentType=" + this.contentType + ", timestamp=" + this.timestamp + ", httpStatus=" + this.httpStatus + ", crawlerStatus=" + this.crawlerStatus + ", crawlerStatusDesc=" + this.crawlerStatusDesc + ", headers=" + this.headers + ", documentBody=" + documentBody() + ", hasCookies=" + this.hasCookies + ", lastModifiedMaybe=" + this.lastModifiedMaybe + ", etagMaybe=" + this.etagMaybe + ")";
    }

    public static class CrawledDocumentBuilder {
        private String crawlId;
        private String url;
        private String contentType;
        private String timestamp;
        private int httpStatus;
        private String crawlerStatus;
        private String crawlerStatusDesc;
        private @Nullable String headers;
        private byte[] documentBodyBytes = new byte[0];
        private String recrawlState;
        private Boolean hasCookies;
        private String lastModifiedMaybe;
        private String etagMaybe;

        CrawledDocumentBuilder() {
        }

        public CrawledDocumentBuilder crawlId(String crawlId) {
            this.crawlId = crawlId;
            return this;
        }

        public CrawledDocumentBuilder url(String url) {
            this.url = url;
            return this;
        }

        public CrawledDocumentBuilder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public CrawledDocumentBuilder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public CrawledDocumentBuilder httpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public CrawledDocumentBuilder crawlerStatus(String crawlerStatus) {
            this.crawlerStatus = crawlerStatus;
            return this;
        }

        public CrawledDocumentBuilder crawlerStatusDesc(String crawlerStatusDesc) {
            this.crawlerStatusDesc = crawlerStatusDesc;
            return this;
        }

        public CrawledDocumentBuilder headers(@Nullable String headers) {
            this.headers = headers;
            return this;
        }

        public CrawledDocumentBuilder documentBody(String documentBody) {
            this.documentBodyBytes = documentBody.getBytes(StandardCharsets.UTF_8);
            return this;
        }
        public CrawledDocumentBuilder documentBodyBytes(byte[] documentBodyBytes) {
            this.documentBodyBytes = documentBodyBytes;
            return this;
        }
        @Deprecated
        public CrawledDocumentBuilder recrawlState(String recrawlState) {
            this.recrawlState = recrawlState;
            return this;
        }

        public CrawledDocumentBuilder hasCookies(Boolean hasCookies) {
            this.hasCookies = hasCookies;
            return this;
        }

        public CrawledDocumentBuilder lastModifiedMaybe(String lastModifiedMaybe) {
            this.lastModifiedMaybe = lastModifiedMaybe;
            return this;
        }

        public CrawledDocumentBuilder etagMaybe(String etagMaybe) {
            this.etagMaybe = etagMaybe;
            return this;
        }

        public CrawledDocument build() {
            return new CrawledDocument(this.crawlId, this.url, this.contentType, this.timestamp, this.httpStatus, this.crawlerStatus, this.crawlerStatusDesc, this.headers, this.documentBodyBytes, this.hasCookies, this.lastModifiedMaybe, this.etagMaybe);
        }

        public String toString() {
            return "CrawledDocument.CrawledDocumentBuilder(crawlId=" + this.crawlId + ", url=" + this.url + ", contentType=" + this.contentType + ", timestamp=" + this.timestamp + ", httpStatus=" + this.httpStatus + ", crawlerStatus=" + this.crawlerStatus + ", crawlerStatusDesc=" + this.crawlerStatusDesc + ", headers=" + this.headers + ", documentBodyBytes=" + Arrays.toString(this.documentBodyBytes) +  ", recrawlState=" + this.recrawlState + ", hasCookies=" + this.hasCookies + ", lastModifiedMaybe=" + this.lastModifiedMaybe + ", etagMaybe=" + this.etagMaybe + ")";
        }
    }
}
