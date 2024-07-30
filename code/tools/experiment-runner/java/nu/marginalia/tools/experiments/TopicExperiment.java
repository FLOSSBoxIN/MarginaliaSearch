package nu.marginalia.tools.experiments;

import com.google.inject.Inject;
import nu.marginalia.WmsaHome;
import nu.marginalia.converting.processor.classifier.adblock.GoogleAnwersSpamDetector;
import nu.marginalia.converting.processor.classifier.topic.RecipeDetector;
import nu.marginalia.converting.processor.classifier.topic.TextileCraftDetector;
import nu.marginalia.converting.processor.classifier.topic.WoodworkingDetector;
import nu.marginalia.converting.processor.logic.dom.DomPruningFilter;
import nu.marginalia.language.sentence.SentenceExtractor;
import nu.marginalia.model.crawldata.CrawledDomain;
import nu.marginalia.tools.LegacyExperiment;
import org.jsoup.Jsoup;

public class TopicExperiment extends LegacyExperiment {

    RecipeDetector recipeDetector = new RecipeDetector();
    WoodworkingDetector woodworkingDetector = new WoodworkingDetector();
    TextileCraftDetector textileCraftDetector = new TextileCraftDetector();
    GoogleAnwersSpamDetector spamDetector = new GoogleAnwersSpamDetector();

    SentenceExtractor se = new SentenceExtractor(WmsaHome.getLanguageModels());

    @Inject
    public TopicExperiment() {
    }

    @Override
    public boolean process(CrawledDomain domain) {
        if (domain.doc == null) return true;


        for (var doc : domain.doc) {
            if (doc.documentBody == null) continue;

            var parsed = Jsoup.parse(doc.documentBody);

            parsed.body().filter(new DomPruningFilter(0.5));
            var dld = se.extractSentences(parsed);

            if (dld.totalNumWords() < 250)
                continue;

            if (textileCraftDetector.testP(dld) > 0.3) {
                System.out.println("textilecraft\t" + doc.url);
            }
            if (woodworkingDetector.testP(dld) > 0.1) {
                System.out.println("woodworking\t" + doc.url);
            }
            if (recipeDetector.testP(dld) > 0.5) {
                System.out.println("recipe\t" + doc.url);
            }
            if (spamDetector.testP(parsed) > 0.5) {
                System.out.println("GA spam\t" + doc.url);
            }

        }

        return true;
    }

    @Override
    public void onFinish() {
    }
}
