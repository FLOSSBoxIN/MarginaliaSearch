package nu.marginalia.crawl.retreival.revisit;

import crawlercommons.robots.SimpleRobotRules;
import nu.marginalia.crawl.fetcher.ContentTags;
import nu.marginalia.crawl.fetcher.warc.WarcRecorder;
import nu.marginalia.crawl.retreival.CrawlDataReference;
import nu.marginalia.crawl.retreival.CrawlDelayTimer;
import nu.marginalia.crawl.retreival.CrawlerRetreiver;
import nu.marginalia.crawl.retreival.DomainCrawlFrontier;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.body.HttpFetchResult;
import nu.marginalia.model.crawldata.CrawledDocument;

import java.io.IOException;

/** This class encapsulates the logic for re-visiting a domain that has already been crawled.
 *  We may use information from the previous crawl to inform the next crawl, specifically the
 *  E-Tag and Last-Modified headers.
 */
public class CrawlerRevisitor {
    private final DomainCrawlFrontier crawlFrontier;
    private final CrawlerRetreiver crawlerRetreiver;
    private final WarcRecorder warcRecorder;

    public CrawlerRevisitor(DomainCrawlFrontier crawlFrontier,
                            CrawlerRetreiver crawlerRetreiver,
                            WarcRecorder warcRecorder) {
        this.crawlFrontier = crawlFrontier;
        this.crawlerRetreiver = crawlerRetreiver;
        this.warcRecorder = warcRecorder;
    }

    /** Performs a re-crawl of old documents, comparing etags and last-modified */
    public int recrawl(CrawlDataReference oldCrawlData,
                       SimpleRobotRules robotsRules,
                       CrawlDelayTimer delayTimer)
    throws InterruptedException {
        int recrawled = 0;
        int retained = 0;
        int errors = 0;
        int skipped = 0;

        for (;;) {
            if (errors > 20) {
                // If we've had too many errors, we'll stop trying to recrawl
                break;
            }

            CrawledDocument doc = oldCrawlData.nextDocument();

            if (doc == null)
                break;

            // This Shouldn't Happen (TM)
            var urlMaybe = EdgeUrl.parse(doc.url);
            if (urlMaybe.isEmpty())
                continue;
            var url = urlMaybe.get();

            // If we've previously 404:d on this URL, we'll refrain from trying to fetch it again,
            // since it's likely to 404 again.  It will be forgotten by the next crawl though, so
            // we'll eventually try again.

            if (doc.httpStatus == 404) {
                crawlFrontier.addVisited(url);
                continue;
            }

            // If the reference document is empty or the HTTP status is not 200, we'll skip it since it's
            // unlikely to produce anything meaningful for us.
            if (doc.httpStatus != 200)
                continue;
            if (!doc.hasBody())
                continue;

            if (!crawlFrontier.filterLink(url))
                continue;

            if (!crawlFrontier.addVisited(url))
                continue;

            if (!robotsRules.isAllowed(url.toString())) {
                warcRecorder.flagAsRobotsTxtError(url);
                continue;
            }


            double skipProb;

            // calculate the probability of skipping this document based on the
            // fraction of documents that haven't changed
            if (recrawled > 0) {
                skipProb = (double) retained / recrawled;

                // If we've crawled a lot of documents, we'll be more conservative
                // in trying to recrawl documents, to avoid hammering the server too much;
                // in the case of a large change, we'll eventually catch it anyway

                if (skipped + recrawled > 10_000) {
                    skipProb = Math.clamp(skipProb, 0.75, 0.99);
                } else if (skipped + recrawled > 1000) {
                    skipProb = Math.clamp(skipProb, 0.5, 0.99);
                } else {
                    skipProb = Math.clamp(skipProb, 0, 0.95);
                }

            } else {
                // If we haven't recrawled anything yet, we'll be more aggressive
                // in trying to recrawl documents
                skipProb = 0.25;
            }

            if (Math.random() < skipProb) //
            {
                // Since it looks like most of these documents haven't changed,
                // we'll load the documents directly; but we do this in a random
                // fashion to make sure we eventually catch changes over time
                // and ensure we discover new links

                try {
                    // Hoover up any links from the document
                    crawlFrontier.enqueueLinksFromDocument(url, doc.parseBody());

                }
                catch (IOException ex) {
                    //
                }
                // Add a WARC record so we don't repeat this
                warcRecorder.writeReferenceCopy(url,
                        doc.contentType,
                        doc.httpStatus,
                        doc.documentBodyBytes,
                        doc.headers,
                        new ContentTags(doc.etagMaybe, doc.lastModifiedMaybe)
                );

                skipped++;
            }
            else {
                // GET the document with the stored document as a reference
                // providing etag and last-modified headers, so we can recycle the
                // document if it hasn't changed without actually downloading it

                DocumentWithReference reference =  new DocumentWithReference(doc, oldCrawlData);

                var result = crawlerRetreiver.fetchContentWithReference(url, delayTimer, reference);

                if (reference.isSame(result)) {
                    retained++;
                }
                else if (result instanceof HttpFetchResult.ResultException) {
                    errors++;
                }

                recrawled++;
            }
        }

        return recrawled;
    }
}
