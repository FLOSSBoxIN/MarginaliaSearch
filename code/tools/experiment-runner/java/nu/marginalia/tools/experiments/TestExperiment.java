package nu.marginalia.tools.experiments;

import nu.marginalia.model.crawldata.CrawledDomain;
import nu.marginalia.tools.LegacyExperiment;

public class TestExperiment extends LegacyExperiment {
    @Override
    public boolean process(CrawledDomain domain) {
        return true;
    }

    @Override
    public void onFinish() {
        System.out.println("Tada!");
    }
}
