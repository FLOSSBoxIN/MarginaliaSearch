package nu.marginalia.tools;

import nu.marginalia.crawling.model.CrawledDomain;

public interface CrawlDataExperiment extends Experiment {

    /** The experiment processes the domain here.
     *
     * @return true to continue, false to terminate.
     */
    boolean process(CrawledDomain domain);

    /** Invoked after all domains are processed
     *
     */
    void onFinish();

    default boolean isInterested(String domainName) {
        return true;
    }
}
