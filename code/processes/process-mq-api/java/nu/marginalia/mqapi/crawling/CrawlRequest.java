package nu.marginalia.mqapi.crawling;

import nu.marginalia.storage.model.FileStorageId;

/**
 * A request to start a crawl
 */
public class CrawlRequest {

    /**
     * (optional)  Name of a single domain to be re-crawled
     */
    public String targetDomainName;

    /**
     * File storage where the crawl data will be written.  If it contains existing crawl data,
     * this crawl data will be referenced for e-tags and last-mofified checks.
     */
    public FileStorageId crawlStorage;

    public CrawlRequest(String targetDomainName, FileStorageId crawlStorage) {
        this.targetDomainName = targetDomainName;
        this.crawlStorage = crawlStorage;
    }

    public static CrawlRequest forSingleDomain(String targetDomainName, FileStorageId crawlStorage) {
        return new CrawlRequest(targetDomainName, crawlStorage);
    }

    public static CrawlRequest forFullCrawl(FileStorageId crawlStorage) {
        return new CrawlRequest(null, crawlStorage);
    }

}
