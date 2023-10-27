package nu.marginalia.tools;

import com.google.inject.Guice;
import com.google.inject.Injector;
import nu.marginalia.converting.ConverterModule;
import nu.marginalia.crawling.io.CrawledDomainReader;
import nu.marginalia.crawling.model.CrawledDocument;
import nu.marginalia.crawling.model.CrawledDomain;
import nu.marginalia.crawling.model.SerializableCrawlData;
import nu.marginalia.process.log.WorkLog;
import nu.marginalia.service.module.DatabaseModule;
import nu.marginalia.tools.experiments.*;
import plan.CrawlPlanLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class ExperimentRunnerMain {

    private static Map<String, Class<? extends Experiment>> experiments = Map.of(
            "test", TestExperiment.class,
            "adblock", AdblockExperiment.class,
            "topic", TopicExperiment.class,
            "sentence-statistics", SentenceStatisticsExperiment.class,
            "site-statistics", SiteStatisticsExperiment.class,
            "debug-converter", DebugConverterExperiment.class
    );

    public static void main(String... args) throws IOException {
        if (args.length < 2) {
            System.err.println("Expected arguments: plan.yaml experiment-name [experiment-args]");
            return;
        }

        if (!experiments.containsKey(args[1])) {
            System.err.println("Valid experiment names: " + experiments.keySet());
            return;
        }

        Injector injector = Guice.createInjector(
                new DatabaseModule(),
                new ConverterModule()
        );

        Experiment experiment = injector.getInstance(experiments.get(args[1]));

        experiment.args(Arrays.copyOfRange(args, 2, args.length));

        Path basePath = Path.of(args[0]);
        var reader = new CrawledDomainReader();
        for (var item : WorkLog.iterable(basePath.resolve("crawler.log"))) {
            Path crawlDataPath = basePath.resolve(item.relPath());
            try (var stream = reader.createDataStream(crawlDataPath)) {
                experiment.process(stream);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        experiment.onFinish();
    }
}
