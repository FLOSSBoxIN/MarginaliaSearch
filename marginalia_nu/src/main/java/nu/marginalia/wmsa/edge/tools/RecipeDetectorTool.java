package nu.marginalia.wmsa.edge.tools;

import nu.marginalia.util.language.conf.LanguageModels;
import nu.marginalia.util.language.processing.SentenceExtractor;
import nu.marginalia.util.language.processing.model.DocumentLanguageData;
import nu.marginalia.wmsa.configuration.WmsaHome;
import nu.marginalia.wmsa.configuration.module.DatabaseModule;
import nu.marginalia.wmsa.edge.converting.processor.logic.RecipeDetector;
import nu.marginalia.wmsa.edge.crawling.CrawlPlanLoader;
import nu.marginalia.wmsa.edge.crawling.CrawledDomainReader;
import nu.marginalia.wmsa.edge.crawling.model.CrawledDocument;
import nu.marginalia.wmsa.edge.crawling.model.CrawledDomain;
import nu.marginalia.wmsa.edge.model.EdgeCrawlPlan;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static nu.marginalia.wmsa.edge.converting.processor.DocumentProcessor.isAcceptedContentType;

public class RecipeDetectorTool {
    private static final CrawledDomainReader reader = new CrawledDomainReader();
    private static final RecipeDetector detector = new RecipeDetector();
    private static final LanguageModels lm = WmsaHome.getLanguageModels();
    private static final SentenceExtractor sentenceExtractor = new SentenceExtractor(lm);

    private static final Set<String> urls = new HashSet<>(50_000_000);

    public static void main(String... args) throws IOException {
        EdgeCrawlPlan plan = new CrawlPlanLoader().load(Path.of(args[0]));
        DatabaseModule module = new DatabaseModule();

        try (var ds = module.provideConnection();
             var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            var rsp = stmt.executeQuery("SELECT URL FROM EC_URL_VIEW WHERE TITLE IS NOT NULL");
            while (rsp.next()) {
                urls.add(rsp.getString(1));
            }
        }
        catch (SQLException ex) {
            ex.printStackTrace();
        }

        ForkJoinPool pool = new ForkJoinPool(16);
        plan.forEachCrawledDomain(data -> pool.execute(() -> processDomain(data)));

        while (!pool.awaitQuiescence(1, TimeUnit.HOURS));
    }

    private static void processDomain(CrawledDomain domain) {
        if (domain.doc == null) return;
        for (var doc : domain.doc) {
            if (!urls.contains(doc.url))
                continue;

            if (isAcceptedContentType(doc) && "OK".equals(doc.crawlerStatus)) {
                processDocument(doc);
            }
        }
    }


    private static void processDocument(CrawledDocument doc) {
        Document parsedDocument = Jsoup.parse(doc.documentBody);

        parsedDocument.getElementsByTag("a").remove();
        parsedDocument.getElementsByTag("nav").remove();

        DocumentLanguageData dld = sentenceExtractor.extractSentences(parsedDocument);
        double prob = 100*detector.recipeP(dld);
        if (prob > 50) {
            System.out.printf("%3.2f\t%s\n", prob, doc.url);
        }
    }
}
