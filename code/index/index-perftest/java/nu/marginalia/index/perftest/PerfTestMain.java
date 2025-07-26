package nu.marginalia.index.perftest;

import gnu.trove.list.array.TLongArrayList;
import nu.marginalia.api.searchquery.RpcQueryLimits;
import nu.marginalia.api.searchquery.model.query.NsfwFilterTier;
import nu.marginalia.api.searchquery.model.query.QueryParams;
import nu.marginalia.api.searchquery.model.query.SearchSpecification;
import nu.marginalia.api.searchquery.model.results.PrototypeRankingParameters;
import nu.marginalia.array.page.LongQueryBuffer;
import nu.marginalia.functions.searchquery.QueryFactory;
import nu.marginalia.functions.searchquery.query_parser.QueryExpansion;
import nu.marginalia.index.FullReverseIndexReader;
import nu.marginalia.index.PrioReverseIndexReader;
import nu.marginalia.index.forward.ForwardIndexReader;
import nu.marginalia.index.index.CombinedIndexReader;
import nu.marginalia.index.index.StatefulIndex;
import nu.marginalia.index.model.ResultRankingContext;
import nu.marginalia.index.model.SearchParameters;
import nu.marginalia.index.model.SearchTerms;
import nu.marginalia.index.positions.PositionsFileReader;
import nu.marginalia.index.query.IndexQuery;
import nu.marginalia.index.results.DomainRankingOverrides;
import nu.marginalia.index.results.IndexResultRankingService;
import nu.marginalia.index.results.model.ids.CombinedDocIdList;
import nu.marginalia.index.searchset.SearchSetAny;
import nu.marginalia.linkdb.docs.DocumentDbReader;
import nu.marginalia.segmentation.NgramLexicon;
import nu.marginalia.term_frequency_dict.TermFrequencyDict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PerfTestMain {


    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("Arguments: home-dir index-dir query numWarmupIters numIters");
            System.exit(255);
        }

        try {
            Path indexDir = Paths.get(args[0]);
            if (!Files.isDirectory(indexDir)) {
                System.err.println("Index directory is not a directory");
                System.exit(255);
            }
            Path homeDir = Paths.get(args[1]);
            String query = args[2];
            int numWarmupIters = Integer.parseInt(args[3]);
            int numIters = Integer.parseInt(args[4]);

            run(indexDir, homeDir, query, numWarmupIters, numIters);
        }
        catch (NumberFormatException e) {
            System.err.println("Arguments: data-dir  index-dir query numWarmupIters numIters");
            System.exit(255);
        }
        catch (Exception ex) {
            System.err.println("Error during testing");
            ex.printStackTrace();
            System.exit(255);
        }
        System.out.println(Arrays.toString(args));
    }

    private static CombinedIndexReader createCombinedIndexReader(Path indexDir) throws IOException {

        return new CombinedIndexReader(
                new ForwardIndexReader(
                        indexDir.resolve("ir/fwd-doc-id.dat"),
                        indexDir.resolve("ir/fwd-doc-data.dat"),
                        indexDir.resolve("ir/fwd-spans.dat")
                ),
                new FullReverseIndexReader(
                        "full",
                        indexDir.resolve("ir/rev-words.dat"),
                        indexDir.resolve("ir/rev-docs.dat"),
                        new PositionsFileReader(indexDir.resolve("ir/rev-positions.dat"))
                ),
                new PrioReverseIndexReader(
                        "prio",
                        indexDir.resolve("ir/rev-prio-words.dat"),
                        indexDir.resolve("ir/rev-prio-docs.dat")
                )
        );
    }

    private static IndexResultRankingService createIndexResultRankingService(Path indexDir, CombinedIndexReader combinedIndexReader) throws IOException, SQLException {
        return new IndexResultRankingService(
                new DocumentDbReader(indexDir.resolve("ldbr/documents.db")),
                new StatefulIndex(combinedIndexReader),
                new DomainRankingOverrides(null, Path.of("xxxx"))
        );
    }

    static QueryFactory createQueryFactory(Path homeDir) throws IOException {
        return new QueryFactory(
                new QueryExpansion(
                        new TermFrequencyDict(homeDir.resolve("model/tfreq-new-algo3.bin")),
                        new NgramLexicon()
                )
        );
    }

    public static void run(Path homeDir,
                           Path indexDir,
                           String rawQuery,
                           int numWarmupIters,
                           int numIters) throws IOException, SQLException
    {

        CombinedIndexReader indexReader = createCombinedIndexReader(indexDir);
        QueryFactory queryFactory = createQueryFactory(homeDir);
        IndexResultRankingService rankingService = createIndexResultRankingService(indexDir, indexReader);

        var queryLimits = RpcQueryLimits.newBuilder()
                .setTimeoutMs(10_000)
                .setResultsTotal(1000)
                .setResultsByDomain(10)
                .setFetchSize(4096)
                .build();
        SearchSpecification parsedQuery = queryFactory.createQuery(new QueryParams(rawQuery, queryLimits, "NONE", NsfwFilterTier.OFF), PrototypeRankingParameters.sensibleDefaults()).specs;

        System.out.println("Query compiled to: " + parsedQuery.query.compiledQuery);

        SearchParameters searchParameters = new SearchParameters(parsedQuery, new SearchSetAny());

        List<IndexQuery> queries = indexReader.createQueries(new SearchTerms(searchParameters.query, searchParameters.compiledQueryIds), searchParameters.queryParams);

        TLongArrayList allResults = new TLongArrayList();
        LongQueryBuffer buffer = new LongQueryBuffer(4096);

        for (var query : queries) {
            while (query.hasMore() && allResults.size() < 4096 ) {
                query.getMoreResults(buffer);
                allResults.addAll(buffer.copyData());
            }
            if (allResults.size() >= 4096)
                break;
        }
        allResults.sort();
        if (allResults.size() > 4096) {
            allResults.subList(4096,  allResults.size()).clear();
        }

        var docIds = new CombinedDocIdList(allResults.toArray());
        var rankingContext = ResultRankingContext.create(indexReader, searchParameters);

        System.out.println("Running warmup loop!");
        int sum = 0;
        for (int iter = 0; iter < numWarmupIters; iter++) {
            sum += rankingService.rankResults(rankingContext, docIds, true).size();
        }
        System.out.println("Warmup complete!");

        int sum2 = 0;
        List<Double> times = new ArrayList<>();
        for (int iter = 0; iter < numIters; iter++) {
            long start = System.nanoTime();
            sum2 += rankingService.rankResults(rankingContext, docIds, true).size();
            long end = System.nanoTime();
            times.add((end - start)/1_000_000.);
        }
        System.out.println("Best times: " + times.stream().mapToDouble(Double::doubleValue).sorted().limit(3).average().orElse(-1));
        System.out.println("Warmup sum: " + sum);
        System.out.println("Main sum: " + sum2);
        System.out.println(docIds.size());
    }
}
